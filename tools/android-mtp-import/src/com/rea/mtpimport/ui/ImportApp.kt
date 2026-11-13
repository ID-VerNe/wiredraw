package com.rea.mtpimport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiState

/**
 * 根 composable：按 `UiState` 三屏分流与覆盖层渲染。
 *
 * - `IMPORTING` / `PAUSED` → 传输屏；`CATALOG_READY` → 照片墙；其余 → 状态屏。
 * - `photo` 页（PhotoScreen）作为全屏覆盖层叠加在照片墙之上（issue #369），
 *   使底层照片墙保持活跃挂载与滚动偏移量，返回时零重组延迟。
 * - 详情抽屉、过滤抽屉与退出确认框均为覆盖层，不改变底层的页面结构（L-05 / L-09）。
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
        if (UiState.isTransfer(state)) {
            TransferScreen(model)
        } else if (UiState.showsLibrary(state)) {
            LibraryScreen(model)
        } else {
            StatusScreen(model)
        }

        if (model.openedPhoto != null) {
            PhotoScreen(model)
        }

        if (model.detailsOpen) DetailsDrawer(model)
        if (model.filterOpen) FilterDrawer(model)
        if (model.exitConfirmOpen) ExitConfirmDialog(model)
    }
}

/**
 * `IMPORTING` / `PAUSED` 下按系统返回键的拦截框（§1.3 / §4.5 词表）。
 *
 * 两个选项的文案是定稿指定的：`Pause & exit`（暂停并退出应用，断点留在 Journal）
 * 与 `Resume`（收起框，继续留在当前状态/恢复导入）。不引 Material 的 `AlertDialog`，
 * 与抽屉共用一套遮罩和按钮组件，免得为一个框再拉一个依赖。
 */
@Composable
private fun ExitConfirmDialog(model: AppModel) {
    Box(Modifier.fillMaxSize()) {
        Scrim { model.setExitConfirm(false) }
        Column(
            Modifier
                .align(Alignment.Center)
                .fillMaxWidth()
                .padding(horizontal = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(UiColors.surfaceWhite)
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            PrimaryButton("Pause & exit", onClick = { model.callbacks?.onPauseExit() })
            Spacer(Modifier.height(8.dp))
            SecondaryButton("Resume", onClick = { model.callbacks?.onResumeExit() })
        }
    }
}
