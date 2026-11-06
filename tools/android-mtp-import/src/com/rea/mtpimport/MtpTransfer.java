package com.rea.mtpimport;

import android.mtp.MtpDevice;

import java.io.File;
import java.io.IOException;

/**
 * 真实 MTP 传输（PA，issue #50）：只读相机对象，写到手机侧 `.part`，对账后改名到最终路径。
 *
 * 为什么先只用 {@link MtpDevice#importFile}：探针已经用它传过 3 张 24 MP RAW（73.29 MB /
 * 3.42 s，手机侧 MD5 与 PC 侧一致），但 `getPartialObject(handle, offset, size, buf)` 的
 * offset 语义一次都没在 ILCE-7C 上验证过。PA 若按推断做续传，可能写出一堆截断 ARW；
 * 所以这里明确拒绝 `resumeFrom > 0`，真正续传留给 PC 分节。
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
    public Result transfer(ImportPlan.Item item, String partPath, long resumeFrom) {
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
            // PA 明确不做 offset 续传：没验证过 getPartialObject 的语义。
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

        try {
            File dest = resolveDest(outDir, item.relPath);
            if (dest == null) {
                return new Result(Kind.FAILED, "目标路径越界：" + item.relPath);
            }

            boolean imported = device.importFile(item.handle, part.getAbsolutePath());
            if (!imported) {
                return new Result(Kind.FAILED, "importFile 返回 false");
            }

            long got = part.length();
            if (got != item.size) {
                // 保留 .part，方便后续 PC 分节做真正的续传；但本轮绝不能记成完成。
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
        } catch (IOException e) {
            return new Result(Kind.FAILED, "路径解析失败：" + e);
        } catch (Throwable t) {
            return new Result(Kind.FAILED, "传输异常：" + t);
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
