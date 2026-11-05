package com.rea.mtpimport.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 视觉 token 的唯一出处（UI-SPEC §3 的 47 个 token 里界面实际用到的子集）。
 *
 * 只做 light：定稿 §0.5 明确本轮不出 dark，V-08 / A-07 标「暂不适用」。
 * 正文色 `secondary` 在白底上的对比度约 9.8:1；`outline`（#747878 on #FFFFFF）实测约 4.47:1，
 * 略低于正文的 4.5:1，所以它**只用于图标**（图标按 A-02 取 ≥3:1 即可，不违规）。
 */
object UiColors {
    /** canvas / surface：界面底色。 */
    val canvas = Color(0xFFFFFFFF)

    /** 主文字与已选描边。 */
    val ink = Color(0xFF111111)

    /** 正文与组头（≥4.5:1）。 */
    val secondary = Color(0xFF444748)

    /** 图标与次级描边（#747878 on #FFFFFF ≈ 4.47:1，只用于图标，按 A-02 的 ≥3:1 判）。 */
    val outline = Color(0xFF747878)

    /** 未选格的常态描边（装饰性，不承载文字，不受 V-07 约束）。 */
    val outlineSoft = Color(0xFFC4C7C7)

    /** 无缩略图时的同尺寸灰块（V-19）。 */
    val container = Color(0xFFEEEEEE)

    /** 照片页的降级块底色。 */
    val containerLow = Color(0xFFF3F3F4)

    /** `ERROR` 的错误色。 */
    val error = Color(0xFFBA1A1A)

    /** 抽屉遮罩。 */
    val scrim = Color(0x66000000)

    /** 照片页画布与胶囊。 */
    val photoCanvas = Color(0xFF000000)
    val photoCapsule = Color(0x1FFFFFFF)
}

/**
 * 字形：仓库里没有 Geist 字体文件，Compose 统一用系统 sans-serif；
 * 数字走等宽 `FontFeatureSettings`，保证进度/计数不跳字（§3.1 的「单一字形」）。
 */
private val Sans = FontFamily.SansSerif

val TextBody = TextStyle(fontFamily = Sans, fontSize = 14.sp, lineHeight = 20.sp, color = UiColors.ink)
val TextSmall = TextStyle(fontFamily = Sans, fontSize = 12.sp, lineHeight = 16.sp, color = UiColors.secondary)
val TextLabel = TextStyle(fontFamily = Sans, fontSize = 13.sp, lineHeight = 16.sp, color = UiColors.ink)
val TextDisplay = TextStyle(
    fontFamily = Sans,
    fontSize = 24.sp,
    lineHeight = 28.sp,
    fontWeight = FontWeight.SemiBold,
    color = UiColors.ink,
    // display-sm 用在环内百分比与 DONE 的 `36 · 871 MB`，等宽数字避免进度跳动。
    fontFeatureSettings = "tnum"
)

val TextMonoData = TextStyle(
    // §3.1：字体族必须统一（含 `mono-data`），不得再引入第二套字形。
    // 数字对齐靠 `tabular-nums`（`tnum`），不靠等宽字体（PR #41 第四轮评审 B2）。
    fontFamily = Sans,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    color = UiColors.secondary,
    fontFeatureSettings = "tnum"
)
