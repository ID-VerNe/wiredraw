package com.rea.mtpimport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.ColorTokens
import com.rea.mtpimport.Dims

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

/**
 * 主 CTA：实心墨底 + 白字 + 48 dp 高 + 全圆角（L-06 / §2.1）。
 *
 * `enabled=false` 只用于渐进态（issue #60：扫描线程还在后台补齐，主 CTA 与扫描不能
 * 并行发 MTP 命令）。按 V-10 同时改视觉并置 `enabled=false`，不得只靠点击无反应。
 */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            // P-09：空间不足时 CTA 禁用，视觉退到浅描边底 + 次级字色，仍保持 48 dp 热区形状。
            .background(if (enabled) UiColors.ink else UiColors.outlineSoft)
            .tappable(enabled = enabled, onClick = onClick)
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
            }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        // V-03：主按钮与正文同级（14 sp，`body-md`），不用 `label-md`（PR #41 第四轮评审 B2）。
        BasicText(
            text,
            style = TextBody.copy(
                color = if (enabled) UiColors.surfaceWhite else UiColors.secondary,
                textAlign = TextAlign.Center
            )
        )
    }
}

/**
 * 次按钮：文字 + 1 dp 描边（§3.4「线性图标（无文字）或 1 px 描边」），热区同样 ≥48 dp（A-01）。
 * 描边取 `outline`（#747878 on #FFFFFF ≈ 4.47:1），满足图形 ≥3:1。
 */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 20.dp,
    icon: (@Composable () -> Unit)? = null
) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .border(1.dp, UiColors.outline, RoundedCornerShape(24.dp))
            .tappable(onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = horizontalPadding, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                icon()
                Spacer(Modifier.width(8.dp))
            }
            // V-01 / V-03：次按钮与正文同级（14 sp）。
            BasicText(text, style = TextBody.copy(color = UiColors.onSurface))
        }
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
 * 语义按 A-04：只有**传输环**（`stateText != null`，即 `IMPORTING` / `PAUSED`）带
 * `progressBarRangeInfo` + `stateDescription`（读作 `12 of 36`）。
 * `SCANNING` 的细环只呈现计数文案，**不另加进度语义**（A-04 明写两环分属不同状态）。
 * 不是把环整体对读屏隐藏（定稿原型那样做是错的，规范明令不得照抄）。
 */
@Composable
fun ProgressRing(
    progress: Float,
    stateText: String?,
    modifier: Modifier = Modifier,
    diameter: Dp = Dims.TRANSFER_RING_DIAMETER_DP.dp,
    stroke: Dp = Dims.TRANSFER_RING_STROKE_DP.dp,
    // 参数名不能叫 `center`：Canvas 的 DrawScope 自带 `center` 属性，
    // 同名参数会把画布圆心遮住，`drawCircle` 就画不出来（编译期已报过）。
    inner: @Composable BoxScope.() -> Unit
) {
    val clamped = progress.coerceIn(0f, 1f)
    val ringModifier = if (stateText != null) {
        modifier.semantics {
            progressBarRangeInfo = ProgressBarRangeInfo(clamped, 0f..1f)
            stateDescription = stateText
        }
    } else {
        modifier
    }
    Box(
        ringModifier.size(diameter),
        contentAlignment = Alignment.Center
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val r = (size.minDimension - s) / 2f
            // DrawScope.center 是画布的圆心，不用自己再算一次。
            // 环轨道用 outline（白底 4.47:1 ≥ 3:1，P1-3 / V-07）；outlineSoft（1.7:1）不得承载图标/文字，
            // 只作分隔细线与禁用底色（照片格边框、禁用 CTA 底色）；扫描细线底按 #265 也用 outline。
            drawCircle(UiColors.outline, radius = r, center = center, style = Stroke(s))
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
            // #374 / IA-12：遮罩是「点空白处关闭」的触摸面，不该进无障碍树。
            // 这里刻意不用 `tappable`（= `clickable`）：`clickable` 会把「无名的可点节点」写进语义，
            // dump 里就是 `NAF=true / clickable=true / 无 text 无 desc` 的全屏节点——读屏打开浮层后
            // 第一个焦点落在它上面，念不出任何东西。`clearAndSetSemantics {}` 修不掉：清理只作用于
            // 后代节点，同一布局节点上先合并进来的 `clickable` 动作仍在。
            // 关闭仍有两条可达路径：抽屉标题行的 `Close`（A-03）与系统返回键（§1.3）。
            .pointerInput(Unit) { detectTapGestures { onDismiss() } }
    )
}

/**
 * 抽屉骨架（L-05 / L-09）：遮罩 + 贴底白底面板 + 系统栏内边距 + 最大高度 + 滚动容器 + 标题行。
 *
 * 详情与过滤两个抽屉共用这一份形状（F-04 / issue #217）：差异只允许出现在 [title] 与 [content]。
 * 最大高度取 `Dims.DRAWER_MAX_HEIGHT_DP`，与 G7a 的具名同源。
 */
@Composable
fun DrawerSkeleton(
    title: String,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        Scrim(onClose)
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(UiColors.surfaceWhite)
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = Dims.DRAWER_MAX_HEIGHT_DP.dp)
                .verticalScroll(rememberScrollState())
                // #374 / IA-12：抽屉是模态浮层（L-05）。`paneTitle` 让读屏在进入时先报
                // 「Location details / Filter」，`dismiss` 给「下滑关闭」一个可派发的动作；
                // 系统返回键仍是另一条路径（§1.3，由 `BackNav` 决定）。
                .semantics {
                    paneTitle = title
                    dismiss {
                        onClose()
                        true
                    }
                }
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText(title, style = TextBody)
                Spacer(Modifier.weight(1f))
                IconAction(desc = "Close", onClick = onClose) {
                    CloseGlyph(16.dp, UiColors.outline)
                }
            }
            content()
        }
    }
}

/**
 * 小圆角胶囊：`photo` 页的 `ARW · 24.6 MB` 与 `CATALOG_READY` 顶栏计数胶囊（`V-27`），
 * 统一 `mono-data` 12 sp、等宽数字。顶栏只在 `CATALOG_READY` 复用本组件，其它状态不出现数字。
 *
 * 底色/前景走 `ColorTokens` 的单一事实源（参数名与 `ColorTokens.pillBackground(onCanvas)` 同名，F-16）：
 * - `onCanvas = true`（`photo` 页）：`canvas` **实底** + `#ffffff` 字 = 16.83:1（§3.3:525，
 *   不再用白色 12% alpha，也不再有 `canvas/60` 档）；
 * - `onCanvas = false`（顶栏）：`surface-container #eeeeee` + `on-surface #1a1c1c` = 14.75:1（`V-27`）。
 */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    onCanvas: Boolean = false
) {
    val bg = Color(ColorTokens.pillBackground(onCanvas))
    val fg = Color(ColorTokens.pillForeground(onCanvas))
    Box(
        modifier
            .clip(CircleShape)
            .background(bg)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        BasicText(text, style = TextMonoData.copy(color = fg))
    }
}

/** 已选格描边：V-11 的「1 px 深色描边」，颜色取 on-surface（`ink` 只作主 CTA 底）。 */
@Composable
fun softOutline(selected: Boolean): Modifier =
    if (selected) {
        Modifier.border(1.dp, UiColors.onSurface, RoundedCornerShape(8.dp))
    } else {
        Modifier.border(1.dp, UiColors.outlineSoft, RoundedCornerShape(8.dp))
    }
