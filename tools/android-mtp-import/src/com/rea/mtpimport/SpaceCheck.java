package com.rea.mtpimport;

import java.util.Locale;

/**
 * 空间预检（P-09，纯 JDK，JVM 自检覆盖）。
 *
 * 口径来自 `UI-SPEC.md` P-09：目标剩余空间 < 计划大小 + 5% 缓冲时必须拦下主 CTA。
 * 这里只做纯计算，取剩余空间与禁用按钮由调用方负责，保证本类可在空 classpath 下自检。
 */
public final class SpaceCheck {

    private SpaceCheck() {
    }

    /**
     * 计划大小 + 5% 缓冲，缓冲向上取整。
     *
     * 例：1000 字节 → 1050；1 字节 → 2（5% 不足 1 字节也留 1 字节）。
     * `plannedBytes <= 0` 返回 0：没有计划时不应拦导入。
     */
    public static long requiredBytes(long plannedBytes) {
        if (plannedBytes <= 0) return 0L;
        long buffer = plannedBytes / 20 + (plannedBytes % 20 == 0 ? 0 : 1);
        if (plannedBytes > Long.MAX_VALUE - buffer) return Long.MAX_VALUE;
        return plannedBytes + buffer;
    }

    /** 剩余空间是否足够；计划为 0 时恒为 true。 */
    public static boolean enough(long freeBytes, long plannedBytes) {
        return freeBytes >= requiredBytes(plannedBytes);
    }

    /**
     * Details 抽屉里的差额行（P-09：「差额进 Details」）。
     *
     * 只在不足时展示；用 1 位小数的 GB，避免主视觉出现容量数字。
     */
    public static String describe(long freeBytes, long plannedBytes) {
        return String.format(Locale.US, "Need %.1f GB, free %.1f GB",
                requiredBytes(plannedBytes) / 1073741824.0,
                Math.max(0L, freeBytes) / 1073741824.0);
    }
}
