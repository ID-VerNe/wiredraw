package com.rea.mtpimport;

import android.mtp.MtpDevice;
import android.os.ParcelFileDescriptor;

import java.io.File;
import java.io.IOException;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.LongConsumer;

/**
 * 真实 MTP 传输（PA / PB，issue #50）：只读相机对象，写到手机侧 `.part`，对账后改名到最终路径。
 *
 * **只保留整文件路径**（`MtpDevice#importFile`）。探针已验（ILCE-7C：3 张 24 MP RAW，
 * 73.29 MB / 3.42 s，手机侧 MD5 与 PC 侧一致）；PB 的取消靠关闭 pfd 尝试打断原生写。
 *
 * **续传已停用（止血，issue #177）**：2026-11-07 真机判别（E2 MI 9 / Android 15 API 35 /
 * ILCE-7C fw2.00 / USB MTP）得到精确语义：非零 `offset` 只有在恰好等于相机内部游标时才被
 * 采纳；对不上时忽略 offset、从对象头返回。「游标」是相机侧读流的位置（相机在会话内维护）。
 * 从 0 起逐块顺读永远对；跳读 / 续传一定错，而且续读不省带宽（要先从 0 读到该位置）。
 * 把遗留 `.part` 当可信前缀 append 会得到「字节数对、内容错、却报 OK」的文件；
 * 满尺寸残留直接 rename 更是把残渣当成品。
 * 因此本类不再发 `getPartialObject`，也不再保留 `from == size` 直通：
 * 任何既有 `.part` 一律视为不可信，先删除再从 0 整段重写。
 *
 * `.part` 的命名仍由 {@link Journal#partPath} 按 `relPath + size` 生成（跨会话稳定，且异 size 的
 * 旧残留不会被误认），但它只是临时文件名，不再承载「已传前缀」语义。
 * 失败/断线保留 `.part`（下一轮从 0 重写），取消删除整个半成品。
 *
 * PB 的取消：`importFile` 本身没有取消参数，唯一的手段是关掉 pfd、尝试让原生写失败。
 * 这里用 `importFile(int, ParcelFileDescriptor)`（android-31 起的公共 API），把 pfd 的
 * `close()` 注册到 {@link CancelToken}；本次机型实测约 0.6 s 返回；能否打断没有跨机型保证，
 * 打不断时由 `ImportService.isCancelStuck()` 给界面出路。
 *
 * 真机已验（2026-11-06，ILCE-7C 固件 2.00 / Android 15 / USB MTP / 小米 9）：
 * 整文件路径取消时 pfd 关闭后原生写约 0.6 s 返回，本次未传满的半成品被删除且未记账；
 * 随后同一会话继续导入正常。
 *
 * 相机侧只读：`importFile()` 是 GetObject 族，不删除、不格式化、不改设置、不触发快门。
 */
public final class MtpTransfer implements ResumableTransfer {

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
    public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom, CancelToken token,
                           LongConsumer progress) {
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
        try {
            Dirs.ensure(outDir);
        } catch (IOException e) {
            return new Result(Kind.FAILED, e.getMessage());
        }

        final File dest;
        try {
            dest = DestPolicy.resolve(outDir, item.relPath);
        } catch (IOException e) {
            return new Result(Kind.FAILED, "路径解析失败：" + e);
        }
        if (dest == null) {
            return new Result(Kind.FAILED, "目标路径越界：" + item.relPath);
        }

        // #177：续传已停用。`resumeFrom` 只为保持调用契约，不能作为读取起点；
        // 任何既有 `.part` 都在 whole() 里先删掉，再从 0 整段重写。
        if (resumeFrom > 0) {
            log.line("忽略续传起点 " + resumeFrom + "（续传已停用 #177）：" + item.relPath);
        }

        try {
            return whole(item, part, dest, token);
        } finally {
            if (token != null) token.clearOnCancel();
        }
    }

    /** 整文件路径：旧 `.part` 一律作废，`importFile` 从头写满后对账、改名。 */
    private Result whole(ImportPlan.Item item, File part, File dest, CancelToken token) {
        if (part.exists()) {
            long residue = part.length();
            if (!part.delete()) {
                return new Result(Kind.FAILED, "无法清理旧 .part（续传已停用，必须从 0 重写）：" + part);
            }
            if (residue > 0) {
                log.line("作废旧半成品（" + residue + " 字节；续传已停用，从 0 重传）："
                        + item.relPath);
            }
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
                    return new Result(Kind.CANCELLED, UiText.transferCancelled());
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
                return new Result(Kind.CANCELLED, UiText.transferCancelled());
            }

            if (!imported) {
                // 未取消却返回 false：保留 .part（#52 的生命周期），本轮绝不记完成。
                return new Result(Kind.FAILED, "importFile 返回 false");
            }

            // 保留 .part 留给 #52 的生命周期；本轮绝不能记成完成，下一轮也从 0 重写（#177）。
            return new Result(Kind.FAILED, UiText.sizeMismatch(item.size, got));
        } catch (Throwable t) {
            closeQuietly(pfd);
            pfd = null;
            if (token != null && token.isCancelled()) {
                discardPart(part);
                return new Result(Kind.CANCELLED, UiText.transferCancelled());
            }
            return new Result(Kind.FAILED, "传输异常：" + t);
        }
    }

    /**
     * 对账 + 落盘冲突检查 + rename；只有字节数等于相机侧大小时才落最终文件。
     *
     * **尺寸对账不等于内容校验（#177）**：这里只能确认字节数，无法校验字节内容，
     * 因此成功文案必须写明「内容未校验」，不能把尺寸对账说成校验通过。
     *
     * **边界（#54）**：这里不解决「两个存储同名文件争同一路径」的布局问题，只保证不静默丢数据——
     * 目标已存在且尺寸不同就拒绝覆盖；尺寸相同按 `relPath + size` 口径视为同一文件（#20 的已知边界）。
     */
    private Result finish(ImportPlan.Item item, File part, File dest) {
        long got = part.length();
        if (got != item.size) {
            // 保留 .part；本轮绝不能记成完成。下一轮从 0 重写（#177）。
            return new Result(Kind.FAILED, UiText.sizeMismatch(item.size, got));
        }
        try {
            Dirs.ensure(dest.getParentFile());
        } catch (IOException e) {
            return new Result(Kind.FAILED, e.getMessage());
        }
        DestPolicy.Existing existing = DestPolicy.existing(dest, item.size);
        if (existing == DestPolicy.Existing.DIFFERENT_SIZE) {
            // 不删旧文件：宁可这一项失败，也不能把已经导入的照片覆盖掉。
            return new Result(Kind.FAILED,
                    "目标已存在同名文件但大小不同（现有 " + dest.length() + " / 期望 "
                            + item.size + "），拒绝覆盖：" + dest);
        }
        if (existing == DestPolicy.Existing.SAME_SIZE) {
            // #177：本轮没有写成品，只是「已存在同尺寸文件」。按 SKIPPED 记，不记 OK；
            // 尺寸相同不等于内容正确（#20 的同路径同尺寸边界仍在）。
            if (part.exists() && !part.delete()) log.line("半成品删除失败（不影响结果）：" + part);
            log.line(UiText.sameSizeExists() + "：" + item.relPath);
            return new Result(Kind.SKIPPED, UiText.sameSizeExists());
        }
        if (!part.renameTo(dest)) {
            return new Result(Kind.FAILED, "改名失败：" + part + " → " + dest);
        }
        log.line("已导入（尺寸核对通过，内容未校验）：" + item.relPath + "（" + got + " 字节）");
        return new Result(Kind.OK, "尺寸核对通过（内容未校验）");
    }

    /**
     * 删掉整个半成品（续传停用后，任何残留都不可信；取消 = 从 0 重来）；
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

}
