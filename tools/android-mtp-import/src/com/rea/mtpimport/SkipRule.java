package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;

/**
 * 「这个文件能不能跳过」的**唯一**判据（issue #209 / G1）。
 *
 * 判据是「与」不是「或」：账本记录完成 **并且** 磁盘上有尺寸吻合的成品才算跳过。
 * 原来这两半分散在 `ImportService` 的 `journal.isComplete(...) && targetMatches(...)`、
 * `DestPolicy.existing(...)` 与 `MtpTransfer.finish(...)` 里各写一遍，任何一处漏掉磁盘核对
 * 都会让「账本说完成、成品其实没了」的文件被静默跳过（#177）。
 *
 * 三个结果把「没记过」和「记过但磁盘不符」分开：后者要重新导入，而且调用方要能记日志。
 */
public final class SkipRule {

    public enum Verdict {
        /** 账本没记完成 → 正常导入。 */
        TODO,
        /** 账本完成且磁盘成品尺寸一致 → 跳过。 */
        SKIP,
        /** 账本完成但成品缺失 / 截断 / 路径越界 → 重新导入。 */
        STALE
    }

    private SkipRule() { }

    /**
     * 按「账本 + 磁盘」合判一次。
     *
     * 路径越界（{@link DestPolicy#resolve} 返回 null）与解析异常都归 `STALE`：
     * 不许在预检阶段冒充跳过，也不在这里抢传输层的路径报错。
     */
    public static Verdict check(Journal journal, File outDir, String relPath, long size) {
        if (!journal.isComplete(relPath, size)) return Verdict.TODO;
        try {
            File dest = DestPolicy.resolve(outDir, relPath);
            if (dest == null) return Verdict.STALE;
            return DestPolicy.existing(dest, size) == DestPolicy.Existing.SAME_SIZE
                    ? Verdict.SKIP : Verdict.STALE;
        } catch (IOException e) {
            return Verdict.STALE;
        }
    }

    /**
     * 成品存在性探针（issue #28 分节 PB）：成品进 MediaStore 后不再是 `outDir` 下的 File，
     * 由 Android 侧注入按 `relPath` 查询相册条目尺寸的实现；返回 &lt;0 表示不存在 / 查询失败。
     */
    @FunctionalInterface
    public interface ProductProbe {
        long sizeOf(String relPath);
    }

    /**
     * 按「账本 + 成品探针」合判一次（issue #28 分节 PB）。
     *
     * 成品进 MediaStore 后不再是 `outDir` 下的 File，由注入的探针查相册条目尺寸；
     * 探针返回 &lt;0（不存在 / 查询失败）或尺寸不符都归 `STALE`，与私有 File 路径同口径：
     * 账本说完成也不单独作数（#177），这样既不会把整文件重下，也不会在成品被用户删掉后静默跳过。
     */
    public static Verdict check(Journal journal, ProductProbe probe, String relPath, long size) {
        if (!journal.isComplete(relPath, size)) return Verdict.TODO;
        long existing;
        try {
            existing = probe.sizeOf(relPath);
        } catch (RuntimeException e) {
            return Verdict.STALE;
        }
        return DestPolicy.existingBySize(existing, size) == DestPolicy.Existing.SAME_SIZE
                ? Verdict.SKIP : Verdict.STALE;
    }
}
