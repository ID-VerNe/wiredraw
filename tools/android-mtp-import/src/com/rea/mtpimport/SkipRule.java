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
}
