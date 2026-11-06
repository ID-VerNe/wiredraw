package com.rea.mtpimport;

import android.mtp.MtpDevice;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 真实 MTP 传输（PA / PB / PC，issue #50）：只读相机对象，写到手机侧 `.part`，对账后改名到最终路径。
 *
 * 两条传输路径：
 *
 * - `resumeFrom == 0`：整文件 {@link MtpDevice#importFile}。探针已验（ILCE-7C：3 张 24 MP RAW，
 *   73.29 MB / 3.42 s，手机侧 MD5 与 PC 侧一致）；PB 的取消靠关闭 pfd 尝试打断原生写。
 * - `resumeFrom > 0`：`getPartialObject(handle, offset, min(1 MiB, size - offset), buf)` 循环
 *   append 到 `.part`。语义与边界已在 ILCE-7C 上实测（issue #67）：
 *   `offset` 是绝对偏移；普通 ARW / JPEG 对象都支持；1 MiB 分块拼满 24.4 MB ARW 的字节数、
 *   MD5、TIFF 文件头与 `importFile` 完全一致（22.86 MB/s）；`length` 超剩余截断到 EOF；
 *   `offset == size` 返回 0；**`offset > size` 抛 IOException 并污染整个 MTP 会话**——
 *   所以这里永不请求越界偏移，且每块长度都不超过 `size - offset`。
 * - `resumeFrom == size`：半成品已满，直接 rename 落盘，不再向相机发读请求。
 *
 * `.part` 的命名由 {@link Journal#partPath} 按 `relPath + size` 生成（跨会话稳定，且异 size 的
 * 旧残留不会被误认），续传起点由 {@link Journal#resumeFrom} 按磁盘实际长度判定；
 * 失败/断线保留 `.part`，取消删除整个半成品（用户取消 = 从 0 重来）。
 *
 * PB 的取消（`importFile` 路径）：`importFile` 本身没有取消参数，唯一的手段是关掉 pfd、尝试让
 * 原生写失败。这里用 `importFile(int, ParcelFileDescriptor)`（android-31 起的公共 API），把 pfd 的
 * `close()` 注册到 {@link CancelToken}；本次机型实测约 0.6 s 返回；能否打断没有跨机型保证，
 * 打不断时由 `ImportService.isCancelStuck()` 给界面出路。
 *
 * 续传路径的取消：原生 `getPartialObject` 没有可关闭的 fd，只能每块之间判取消；单个 1 MiB 块
 * 在 22.86 MB/s 下约 44 ms，取消延迟不会超过「当前块返回」。同时注册关流动作，让正在追加的
 * 写尽快失败。
 *
 * 真机已验（2026-11-06，ILCE-7C 固件 2.00 / Android 15 / USB MTP / 小米 9）：
 * 整文件路径取消时 pfd 关闭后原生写约 0.6 s 返回，本次未传满的半成品被删除且未记账；
 * 随后同一会话继续导入正常。续传路径的分块语义来自同一机型的 #67 探针。
 *
 * 相机侧只读：`importFile()` 与 `getPartialObject()` 都是 GetObject 族，不删除、不格式化、
 * 不改设置、不触发快门。
 */
public final class MtpTransfer implements ResumableTransfer {

    /** 续传分块 1 MiB：探针实测 64 KiB 小块只有 0.38–0.43 MB/s，1 MiB 为 22.86 MB/s。 */
    private static final int CHUNK = 1 << 20;

    private final MtpDevice device;
    private final Log log;
    /** 与缩略图（#59）共用的会话 I/O 锁：同一 MtpDevice 不并行发命令。 */
    private final ReentrantLock ioLock;

    public MtpTransfer(MtpDevice device, Log log, ReentrantLock ioLock) {
        this.device = device;
        this.log = log;
        this.ioLock = ioLock;
    }

    @Override
    public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token) {
        if (device == null) {
            return new Result(Kind.FAILED, "MTP 会话已断开");
        }
        if (item == null || item.relPath.isEmpty()) {
            return new Result(Kind.FAILED, "目标路径为空");
        }
        if (item.size <= 0) {
            // 大小未知就无法对账；宁可失败，也不能把截断文件记成完成。
            return new Result(Kind.FAILED, "相机未给出对象大小");
        }

        File part = new File(partPath);
        File outDir = part.getParentFile();
        if (outDir == null) {
            return new Result(Kind.FAILED, "半成品路径没有父目录");
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            return new Result(Kind.FAILED, "建目标目录失败：" + outDir);
        }

        final File dest;
        try {
            dest = resolveDest(outDir, item.relPath);
        } catch (IOException e) {
            return new Result(Kind.FAILED, "路径解析失败：" + e);
        }
        if (dest == null) {
            return new Result(Kind.FAILED, "目标路径越界：" + item.relPath);
        }

        // 续传起点以磁盘为准：调用方说的 resumeFrom 比磁盘多时（日志与文件不一致），
        // 以实际落盘长度为准；超过 size 的半成品一律判损坏、从 0 重传。
        //
        // 反方向（resumeFrom < onDisk）当前不可达：ImportService 就是用 part.length() 算的
        // resumeFrom，两者恒等。若未来出现，append 模式会从文件末尾写，必须先截断到 from，
        // 不能直接续——这里保留现状并注明，不引入当前用不到的截断逻辑。
        long onDisk = part.isFile() ? part.length() : 0L;
        long from = Journal.resumeFrom(resumeFrom, item.size);
        if (from > 0 && from > onDisk) {
            from = Journal.resumeFrom(onDisk, item.size);
        }

        try {
            if (from == item.size) {
                log.line("半成品已满，直接落盘：" + item.relPath);
                return finish(item, part, dest);
            }
            if (from > 0) {
                return append(item, part, dest, from, token);
            }
            return whole(item, part, dest, token);
        } finally {
            if (token != null) token.clearOnCancel();
        }
    }

    /** `resumeFrom == 0`：已验的整文件路径，保持 PA / PB 的原有行为。 */
    private Result whole(ImportPlan.Item item, File part, File dest, CancelToken token) {
        if (part.exists() && !part.delete()) {
            return new Result(Kind.FAILED, "无法清理旧 .part：" + part);
        }

        ParcelFileDescriptor pfd = null;
        try {
            pfd = ParcelFileDescriptor.open(part,
                    ParcelFileDescriptor.MODE_WRITE_ONLY
                            | ParcelFileDescriptor.MODE_CREATE
                            | ParcelFileDescriptor.MODE_TRUNCATE);
            final ParcelFileDescriptor open = pfd;
            if (token != null) {
                // 取消动作：关闭正在写的 fd，让原生写提前失败。
                // 已验（2026-11-06，ILCE-7C 2.00 / Android 15 / USB MTP / 小米 9）：原生写约 0.6 s
                // 返回，半成品删除、未记账，下一轮同一会话继续导入正常。
                // 仍未验：close 的「重复调用无副作用」——android.jar 是 stub，不做承诺。
                // 若某台机器打不断，worker 会卡在原生调用里，由 ImportService.isCancelStuck() 给界面出路。
                token.setOnCancel(() -> closeQuietly(open));
                if (token.isCancelled()) {
                    closeQuietly(pfd);
                    pfd = null;
                    discardPart(part);
                    return new Result(Kind.CANCELLED, "已取消，整个半成品已删除（从 0 重来）");
                }
            }

            boolean imported;
            ioLock.lock();
            try {
                imported = device.importFile(item.handle, pfd);
            } finally {
                ioLock.unlock();
            }
            closeQuietly(pfd);
            pfd = null;

            long got = part.length();
            if (imported && got == item.size) {
                // 已经完整传完：即使取消在最后一刻到达，也按成功落盘，避免丢掉完整文件。
                return finish(item, part, dest);
            }

            if (token != null && token.isCancelled()) {
                discardPart(part);
                log.line("已取消：" + item.relPath + "（未传满，整个半成品已删除）");
                return new Result(Kind.CANCELLED, "已取消，整个半成品已删除（从 0 重来）");
            }

            if (!imported) {
                // 未取消却返回 false：保留 .part（#52 的生命周期），本轮绝不记完成。
                return new Result(Kind.FAILED, "importFile 返回 false");
            }

            // 保留 .part 供下一轮续传；但本轮绝不能记成完成。
            return new Result(Kind.FAILED,
                    "字节数不符：期望 " + item.size + "，实际 " + got);
        } catch (Throwable t) {
            closeQuietly(pfd);
            pfd = null;
            if (token != null && token.isCancelled()) {
                discardPart(part);
                return new Result(Kind.CANCELLED, "已取消，整个半成品已删除（从 0 重来）");
            }
            return new Result(Kind.FAILED, "传输异常：" + t);
        }
    }

    /**
     * `0 < resumeFrom < size`：从绝对偏移续读，append 到已有 `.part`。
     *
     * 分块上限 1 MiB、长度不超过剩余字节：`offset > size` 会污染整个 MTP 会话（#67 实测），
     * 这里任何一种都不可能发出去。
     */
    private Result append(ImportPlan.Item item, File part, File dest, long from, CancelToken token) {
        FileOutputStream out = null;
        final AtomicReference<FileOutputStream> ref = new AtomicReference<>();
        try {
            out = new FileOutputStream(part, true);
            ref.set(out);
            if (token != null) {
                // 关流让正在追加的写尽快失败；原生 getPartialObject 只能等当前块返回。
                token.setOnCancel(() -> closeQuietly(ref.get()));
                if (token.isCancelled()) {
                    closeQuietly(out);
                    out = null;
                    ref.set(null);
                    discardPart(part);
                    return new Result(Kind.CANCELLED, "已取消，整个半成品已删除（从 0 重来）");
                }
            }

            byte[] buf = new byte[CHUNK];
            long offset = from;
            while (offset < item.size) {
                if (token != null && token.isCancelled()) {
                    closeQuietly(out);
                    out = null;
                    ref.set(null);
                    discardPart(part);
                    log.line("已取消：" + item.relPath + "（续传未完成，整个半成品已删除）");
                    return new Result(Kind.CANCELLED, "已取消，整个半成品已删除（从 0 重来）");
                }
                int want = (int) Math.min((long) CHUNK, item.size - offset);
                long n;
                ioLock.lock();
                try {
                    n = device.getPartialObject(item.handle, offset, want, buf);
                } finally {
                    ioLock.unlock();
                }
                if (n <= 0) {
                    closeQuietly(out);
                    out = null;
                    ref.set(null);
                    // 保留已写入的半成品，下一轮可从 offset 继续。
                    return new Result(Kind.FAILED,
                            "getPartialObject 在 offset=" + offset + " 返回 " + n
                                    + "（已保留半成品至 " + offset + " 字节）");
                }
                out.write(buf, 0, (int) n);
                offset += n;
            }

            closeQuietly(out);
            out = null;
            ref.set(null);
            log.line("续传完成：" + item.relPath + "（从 " + from + " 字节继续，共 "
                    + item.size + " 字节）");
            return finish(item, part, dest);
        } catch (Throwable t) {
            closeQuietly(out);
            out = null;
            ref.set(null);
            if (token != null && token.isCancelled()) {
                discardPart(part);
                return new Result(Kind.CANCELLED, "已取消，整个半成品已删除（从 0 重来）");
            }
            return new Result(Kind.FAILED, "续传异常：" + t + "（半成品已保留）");
        }
    }

    /** 对账 + 覆盖 + rename；只有字节数等于相机侧大小时才落最终文件。 */
    private Result finish(ImportPlan.Item item, File part, File dest) {
        long got = part.length();
        if (got != item.size) {
            // 保留 .part 供下一轮续传；本轮绝不能记成完成。
            return new Result(Kind.FAILED,
                    "字节数不符：期望 " + item.size + "，实际 " + got);
        }
        File parent = dest.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return new Result(Kind.FAILED, "建子目录失败：" + parent);
        }
        if (dest.exists() && !dest.delete()) {
            return new Result(Kind.FAILED, "无法覆盖已有文件：" + dest);
        }
        if (!part.renameTo(dest)) {
            return new Result(Kind.FAILED, "改名失败：" + part + " → " + dest);
        }
        log.line("已落盘：" + item.relPath + "（" + got + " 字节）");
        return Result.of(Kind.OK);
    }

    /**
     * 删掉整个半成品（含上一轮已续的字节，取消 = 从 0 重来）；
     * 删不掉只记日志，不能让它变成「已完成」。
     */
    private void discardPart(File part) {
        if (part.exists() && !part.delete()) {
            log.line("半成品删除失败（不会记完成）：" + part);
        }
    }

    private static void closeQuietly(ParcelFileDescriptor pfd) {
        if (pfd == null) return;
        try {
            pfd.close();
        } catch (Throwable ignored) {
            // 取消路径下 fd 可能已关闭；重复 close 的副作用仍未验（真机只验过「取消一次」）。
        }
    }

    private static void closeQuietly(FileOutputStream out) {
        if (out == null) return;
        try {
            out.close();
        } catch (Throwable ignored) {
            // 取消路径下流可能已被关闭；重复 close 不影响结果。
        }
    }

    /**
     * 把相机给的 relPath 拼到 outDir 下，并挡掉 `..` / 绝对路径逃逸。
     * 相机文件名理论上受机身控制，但这是系统边界，不能默认可信。
     */
    private static File resolveDest(File outDir, String relPath) throws IOException {
        File dest = new File(outDir, relPath);
        String root = outDir.getCanonicalPath();
        String target = dest.getCanonicalPath();
        if (target.equals(root)) return null;
        if (!target.startsWith(root + File.separator)) return null;
        return dest;
    }
}
