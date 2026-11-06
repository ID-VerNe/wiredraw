package com.rea.mtpimport;

import java.io.File;
import java.io.IOException;

/**
 * 落盘冲突策略（issue #54）：纯 JDK，便于 JVM 自检覆盖。
 *
 * <p>**边界**：本方案不修「两个同名文件仍争同一落盘路径」这件事——relPath 里没有 storage 维度，
 * 两个存储的同名文件本来就只能对应一个目标路径。它保证的是**不静默丢数据**：
 * 目标已存在且尺寸不同就显式拒绝覆盖；尺寸相同按 `relPath + size` 口径视为同一文件（与
 * Journal / 已导入索引一致，见 #20 的已知边界）。</p>
 */
public final class DestPolicy {

    /** 目标路径上已有文件时怎么处理。 */
    public enum Existing {
        /** 没有同名文件，正常落盘。 */
        NONE,
        /** 同名且尺寸相同：按同一文件处理，不覆盖、不重传。 */
        SAME_SIZE,
        /** 同名但尺寸不同：拒绝覆盖，显式失败并保留原文件。 */
        DIFFERENT_SIZE
    }

    private DestPolicy() { }

    /** 把 relPath 解析到 outDir 下的目标位置；越界（绝对路径/`..`）返回 null。 */
    public static File resolve(File outDir, String relPath) throws IOException {
        File dest = new File(outDir, relPath);
        String root = outDir.getCanonicalPath();
        String target = dest.getCanonicalPath();
        if (target.equals(root)) return null;
        if (!target.startsWith(root + File.separator)) return null;
        return dest;
    }

    /** 目标已存在时按尺寸判定。 */
    public static Existing existing(File dest, long expectedSize) {
        if (!dest.exists()) return Existing.NONE;
        if (dest.isFile() && dest.length() == expectedSize) return Existing.SAME_SIZE;
        return Existing.DIFFERENT_SIZE;
    }
}
