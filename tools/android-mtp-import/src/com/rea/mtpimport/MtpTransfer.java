package com.rea.mtpimport;

import android.mtp.MtpDevice;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;

/**
 * 真实 MTP 传输（PA / PB，issue #50）：只读相机对象，写到手机侧 `.part`，对账后改名到最终路径。
 *
 * 为什么先只用 {@link MtpDevice#importFile}：探针已经用它传过 3 张 24 MP RAW（73.29 MB /
 * 3.42 s，手机侧 MD5 与 PC 侧一致），但 `getPartialObject(handle, offset, size, buf)` 的
 * offset 语义一次都没在 ILCE-7C 上验证过。PA 若按推断做续传，可能写出一堆截断 ARW；
 * 所以这里明确拒绝 `resumeFrom > 0`，真正续传留给 PC 分节。
 *
 * PB 的取消：`importFile` 本身没有取消参数，唯一能真正打断阻塞中传输的办法是让原生写失败。
 * 这里改用 `importFile(int, ParcelFileDescriptor)`（android-31 起的公共 API），把 pfd 的
 * `close()` 注册到 {@link CancelToken}；用户点 Cancel 时 pfd 当场关闭，**试图**让 `importFile`
 * 尽快返回。本次未传满的 `.part` 由本类删除；**失败**残留的 `.part` 仍保留，生命周期归 #52
 * （不在这里预判）。
 *
 * 真机已验（2026-11-06，ILCE-7C 固件 2.00 / Android 15 / USB MTP / 小米 9）：
 * 取消时 pfd 关闭后原生写约 0.6 s 返回，本次未传满的半成品被删除且未记账；
 * 随后同一会话继续导入正常（只读 `getObjectInfo` 探活通过、下一张照常落盘）。
 * 但「重复 close 无副作用」仍未验，见 `closeQuietly`。
 * 万一某台机器上打不断，worker 会卡死，由 `ImportService.isCancelStuck()` + `Main` 的放弃/探活
 * 给界面出路；下一轮复用会话前会用只读 `getObjectInfo(handle)` 探活，失败落 `ERROR`。
 *
 * 相机侧只读：`importFile()` 是 GetObject，不删除、不格式化、不改设置、不触发快门。
 */
public final class MtpTransfer implements ResumableTransfer {

    private final MtpDevice device;
    private final Log log;

    public MtpTransfer(MtpDevice device, Log log) {
        this.device = device;
        this.log = log;
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
        if (resumeFrom > 0) {
            // PA / PB 明确不做 offset 续传：没验证过 getPartialObject 的语义。
            return new Result(Kind.FAILED, "断点续传未启用（getPartialObject 未验证）");
        }

        File part = new File(partPath);
        File outDir = part.getParentFile();
        if (outDir == null) {
            return new Result(Kind.FAILED, "半成品路径没有父目录");
        }
        if (!outDir.exists() && !outDir.mkdirs()) {
            return new Result(Kind.FAILED, "建目标目录失败：" + outDir);
        }
        if (part.exists() && !part.delete()) {
            return new Result(Kind.FAILED, "无法清理旧 .part：" + part);
        }

        ParcelFileDescriptor pfd = null;
        try {
            File dest = resolveDest(outDir, item.relPath);
            if (dest == null) {
                return new Result(Kind.FAILED, "目标路径越界：" + item.relPath);
            }

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
                    return new Result(Kind.CANCELLED, "已取消，本次半成品已删除");
                }
            }

            boolean imported = device.importFile(item.handle, pfd);
            closeQuietly(pfd);
            pfd = null;

            long got = part.length();
            if (imported && got == item.size) {
                // 已经完整传完：即使取消在最后一刻到达，也按成功落盘，避免丢掉完整文件。
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

            if (token != null && token.isCancelled()) {
                discardPart(part);
                log.line("已取消：" + item.relPath + "（未传满，半成品已删除）");
                return new Result(Kind.CANCELLED, "已取消，本次半成品已删除");
            }

            if (!imported) {
                // 未取消却返回 false：保留 .part（#52 的生命周期），本轮绝不记完成。
                return new Result(Kind.FAILED, "importFile 返回 false");
            }

            // 保留 .part，方便后续 PC 分节做真正的续传；但本轮绝不能记成完成。
            return new Result(Kind.FAILED,
                    "字节数不符：期望 " + item.size + "，实际 " + got);
        } catch (IOException e) {
            closeQuietly(pfd);
            pfd = null;
            if (token != null && token.isCancelled()) {
                discardPart(part);
                return new Result(Kind.CANCELLED, "已取消，本次半成品已删除");
            }
            return new Result(Kind.FAILED, "路径解析失败：" + e);
        } catch (Throwable t) {
            closeQuietly(pfd);
            pfd = null;
            if (token != null && token.isCancelled()) {
                discardPart(part);
                return new Result(Kind.CANCELLED, "已取消，本次半成品已删除");
            }
            return new Result(Kind.FAILED, "传输异常：" + t);
        } finally {
            if (token != null) token.clearOnCancel();
            closeQuietly(pfd);
        }
    }

    /** 删掉本次取消产生的半成品；删不掉只记日志，不能让它变成「已完成」。 */
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
