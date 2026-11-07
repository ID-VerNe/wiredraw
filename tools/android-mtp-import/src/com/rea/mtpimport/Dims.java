package com.rea.mtpimport;

/**
 * 尺寸基线（G7a / issue #215 / F-12、F-21）。
 *
 * 只收 spec 有出处、或「现状即口径」需要注记的两类数：进度环与抽屉最大高度。
 * 单位一律 dp（V-02）；换算成 Compose 的 `Dp` 由调用方做（`Dims.X.dp`）。
 */
public final class Dims {

    private Dims() {}

    /** 传输环外径（V-22：`UI-SPEC.md:611`）。 */
    public static final float TRANSFER_RING_DIAMETER_DP = 180f;

    /** 传输环描边（V-22）。 */
    public static final float TRANSFER_RING_STROKE_DP = 3.5f;

    /**
     * 扫描环外径：与传输环同径，`SCANNING` 的细环沿用。
     *
     * UI-SPEC 的 V-22 只登记了**传输环**（180 / 3.5），扫描环 180 / 2 没有出处；
     * 按「现状即口径」具名保留，待规格补条后再对齐（F-12）。
     */
    public static final float SCAN_RING_DIAMETER_DP = 180f;

    /** 扫描环描边 2 dp：spec 缺 V-22 的扫描环条目，来源未定（F-12）。 */
    public static final float SCAN_RING_STROKE_DP = 2f;

    /** 抽屉最大高度：spec 未给值，沿用现状 480 dp（F-21）。 */
    public static final float DRAWER_MAX_HEIGHT_DP = 480f;
}
