package com.rea.mtpimport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiState

/**
 * 根 composable：按 `UiState` 三屏分流。
 *
 * - `photo` 页是详情视图，优先级最高（由 `CATALOG_READY` 的格子点进来）。
 * - `IMPORTING` / `PAUSED` → 传输屏；`CATALOG_READY` → 照片墙；其余 → 状态屏。
 * - 详情抽屉与过滤抽屉是覆盖层，不改变上面的内容（L-05 / L-09）。
 */
@Composable
fun ImportApp(model: AppModel) {
    val state = model.state

    Box(Modifier.fillMaxSize().background(UiColors.canvas)) {
        if (model.openedPhoto != null) {
            PhotoScreen(model)
        } else if (UiState.isTransfer(state)) {
            TransferScreen(model)
        } else if (UiState.showsLibrary(state)) {
            LibraryScreen(model)
        } else {
            StatusScreen(model)
        }

        if (model.detailsOpen) DetailsDrawer(model)
        if (model.filterOpen) FilterDrawer(model)
    }
}

/** 状态屏的三段式骨架：图标 + 一行字在内容区居中；动作固定在底部（L-04）。 */
@Composable
fun StateScaffold(
    icon: @Composable () -> Unit,
    title: String?,
    actions: @Composable () -> Unit
) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                if (title != null) {
                    Spacer(Modifier.height(16.dp))
                    BasicText(title, style = TextDisplay)
                }
            }
        }
        actions()
    }
}
