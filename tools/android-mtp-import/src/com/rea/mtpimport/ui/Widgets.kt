package com.rea.mtpimport.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.ColorTokens
import com.rea.mtpimport.Dims
import com.rea.mtpimport.Motion

/**
 * 全应用**唯一**的点击入口（#374 / IA-1 / `V-34`）。
 *
 * `Modifier.clickable` 在纯 Compose（不引 Material）时没有 `LocalIndication` 可拿，
 * 所以 `indication = null` 保留；但 `MutableInteractionSource` 不再「建好就扔」：
 * 它同时喂给 [pressFeedback]（`collectIsPressedAsState`），按下当帧就有形态响应，
 * 而不是等 `onClick` 回调把界面改掉才算有反馈。
 *
 * [wash] 是按下时叠加的底色：浅底默认压深（`onSurface`），实心墨底的主 CTA 传
 * `surfaceWhite` 提亮 —— 同一个 10% alpha 在两种底上都看得见。传 `null` 关掉反馈，
 * 只给「全屏触摸吸收层」用（`PhotoScreen` 的黑底），否则整屏会跟着闪。
 */
@Composable
fun Modifier.tappable(
    enabled: Boolean = true,
    wash: Color? = UiColors.onSurface,
    onClick: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val press = if (wash != null) pressFeedback(source, enabled, wash) else Modifier
    return this
        .then(press)
        .clickable(
            enabled = enabled,
            interactionSource = source,
            indication = null,
            onClick = onClick
        )
}

/**
 * 全应用**唯一**的「单击 + 长按」手势入口（#374 / IA-2 / `V-35`）。
 *
 * 三条 ≥500 ms 的确定性动作（组选、进 `photo`、进 `Details`）共用这一份触觉：
 * 以前组头长按有震动、格子长按没有，同一块屏幕两种手感。
 *
 * `pointerInput` 不自带 `InteractionSource`，必须自己把 `PressInteraction` emit 进同一个
 * source —— 否则按压视觉只在 `clickable` 的目标上有，`detectTapGestures` 的目标全裸。
 * [key] 与旧代码同口径：按稳定身份（如 `group.key` / `unit.key`）重启检测器，
 * 不用每次重组都新建的对象；闭包一律经 `rememberUpdatedState` 读最新值（#374 / IA-13）。
 */
@Composable
fun Modifier.tapOrHold(
    key: Any?,
    onTap: () -> Unit,
    onLongPress: () -> Unit
): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val currentTap = rememberUpdatedState(onTap)
    val currentLong = rememberUpdatedState(onLongPress)
    return this
        .pressFeedback(source, enabled = true, wash = UiColors.onSurface)
        .pointerInput(key) {
            detectTapGestures(
                onPress = { offset -> pressGesture(source, offset) },
                onTap = { currentTap.value() },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentLong.value()
                }
            )
        }
}

/** 只吃长按、不吃点击的入口（顶栏 `Details` / `L-05`）；触觉与 [tapOrHold] 同源。 */
@Composable
fun Modifier.longPressOnly(key: Any?, onLongPress: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val haptic = LocalHapticFeedback.current
    val currentLong = rememberUpdatedState(onLongPress)
    return this
        .pressFeedback(source, enabled = true, wash = UiColors.onSurface)
        .pointerInput(key) {
            detectTapGestures(
                onPress = { offset -> pressGesture(source, offset) },
                onLongPress = {
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    currentLong.value()
                }
            )
        }
}

/**
 * 把一次按下的生命周期写进 [source]：`Press` → `Release` / `Cancel`。
 * `detectTapGestures` 一旦给了 `onPress` 就不再自己 `tryAwaitRelease()`，这里必须补上，
 * 否则按压态不会复位（一直压着），且滚动越界取消时不会清掉。
 */
private suspend fun androidx.compose.foundation.gestures.PressGestureScope.pressGesture(
    source: MutableInteractionSource,
    offset: Offset
) {
    val press = PressInteraction.Press(offset)
    source.emit(press)
    val released = tryAwaitRelease()
    source.emit(if (released) PressInteraction.Release(press) else PressInteraction.Cancel(press))
}

/**
 * 按压态的视觉（`V-34`）：在**已有底色之上**叠一层 wash（浅底压深 / 深底提亮）。
 *
 * 为什么不是「缩放到 0.97」：`graphicsLayer` 排在 `.background()` 之后时只变换其后绘制的
 * 内容，实底按钮的形状压根不缩（真机扫描线：按下 600 ms 内墨色区间恒为 40..1039），
 * 结果是「只缩文字、不缩按钮」的假反馈。底色响应对深浅底都可见，且按下当帧就能到位。
 *
 * **按下用 `SnapSpec`、松手才走 90 ms 回落**：弹簧方案实测 300 ms 时底色才刚起步，
 * 违反「`pointerdown` 当帧给出响应」。动画值只在 `drawBehind {}` 的 draw phase 读，
 * 不会让所在 composable 重组，更不会把整面照片墙拖下水（#374 IA-6 刚压下来的成本）。
 */
@Composable
private fun Modifier.pressFeedback(
    source: MutableInteractionSource,
    enabled: Boolean,
    wash: Color
): Modifier {
    val pressed by source.collectIsPressedAsState()
    val active = pressed && enabled
    val alpha by animateFloatAsState(
        targetValue = if (active) Motion.PRESS_WASH_ALPHA else 0f,
        animationSpec = if (active) MotionSpecs.immediate<Float>() else MotionSpecs.press<Float>(),
        label = "pressWash"
    )
    return this.drawBehind {
        if (alpha > 0f) drawRect(color = wash, alpha = alpha)
    }
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
            .tappable(enabled = enabled, wash = UiColors.surfaceWhite, onClick = onClick)
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
    // 语义值取逻辑进度（读屏不该读到动画中间值）；画出来的弧走插值（`V-36`）。
    // 目标必须过 Motion.ringTarget：done < total 时钳在 99%（#282 / U-45），
    // 且用无过冲的 tween 而不是 spring，动画中途也不会把「未完成」画成满环。
    val clamped = progress.coerceIn(0f, 1f)
    val sweep by animateFloatAsState(
        targetValue = Motion.ringTarget(progress),
        animationSpec = MotionSpecs.state<Float>(),
        label = "ringSweep"
    )
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
            if (sweep > 0f) {
                drawArc(
                    color = UiColors.ink,
                    startAngle = -90f,
                    sweepAngle = 360f * sweep,
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

/**
 * 浮层入场/退场的统一宿主（#374 / IA-3 / `V-36` / `V-37`）。
 *
 * `AnimatedVisibility` 不能挂在页面的裸 `if` 上：节点一帧就被移除，退出动画根本没机会播。
 * 所以三个浮层的骨架**常驻组合**，由 [visible] 驱动两层动画；`visible=false` 时
 * `AnimatedVisibility` 不再组合子节点，常驻的只是两个空壳布局，没有语义节点、也不吃触摸。
 *
 * 遮罩只淡不位移（跟着面板滑会在屏幕边缘露白边）；面板的位移/缩放由调用点给。
 * 两层共用同一份 spec，且都走 [MotionSpecs]：减少动效时一起退化成 `snap()`，
 * 终态的 dump 与开启动效时逐节点一致。
 *
 * 动画期间输入不锁（`V-37`）：遮罩的 `pointerInput` 一直挂着，退出途中再次打开只需
 * 把 [visible] 翻回 true，`AnimatedVisibility` 会把进行中的动画重定向到新目标。
 */
@Composable
internal fun OverlayHost(
    visible: Boolean,
    onDismiss: () -> Unit,
    alignment: Alignment,
    panelEnter: EnterTransition,
    panelExit: ExitTransition,
    panel: @Composable () -> Unit
) {
    Box(Modifier.fillMaxSize()) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(MotionSpecs.overlay<Float>()),
            exit = fadeOut(MotionSpecs.overlay<Float>()),
            modifier = Modifier.fillMaxSize()
        ) {
            Scrim(onDismiss)
        }
        AnimatedVisibility(
            visible = visible,
            enter = panelEnter,
            exit = panelExit,
            modifier = Modifier.align(alignment)
        ) {
            panel()
        }
    }
}

/** 抽屉遮罩：点空白处关闭；不推挤内容（L-05 / L-09）。 */
@Composable
fun Scrim(onDismiss: () -> Unit) {
    // `pointerInput(Unit)` 不重启，直接捕获 lambda 会冻结在首次组合那一版；
    // 现在三个调用点闭包的都是稳定对象（`model`），但这是与组头同一类的坑（#374 评审），
    // 统一用 `rememberUpdatedState` 兜住，别等换一个依赖可变状态的 lambda 才炸。
    val dismiss = rememberUpdatedState(onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(UiColors.scrim)
            // #374 / IA-12：遮罩是「点空白处关闭」的触摸面，不该进无障碍树。
            // 这里刻意不用 `tappable`（= `clickable`）：`clickable` 会把「无名的可点节点」写进语义，
            // dump 里就是 `NAF=true / clickable=true / 无 text 无 desc` 的全屏节点——读屏打开浮层后
            // 第一个焦点落在它上面，念不出任何东西。`clearAndSetSemantics {}` 修不掉：清理只作用于
            // 后代节点，同一布局节点上先合并进来的 `clickable` 动作仍在。
            // 关闭的替代路径按调用点不同：两个抽屉有标题行的 `Close`（A-03）；
            // `Pause & exit` 确认框只有两个按钮，另有系统返回键（`BackNav` / §1.3）兜底。
            .pointerInput(Unit) { detectTapGestures { dismiss.value() } }
    )
}

/**
 * 抽屉骨架（L-05 / L-09）：遮罩 + 贴底白底面板 + 系统栏内边距 + 最大高度 + 滚动容器 + 标题行。
 *
 * 详情与过滤两个抽屉共用这一份形状（F-04 / issue #217）：差异只允许出现在 [title] 与 [content]。
 * 最大高度取 `Dims.DRAWER_MAX_HEIGHT_DP`，与 G7a 的具名同源。
 *
 * [visible] 由页面按 `model.detailsOpen` / `model.filterOpen` 传进来：抽屉骨架必须常驻组合，
 * 否则关闭时裸 `if` 会把整棵子树一帧移除，滑出动画无从播起（`V-36`）。
 */
@Composable
fun DrawerSkeleton(
    title: String,
    visible: Boolean,
    onClose: () -> Unit,
    content: @Composable ColumnScope.() -> Unit
) {
    OverlayHost(
        visible = visible,
        onDismiss = onClose,
        alignment = Alignment.BottomCenter,
        panelEnter = slideInVertically(MotionSpecs.overlay<IntOffset>()) { it } +
            fadeIn(MotionSpecs.overlay<Float>()),
        panelExit = slideOutVertically(MotionSpecs.overlay<IntOffset>()) { it } +
            fadeOut(MotionSpecs.overlay<Float>())
    ) {
        Column(
            Modifier
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

/**
 * 已选格描边：`V-11` 的「1 px 深色描边」终态不变（0 → `outlineSoft`、1 → `on-surface`），
 * 但颜色随选中进度插值（`V-36`）。`ink` 只作主 CTA 底，这里不碰。
 */
@Composable
fun softOutline(progress: Float): Modifier =
    Modifier.border(1.dp, lerp(UiColors.outlineSoft, UiColors.onSurface, progress), RoundedCornerShape(8.dp))
