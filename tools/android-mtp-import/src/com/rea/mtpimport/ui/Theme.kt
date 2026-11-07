package com.rea.mtpimport.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.rea.mtpimport.ColorTokens
import com.rea.mtpimport.TypeTokens

/**
 * 视觉 token 的唯一出处（UI-SPEC §3 的 47 个 token 里界面实际用到的子集）。
 *
 * 数值不在这里手抄：统一取自纯 JDK 的 [ColorTokens]（issue #78），
 * 这样 `SelfTest` 的对比度断言与 Compose 实际取值是同一份数据。
 *
 * 只做 light：定稿 §0.5 明确本轮不出 dark，V-08 / A-07 标「暂不适用」。
 *
 * 颜色角色（UI-SPEC §3.3:488，违反即评审阻断）：
 * - [surfaceWhite] `#ffffff`：页面底；深色面（[canvas]）上的文字与图标只能用它。
 * - [ink] `#111111`：**只**作主 CTA 实心底；正文与图标不得用。
 * - [onSurface] `#1a1c1c`：正文与图标（on-surface）。
 * - [canvas] `#1d1d1f`：`photo` 全幅底 / 遮罩 / `photo` 胶囊实底 / 系统栏浅色图标底。
 *
 * 2026-11-06 按当前 token 实算（WCAG 2.1，`SelfTest.colorTokensCases` 已断言）：
 *
 * | 前景 | 背景 | 对比度 |
 * |---|---|---|
 * | `#ffffff` | `ink #111111` | 18.88:1 |
 * | `#ffffff` | `canvas #1d1d1f` | 16.83:1 |
 * | `on-surface #1a1c1c` | `#ffffff` | 17.12:1 |
 * | `on-surface #1a1c1c` | `surface-container #eeeeee` | 14.75:1 |
 * | `on-surface-variant #444748` | `#ffffff` | 9.37:1（旧注释写的 9.8 是错的） |
 * | `outline #747878` | `#ffffff` | 4.47:1（只用于图标，按 A-02 ≥3:1 判） |
 * | `ink #111111` | `canvas #1d1d1f` | 1.12:1（**禁用**，深色面只能用白） |
 *
 * alpha 面也按当前 token 重算：抽屉遮罩（40% canvas 压白）合成 `#a5a5a5`，只是装饰、不承载正文；
 * 未选格灰块（60% surface-container 压白）合成 `#f5f5f5`，其上的 `secondary` 文字仍有 8.6:1。
 */
object UiColors {
    /** 页面底（library / transfer / 抽屉 / 对话框）。规范名 `surface-container-lowest`。 */
    val surfaceWhite = Color(ColorTokens.SURFACE_WHITE)

    /** 主 CTA 实心底；**只**此一处，正文与图标不得用。 */
    val ink = Color(ColorTokens.INK)

    /** 正文/顶栏文字与图标（on-surface，白底 17.12:1）。 */
    val onSurface = Color(ColorTokens.ON_SURFACE)

    /** 正文与组头（`on-surface-variant`，白底 9.37:1）。 */
    val secondary = Color(ColorTokens.ON_SURFACE_VARIANT)

    /** 图标与次级描边（`#747878` on `#ffffff` ≈ 4.47:1，只用于图标，按 A-02 的 ≥3:1 判）。 */
    val outline = Color(ColorTokens.OUTLINE)

    /** 未选格的常态描边（装饰性，不承载文字，不受 V-07 约束）。 */
    val outlineSoft = Color(ColorTokens.OUTLINE_SOFT)

    /** 无缩略图时的同尺寸灰块（V-19）与计数胶囊底（`V-27` 的 `surface-container`）。 */
    val container = Color(ColorTokens.SURFACE_CONTAINER)

    /**
     * 抽屉遮罩。底子与画布黑同一个色相，只是带 40% 透明（issue #44 决策：全仓不许再用纯黑）。
     */
    val scrim = Color(ColorTokens.SCRIM)

    /**
     * 画布黑（`#1d1d1f`）：`photo` 页底与深色容器。与文字近黑 [ink]（`#111111`）是两个显式
     * token，不得互换——`ink` 压在 `#1d1d1f` 上对比度只有约 1.12:1，深色面上只能用 [surfaceWhite]。
     *
     * 注意：这个 token 以前叫 `photoCanvas`，白色页面底以前叫 `canvas`（issue #78 改名），
     * 代码里看到 `canvas` 一律指画布黑，不再指白。
     */
    val canvas = Color(ColorTokens.CANVAS)
}

/**
 * 字形：仓库里没有 Geist 字体文件，Compose 统一用系统 sans-serif；
 * 数字走等宽 `FontFeatureSettings`，保证进度/计数不跳字（§3.1 的「单一字形」）。
 */
private val Sans = FontFamily.SansSerif

// 字号 / 行高 / 字重 / 字距全部取自纯 JDK 的 [TypeTokens]（issue #215 / F-11）；
// 表在 `UI-SPEC.md` 的 §3.1；`SelfTest.typeTokenCases` 逐档断言。
val TextBody = TextStyle(
    fontFamily = Sans,
    fontSize = TypeTokens.BODY_MD_SIZE_SP.sp,
    lineHeight = TypeTokens.BODY_MD_LINE_HEIGHT_SP.sp,
    fontWeight = FontWeight(TypeTokens.BODY_MD_WEIGHT),
    letterSpacing = TypeTokens.BODY_MD_TRACKING_EM.em,
    color = UiColors.onSurface
)

val TextSmall = TextStyle(
    fontFamily = Sans,
    fontSize = TypeTokens.BODY_SM_SIZE_SP.sp,
    lineHeight = TypeTokens.BODY_SM_LINE_HEIGHT_SP.sp,
    fontWeight = FontWeight(TypeTokens.BODY_SM_WEIGHT),
    letterSpacing = TypeTokens.BODY_SM_TRACKING_EM.em,
    color = UiColors.secondary
)

val TextLabel = TextStyle(
    fontFamily = Sans,
    fontSize = TypeTokens.LABEL_MD_SIZE_SP.sp,
    lineHeight = TypeTokens.LABEL_MD_LINE_HEIGHT_SP.sp,
    fontWeight = FontWeight(TypeTokens.LABEL_MD_WEIGHT),
    letterSpacing = TypeTokens.LABEL_MD_TRACKING_EM.em,
    color = UiColors.onSurface
)

val TextDisplay = TextStyle(
    fontFamily = Sans,
    fontSize = TypeTokens.DISPLAY_SM_SIZE_SP.sp,
    lineHeight = TypeTokens.DISPLAY_SM_LINE_HEIGHT_SP.sp,
    fontWeight = FontWeight(TypeTokens.DISPLAY_SM_WEIGHT),
    letterSpacing = TypeTokens.DISPLAY_SM_TRACKING_EM.em,
    color = UiColors.onSurface,
    // display-sm 用在状态主文案、环内百分比与 DONE 的 `36 · 871 MB`，等宽数字避免进度跳动。
    fontFeatureSettings = "tnum"
)

val TextMonoData = TextStyle(
    // §3.1：字体族必须统一（含 `mono-data`），不得再引入第二套字形。
    // 数字对齐靠 `tabular-nums`（`tnum`），不靠等宽字体（PR #41 第四轮评审 B2）。
    fontFamily = Sans,
    fontSize = TypeTokens.MONO_DATA_SIZE_SP.sp,
    lineHeight = TypeTokens.MONO_DATA_LINE_HEIGHT_SP.sp,
    fontWeight = FontWeight(TypeTokens.MONO_DATA_WEIGHT),
    letterSpacing = TypeTokens.MONO_DATA_TRACKING_EM.em,
    color = UiColors.secondary,
    fontFeatureSettings = "tnum"
)
