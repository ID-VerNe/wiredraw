package com.rea.mtpimport.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.Motion
import com.rea.mtpimport.UiState
import com.rea.mtpimport.UiText

/** 三屏分流的稳定 key：`Crossfade` 只按这个枚举换层，状态值本身不作 key。 */
private enum class Screen { TRANSFER, LIBRARY, STATUS }

private fun screenOf(state: UiState.State): Screen = when {
    UiState.isTransfer(state) -> Screen.TRANSFER
    UiState.showsLibrary(state) -> Screen.LIBRARY
    else -> Screen.STATUS
}

/**
 * 根 composable：按 `UiState` 三屏分流与覆盖层渲染。
 *
 * - `IMPORTING` / `PAUSED` → 传输屏；`CATALOG_READY` → 照片墙；其余 → 状态屏。
 * - `photo` 页（PhotoScreen）作为全屏覆盖层叠加在照片墙之上（issue #369），
 *   使底层照片墙保持活跃挂载与滚动偏移量，返回时零重组延迟。
 * - 详情抽屉、过滤抽屉与退出确认框均为覆盖层，不改变底层的页面结构（L-05 / L-09）。
 *
 * `V-36`：三屏与 `photo` 覆盖层都走 120 ms 交叉淡入，不做位移 —— 位移会推迟 `P-01`
 * 的「输入当帧立即出现反馈」。三个浮层的骨架常驻组合，由各自的 `visible` 驱动
 * [OverlayHost] 的入场/退场；裸 `if` 一帧移除子树，退出动画根本没机会播。
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun ImportApp(model: AppModel) {
    val state = model.state

    // `testTagsAsResourceId = true` 把 `Modifier.testTag(...)` 暴露成 dump 里的 resource-id：
    // 顶栏容器的固定标签 `topBar` 靠它才能被 uiautomator 定位并量高度（U-01 / U-02 / §2）。
    Box(
        Modifier
            .fillMaxSize()
            .semantics { testTagsAsResourceId = true }
            .background(UiColors.surfaceWhite)
    ) {
        // 浮层打开时，底下这一层必须同时对**读屏**不可见（#374 / IA-12）。
        // 三个浮层都是同一窗口里的 `Box` 兄弟节点（L-05：覆盖内容区、不推挤），没有 `Dialog`
        // 那样的独立 window，所以「模态」只能是显式声明：不加这一条，读屏会把焦点走到被遮住的
        // 照片墙或主 CTA 上，用户在浮层里滑动两下就跑到背后去了。
        //
        // 必须用 `clearAndSetSemantics`，不能只写 `semantics { invisibleToUser() }`（评审第 3 轮拦下）：
        // `invisibleToUser` 只描述**这一个节点**，KDoc 明确「要隐藏后代语义请用 `clearAndSetSemantics`」；
        // 而 `clearAndSetSemantics` 会清空后代语义，底层才真的不产出任何可聚焦节点。
        // 这一条以前是被遮罩「顺带」担保的：全屏 `clickable` 遮罩本身是一个语义节点，把未覆盖区域占满，
        // 底层子树根本没进 `getCurrentSemanticsNodes()`。遮罩改成零语义的 `pointerInput`（同 PR）之后，
        // 那个副作用消失，屏障就必须自己成立。
        val overlayOpen = model.detailsOpen || model.filterOpen || model.exitConfirmOpen || model.openedPhoto != null
        Box(
            Modifier
                .fillMaxSize()
                .then(
                    if (overlayOpen) Modifier.clearAndSetSemantics { invisibleToUser() } else Modifier
                )
        ) {
            Crossfade(
                targetState = screenOf(state),
                animationSpec = MotionSpecs.state(),
                label = "screen"
            ) { screen ->
                // 退场层冻结切屏那一刻的状态：否则 120 ms 里旧状态屏会按新 state 重画，
                // 状态页 → 照片墙时闪一帧「无相机」兜底屏（`V-36`）。
                val frozen = remember(screen) { state }
                when (screen) {
                    Screen.TRANSFER -> TransferScreen(model)
                    Screen.LIBRARY -> LibraryScreen(model)
                    Screen.STATUS -> StatusScreen(model, frozen)
                }
            }
        }

        // `photo` 覆盖层的 targetState 是**单元本身**：退出淡出期间 Crossfade 把旧的
        // 非空 unit 继续交给旧 content，退场层不会因 model.openedPhoto 变 null 而空屏。
        Crossfade(
            targetState = model.openedPhoto,
            animationSpec = MotionSpecs.state(),
            label = "photo"
        ) { unit ->
            if (unit != null) PhotoScreen(model, unit)
        }

        // 三个浮层常驻组合：可见性交给 DrawerSkeleton / ExitConfirmDialog 里的 OverlayHost。
        // `visible=false` 时 AnimatedVisibility 不再组合子节点，常驻的只是两个空壳布局。
        DetailsDrawer(model)
        FilterDrawer(model)
        ExitConfirmDialog(model, visible = model.exitConfirmOpen)
    }

    StateAnnouncer(model)
}

/**
 * 状态变化播报（`UI-SPEC` §7 `A-08` / #374 IA-10）。
 *
 * 三件事必须播报：**扫描完成 / 导入完成 / 出错**。只改静态文本不算——读屏用户听到的会是
 * 「页面内容变了」，而不是「扫描完成，512 张」。
 *
 * 为什么用 `announceForAccessibility` 而不是 `liveRegion`：三屏整个子树是**换掉**的，
 * `liveRegion` 只在同一节点的文字变化时可靠；新子树里一条新节点不会被当成「变化」。
 * 显式播报与视图替换解耦，也不依赖 TalkBack 的节点比对窗口。
 *
 * 首帧不播报：`demo_state` 可以直接把界面装进 `CATALOG_READY` / `DONE`，那不是「刚刚发生的变化」，
 * 开机就念一句「扫描完成」纯属噪声。所以第一帧只记住状态，不发事件。
 */
@Composable
private fun StateAnnouncer(model: AppModel) {
    val view = LocalView.current
    var last by remember { mutableStateOf<UiState.State?>(null) }
    val state = model.state
    LaunchedEffect(state) {
        val prev = last
        last = state
        if (prev == null) return@LaunchedEffect
        val text = when (state) {
            UiState.State.CATALOG_READY -> UiText.announceScanDone(model.wall?.photoCount() ?: 0)
            UiState.State.DONE -> UiText.announceDone(model.donePhotos)
            UiState.State.ERROR -> model.errorReason
            else -> null
        }
        if (!text.isNullOrBlank()) view.announceForAccessibility(text)
    }
}

/**
 * `IMPORTING` / `PAUSED` 下按系统返回键的拦截框（§1.3 / §4.5 `T-EXIT`）。
 *
 * 定稿给的是「提问 + 两个选项」，不是一个不知在问什么的双按钮框（#374 IA-8 / `V-33`）：
 * 标题必须问出来（`Pause import?`），两个选项**等宽**，实心项是**保留导入**的那一个
 * （`Keep going`），`Pause & exit` 退到描边——破坏性选项不做默认焦点，也不占唯一的主按钮额度（`V-09`）。
 *
 * `Resume` 在这里是错的词：`W-06` 的 `Resume` 专指「把已暂停的轮次继续下去」，
 * 而这个框里还没有暂停，动作只是「关掉框」。两个意思用同一个词，用户会以为点下去会恢复什么。
 *
 * `V-36`：与抽屉共用 [OverlayHost] —— 遮罩只淡入、面板从 `0.96` 缩入，关闭时对称退出；
 * 不引 Material 的 `AlertDialog`，与抽屉共用一套遮罩和按钮组件。
 */
@Composable
private fun ExitConfirmDialog(model: AppModel, visible: Boolean) {
    OverlayHost(
        visible = visible,
        onDismiss = { model.setExitConfirm(false) },
        alignment = Alignment.Center,
        panelEnter = fadeIn(MotionSpecs.state()) +
            scaleIn(MotionSpecs.state(), initialScale = Motion.DIALOG_FROM_SCALE),
        panelExit = fadeOut(MotionSpecs.state()) +
            scaleOut(MotionSpecs.state(), targetScale = Motion.DIALOG_FROM_SCALE)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(UiColors.surfaceWhite)
                // 读屏进框先报提问，否则焦点落在两个按钮上仍然不知道在问什么。
                .semantics { paneTitle = UiText.exitConfirmTitle() }
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            BasicText(
                UiText.exitConfirmTitle(),
                style = TextBody.copy(color = UiColors.onSurface, textAlign = TextAlign.Center),
                modifier = Modifier.padding(bottom = 16.dp)
            )
            PrimaryButton(
                UiText.keepGoing(),
                onClick = { model.callbacks?.onResumeExit() },
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            SecondaryButton(
                UiText.pauseExit(),
                onClick = { model.callbacks?.onPauseExit() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
