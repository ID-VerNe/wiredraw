package com.rea.mtpimport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * `Modifier.clickable` 在纯 Compose（不引 Material）时没有 `LocalIndication` 可拿，
 * 这里显式传 `indication = null` + 自己的 `MutableInteractionSource`，行为与平台点击一致。
 */
@Composable
fun Modifier.tappable(enabled: Boolean = true, onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    return this.clickable(
        enabled = enabled,
        interactionSource = source,
        indication = null,
        onClick = onClick
    )
}

/** 主 CTA：实心墨底 + 白字 + 48 dp 高 + 全圆角（L-06 / §2.1）。 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(UiColors.ink)
            .tappable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text,
            style = TextLabel.copy(color = UiColors.canvas, textAlign = TextAlign.Center)
        )
    }
}

/** 次按钮：只有文字，热区同样 ≥48 dp（A-01）。 */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .tappable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextLabel.copy(color = UiColors.ink))
    }
}

/** 图标按钮：48 dp 热区 + `contentDescription`（A-01 / A-03）。 */
@Composable
fun IconAction(
    desc: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier
            .size(48.dp)
            .tappable(onClick = onClick)
            .semantics { contentDescription = desc },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

/**
 * 进度环：外径 180 dp、描边 3.5 dp（V-22），环内只放一个主数字。
 *
 * 语义按 A-04：`progressBarRangeInfo` + `stateDescription`（读作 `12 of 36`），
 * 不是把环整体对读屏隐藏（定稿原型那样做是错的，规范明令不得照抄）。
 */
@Composable
fun ProgressRing(
    progress: Float,
    stateText: String?,
    modifier: Modifier = Modifier,
    diameter: Dp = 180.dp,
    stroke: Dp = 3.5.dp,
    // 参数名不能叫 `center`：Canvas 的 DrawScope 自带 `center` 属性，
    // 同名参数会把画布圆心遮住，`drawCircle` 就画不出来（编译期已报过）。
    inner: @Composable BoxScope.() -> Unit
) {
    val clamped = progress.coerceIn(0f, 1f)
    Box(
        modifier
            .size(diameter)
            .semantics {
                progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
                if (stateText != null) stateDescription = stateText
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val r = (size.minDimension - s) / 2f
            // DrawScope.center 是画布的圆心，不用自己再算一次。
            drawCircle(UiColors.outlineSoft, radius = r, center = center, style = Stroke(s))
            if (clamped > 0f) {
                drawArc(
                    color = UiColors.ink,
                    startAngle = -90f,
                    sweepAngle = 360f * clamped,
                    useCenter = false,
                    topLeft = Offset(center.x - r, center.y - r),
                    size = Size(r * 2f, r * 2f),
                    style = Stroke(s, cap = StrokeCap.Round)
                )
            }
        }
        inner()
    }
}

/** 抽屉遮罩：点空白处关闭；不推挤内容（L-05 / L-09）。 */
@Composable
fun Scrim(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(UiColors.scrim)
            .tappable(onClick = onDismiss)
    )
}

/** 小圆角容器（徽标、照片页胶囊共用）。 */
@Composable
fun Pill(text: String, modifier: Modifier = Modifier, onDark: Boolean = false) {
    val bg = if (onDark) UiColors.photoCapsule else UiColors.container
    val fg = if (onDark) UiColors.canvas else UiColors.ink
    Box(
        modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextSmall.copy(color = fg))
    }
}

/** 未选格的常态描边（装饰性，不承载文字，不受 V-07 约束）。 */
@Composable
fun softOutline(selected: Boolean): Modifier =
    if (selected) {
        Modifier.border(2.dp, UiColors.ink, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(1.dp, UiColors.outlineSoft, RoundedCornerShape(8.dp))
    }
