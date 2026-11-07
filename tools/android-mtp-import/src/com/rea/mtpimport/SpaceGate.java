package com.rea.mtpimport;

import java.util.function.LongSupplier;

/**
 * 空间预检（G9a，从 `Main.refreshSpaceCheck` / `precheckSpace` 抽出，纯 JDK）。
 *
 * 两段共用同一判据（`SpaceCheck.enough`）：照照片墙时刷新禁用态；点主 CTA 前再判一次，
 * 防止「预检之后、点击之前」空间被别的应用吃掉（PD / P-09 / issue #124）。
 */
public final class SpaceGate {

    public interface Sink {
        void setSpaceStatus(boolean low, String detail);

        void logLine(String line);
    }

    private SpaceGate() {
    }

    /**
     * 照片墙出现 / 勾选变化时刷新：计划为空（可见集合里没有可导出文件）时不做拦截。
     *
     * `freeBytes` 用 supplier 而不是现值：原实现在 `plan.isEmpty()` 早退之后才 `StatFs`，
     * 空计划不该空转一次磁盘统计（失败时还会多一条日志）。
     *
     * @param plan 当前可见集合算出的清单；null 等价于空
     */
    public static void refresh(Sink sink, ImportPlan plan, LongSupplier freeBytes) {
        if (plan == null || plan.isEmpty()) {
            sink.setSpaceStatus(false, "");
            return;
        }
        long free = freeBytes.getAsLong();
        long planned = plan.totalBytes();
        boolean enough = SpaceCheck.enough(free, planned);
        sink.setSpaceStatus(!enough, enough ? "" : SpaceCheck.describe(free, planned));
    }

    /** @return false = 空间不足，已落 `Low space` 并记日志；调用方直接 return。 */
    public static boolean precheck(Sink sink, ImportPlan plan, long freeBytes) {
        long planned = plan == null ? 0 : plan.totalBytes();
        if (!SpaceCheck.enough(freeBytes, planned)) {
            sink.setSpaceStatus(true, SpaceCheck.describe(freeBytes, planned));
            sink.logLine("空间不足：计划 " + planned + " 字节，可用 " + freeBytes + " 字节");
            return false;
        }
        sink.setSpaceStatus(false, "");
        return true;
    }
}
