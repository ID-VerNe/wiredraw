package com.rea.mtpimport;

/**
 * 排版基线（G7a / issue #215 / F-11）。
 *
 * 唯一事实来源是 `UI-SPEC.md:422-434` 的 §3.1 表；这里只登记**实际在用**的五档，
 * 未使用的 `display` / `headline-*` / `body-lg` / `label-sm` 不搬（避免第二份死数据）。
 * `ui/Theme.kt` 的五个 `TextStyle` 从这里取字号 / 行高 / 字重 / 字距，`SelfTest` 逐项断言。
 *
 * 单位口径（V-02）：字号与行高是 sp，字距是 em（相对字号）。
 */
public final class TypeTokens {

    private TypeTokens() {}

    /** `body-md`：主 CTA 文案、顶栏设备短名（`a7C`）。 */
    public static final float BODY_MD_SIZE_SP = 14f;
    public static final float BODY_MD_LINE_HEIGHT_SP = 20f;
    public static final int BODY_MD_WEIGHT = 400;
    public static final float BODY_MD_TRACKING_EM = -0.005f;

    /** `body-sm`：日期组头、顶栏 `Disconnected` / `Reconnect`。 */
    public static final float BODY_SM_SIZE_SP = 12f;
    public static final float BODY_SM_LINE_HEIGHT_SP = 16f;
    public static final int BODY_SM_WEIGHT = 400;
    public static final float BODY_SM_TRACKING_EM = 0f;

    /** `label-md`：环下 `12/36`。 */
    public static final float LABEL_MD_SIZE_SP = 13f;
    public static final float LABEL_MD_LINE_HEIGHT_SP = 16f;
    public static final int LABEL_MD_WEIGHT = 500;
    public static final float LABEL_MD_TRACKING_EM = 0.01f;

    /** `display-sm`：状态主文案、环内百分比、DONE 的 `36 · 858 MB`。 */
    public static final float DISPLAY_SM_SIZE_SP = 24f;
    public static final float DISPLAY_SM_LINE_HEIGHT_SP = 28f;
    public static final int DISPLAY_SM_WEIGHT = 600;
    public static final float DISPLAY_SM_TRACKING_EM = -0.02f;

    /** `mono-data`：`photo` 胶囊与顶栏计数胶囊（等宽数字，`tnum`）。 */
    public static final float MONO_DATA_SIZE_SP = 12f;
    public static final float MONO_DATA_LINE_HEIGHT_SP = 16f;
    public static final int MONO_DATA_WEIGHT = 400;
    public static final float MONO_DATA_TRACKING_EM = 0f;
}
