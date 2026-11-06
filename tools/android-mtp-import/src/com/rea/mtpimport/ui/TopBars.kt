package com.rea.mtpimport.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiState

/**
 * 顶栏容器：56 dp、全宽、统一 `topBar` 标签。
 *
 * 顶栏在 10 态 + `photo` + `transfer` 全部常驻（issue #44 决策 2）。这里只放
 * 「图标 + 短英文」，不放解释句，也不做第二个焦点。
 */
@Composable
fun TopBarRow(
    horizontalPadding: Dp = 16.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .testTag("topBar")
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/**
 * 连接指示按状态推定，不是实时 USB 探测：`COLD` 与 `ERROR` 视为不可用
 * （`ERROR` 的恢复动作是拔插数据线，见 §1.2），其余状态视为相机在线。
 */
fun cameraLive(state: UiState.State): Boolean =
    state != UiState.State.COLD && state != UiState.State.ERROR

/** 顶栏左侧的相机标记；在线用相机线稿，不可用用带斜线的同一线稿。 */
@Composable
fun CameraMark(model: AppModel) {
    if (cameraLive(model.state)) {
        CameraGlyph(20.dp, UiColors.ink)
    } else {
        CameraOffGlyph(20.dp, UiColors.ink)
    }
}

/** 短英文名号；`detailsEntry` 为真时长按仍进 Details（库页原有交互）。 */
@Composable
private fun TopBarLabel(text: String, model: AppModel, detailsEntry: Boolean) {
    val base = if (detailsEntry) {
        Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { model.detailsOpen = true }) }
    } else {
        Modifier.heightIn(min = 48.dp)
    }
    Box(
        base.clearAndSetSemantics {
            contentDescription = text
            if (detailsEntry) {
                role = Role.Button
                onLongClick(label = "Details") {
                    model.detailsOpen = true
                    true
                }
            }
        },
        contentAlignment = Alignment.CenterStart
    ) {
        BasicText(text, style = TextBody.copy(color = UiColors.ink))
    }
}

/** 照片墙顶栏（方案 A）：相机标记 + 设备名 + `Filter`；不出现计数胶囊。 */
@Composable
fun LibraryTopBar(model: AppModel) {
    TopBarRow {
        CameraMark(model)
        Spacer(Modifier.width(8.dp))
        TopBarLabel(model.deviceName, model, detailsEntry = true)
        Spacer(Modifier.weight(1f))
        IconAction("Filter", onClick = { model.filterOpen = true }) {
            FilterGlyph(20.dp, UiColors.outline)
        }
    }
}

/** 状态页顶栏（方案 A）：相机标记 + 设备名；断开态只换成同一线稿加斜线。 */
@Composable
fun StatusTopBar(model: AppModel) {
    val live = cameraLive(model.state)
    TopBarRow {
        CameraMark(model)
        Spacer(Modifier.width(8.dp))
        TopBarLabel(
            if (live) model.deviceName else "Disconnected",
            model,
            detailsEntry = false
        )
        Spacer(Modifier.weight(1f))
    }
}
