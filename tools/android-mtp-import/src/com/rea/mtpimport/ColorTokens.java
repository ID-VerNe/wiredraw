package com.rea.mtpimport;

/**
 * UI-SPEC §3.3 的 light token 数值与派生关系（纯 JDK，不 import android.*，issue #78）。
 *
 * 这里是**唯一事实来源**：`ui/Theme.kt` 的 Compose `Color` 从本类取 ARGB 值，
 * `Pill` 的底色/前景也走 {@link #pillBackground(boolean)} / {@link #pillForeground(boolean)}。
 * 把数值留在 Kotlin 里、断言另抄一份，改一处漏一处就是本轮要修的坑
 * （`ui/Theme.kt:18` 的白色 `canvas` 与规范的画布黑同名反向）。
 *
 * 对比度口径：WCAG 2.1 相对亮度，2026-11-06 实算。深色面（{@link #CANVAS}）上的
 * 文字/图标**只能**用 {@link #SURFACE_WHITE}（16.83:1）；{@link #INK} 压 `#1d1d1f`
 * 只有 1.12:1，严禁。
 */
public final class ColorTokens {

    /** 页面底（library / transfer / 抽屉 / 对话框）：规范 `surface-container-lowest` `#ffffff`。 */
    public static final int SURFACE_WHITE = 0xFFFFFFFF;

    /** 主 CTA 实心底（文字近黑）——**只**此一处，不得用于正文/图标。 */
    public static final int INK = 0xFF111111;

    /** 正文与图标（on-surface）。 */
    public static final int ON_SURFACE = 0xFF1A1C1C;

    /** 次级文字（on-surface-variant）。 */
    public static final int ON_SURFACE_VARIANT = 0xFF444748;

    /** 图标/描边（outline）。 */
    public static final int OUTLINE = 0xFF747878;

    /** 装饰分隔（outline-variant），不承载文字。 */
    public static final int OUTLINE_SOFT = 0xFFC4C7C7;

    /** 计数胶囊底与无缩略图灰块（surface-container）。 */
    public static final int SURFACE_CONTAINER = 0xFFEEEEEE;

    /** 照片页降级块底（surface-container-low）。 */
    public static final int SURFACE_CONTAINER_LOW = 0xFFF3F3F4;

    /** 错误色。 */
    public static final int ERROR = 0xFFBA1A1A;

    /** 画布黑：`photo` 全幅底 / 遮罩 / `photo` 胶囊实底 / 系统栏浅色图标底。 */
    public static final int CANVAS = 0xFF1D1D1F;

    /** 抽屉遮罩：画布黑 40% alpha；只是装饰，不承载正文。 */
    public static final int SCRIM = 0x661D1D1F;

    private ColorTokens() {
    }

    /**
     * 胶囊底：`photo` 页按 §3.3 用 `canvas` **实底**（`#1d1d1f`）；
     * 顶栏计数胶囊按 `V-27` 用 `surface-container`（`#eeeeee`）。
     * 不再有 `canvas/60` 这类 alpha 档。
     */
    public static int pillBackground(boolean onCanvas) {
        return onCanvas ? CANVAS : SURFACE_CONTAINER;
    }

    /**
     * 胶囊前景：`canvas` 实底上只能用白（16.83:1）；light 底上用 on-surface（14.75:1）。
     * 改名后若这里回退成 {@link #INK}，深底上的字会掉到 1.12:1（PR-2 的重点回归项）。
     */
    public static int pillForeground(boolean onCanvas) {
        return onCanvas ? SURFACE_WHITE : ON_SURFACE;
    }

    /** WCAG 2.1 对比度；参数必须是不含 alpha 的 ARGB（带 alpha 的先 {@link #compositeOver}）。 */
    public static double contrastRatio(int fgArgb, int bgArgb) {
        double a = relativeLuminance(fgArgb);
        double b = relativeLuminance(bgArgb);
        double hi = Math.max(a, b);
        double lo = Math.min(a, b);
        return (hi + 0.05) / (lo + 0.05);
    }

    /** WCAG 2.1 相对亮度（忽略 alpha 通道）。 */
    public static double relativeLuminance(int argb) {
        double r = channel((argb >> 16) & 0xFF);
        double g = channel((argb >> 8) & 0xFF);
        double b = channel(argb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    /** 把带 alpha 的前景合成到不透明底上，返回不透明 ARGB（用于校验 alpha 面）。 */
    public static int compositeOver(int fgArgb, int bgArgb) {
        double alpha = ((fgArgb >> 24) & 0xFF) / 255.0;
        int r = mix((fgArgb >> 16) & 0xFF, (bgArgb >> 16) & 0xFF, alpha);
        int g = mix((fgArgb >> 8) & 0xFF, (bgArgb >> 8) & 0xFF, alpha);
        int b = mix(fgArgb & 0xFF, bgArgb & 0xFF, alpha);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int mix(int fg, int bg, double alpha) {
        return (int) Math.round(fg * alpha + bg * (1 - alpha));
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.03928 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
