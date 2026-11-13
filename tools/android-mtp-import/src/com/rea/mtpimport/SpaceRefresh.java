package com.rea.mtpimport;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 空间预检的调度状态（#374 / IA-7，纯 JDK）。
 *
 * `refreshSpaceCheck` 原来在 UI 线程里连着做两件重活：对最多 511 个单元
 * `ImportPlan.of`（filter + sort + 三个新列表），再 `StatFs.getAvailableBytes()` 一次系统调用。
 * demo 路径首行就 return，所以这两笔开销从来没进过帧预算的实测（issue 原文的口径修正）。
 *
 * 这个类只管**状态**，不碰线程：后台任务由 `Main` 投递，这里负责
 * ① 用单调递增序号让迟到的旧结果作废；
 * ② 把可用字节缓存一个很短的 TTL，避免每次勾选都重新读盘。
 * 时钟与取数都注入，于是这两条能在纯 JDK 自检里跑，而不需要真机或相机。
 *
 * 注意：点主 CTA 前的 `precheckSpace` **不走这个缓存**——那道防线就是为了
 * 抓住「预检之后、点击之前」空间被别的应用吃掉，缓存会让它失效。
 */
public final class SpaceRefresh {

    /** 可用空间缓存时长：1.5 s，落在 issue 建议的「1–2 秒」区间内。 */
    public static final long FREE_BYTES_TTL_MS = 1500L;

    private final LongSupplier clock;
    private long generation;
    private boolean cancelled;
    private boolean hasCache;
    private long cachedFree;
    private long cachedAt;

    public SpaceRefresh(LongSupplier clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** UI 线程调用：每次提交都让上一批在飞结果过期。 */
    public long submit() {
        return ++generation;
    }

    /** 只有最新一次提交的结果允许落到界面；Activity 销毁后一律不落。 */
    public boolean isCurrent(long seq) {
        return !cancelled && seq != 0L && seq == generation;
    }

    /** `onDestroy`：不可逆地作废所有在飞结果，避免销毁后回写界面状态。 */
    public void cancel() {
        cancelled = true;
        ++generation;
    }

    /**
     * 取可用字节；TTL 内复用上次结果。时钟回拨时也重新取数，
     * 免得系统时间被改小之后缓存永远不过期。
     */
    public long freeBytes(LongSupplier probe) {
        Objects.requireNonNull(probe, "probe");
        long now = clock.getAsLong();
        if (!hasCache || now < cachedAt || now - cachedAt >= FREE_BYTES_TTL_MS) {
            cachedFree = probe.getAsLong();
            cachedAt = now;
            hasCache = true;
        }
        return cachedFree;
    }
}
