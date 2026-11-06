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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.TopBarSpec
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

/** 短英文名号；`detailsEntry` 为真时长按仍进 Details（库页原有交互）。 */
@Composable
private fun TopBarLabel(
    text: String,
    model: AppModel,
    detailsEntry: Boolean,
    style: TextStyle = TextBody
) {
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
        BasicText(text, style = style.copy(color = UiColors.onSurface), maxLines = 1)
    }
}

/** 照片墙顶栏（方案 B）：相机线稿 + 设备名 + 计数胶囊 + `Filter`。 */
@Composable
fun LibraryTopBar(model: AppModel) {
    TopBarRow {
        CameraGlyph(20.dp, UiColors.onSurface)
        Spacer(Modifier.width(8.dp))
        TopBarLabel(model.deviceName, model, detailsEntry = true)
        Spacer(Modifier.weight(1f))
        // 渐进态的唯一临时动作（issue #60）：后台补齐时仍要能取消扫描。
        // 完成后消失，不成为常驻控件；热区 48 dp（A-01）。
        if (model.backgroundScanning) {
            IconAction(desc = "Cancel", onClick = { model.callbacks?.onCancel() }) {
                CloseGlyph(20.dp, UiColors.onSurface)
            }
        }
        // 顶栏 filter 芯片（V-31）：只在非 All 档出现，12 sp 非计数文本；日期档取组标签。
        // 它不是计数胶囊，不参与 `C-08` 的「胶囊与主 CTA 同分母」，只是为了让人一眼
        // 看出当前照片墙被过滤到了哪一档。
        val chip = model.filterChip
        if (chip != null) {
            BasicText(
                chip,
                style = TextSmall.copy(color = UiColors.secondary),
                maxLines = 1,
                modifier = Modifier.testTag("filterChip")
            )
            Spacer(Modifier.width(8.dp))
        }
        // 顶栏计数胶囊只在 CATALOG_READY 出现（UI-SPEC V-27）：与主 CTA 同分母（当前
        // filter 可见集合的导出文件数），只作常驻计数，不承担「唯一分母」职责。
        // 判定与 SelfTest 共用 `TopBarSpec.showsCountPill`（N-1），页面里不再写条件表达式。
        val count = model.visibleExportCount
        if (TopBarSpec.showsCountPill(model.state, count)) {
            Pill(count.toString())
            Spacer(Modifier.width(8.dp))
        }
        IconAction("Filter", onClick = { model.filterOpen = true }) {
            FilterGlyph(20.dp, UiColors.outline)
        }
    }
}

/**
 * 状态页顶栏（逐态分化，UI-SPEC §2.2 / `V-26`）。
 *
 * - `COLD`：相机线稿 + `a7C`，右侧断开短词 `Disconnected`；
 * - `ERROR`：`V-28` 的断线图标 + `Disconnected`，右侧 `Reconnect` 可点标签（`V-29`）；
 * - 其余：相机线稿 + `a7C`。
 */
@Composable
fun StatusTopBar(model: AppModel) {
    TopBarRow {
        if (model.state == UiState.State.ERROR) {
            ErrorIdentity(model)
            Spacer(Modifier.weight(1f))
            ReconnectAction(model)
        } else {
            CameraGlyph(20.dp, UiColors.onSurface)
            Spacer(Modifier.width(8.dp))
            TopBarLabel(model.deviceName, model, detailsEntry = false)
            Spacer(Modifier.weight(1f))
            if (model.state == UiState.State.COLD) {
                TopBarLabel("Disconnected", model, detailsEntry = false, style = TextSmall)
            }
        }
    }
}

/**
 * `ERROR` 顶栏左侧整簇：断线图标 + `Disconnected`（UI-SPEC `V-28` / §2.2）。
 *
 * 整簇是 `Details` 的长按入口（`L-05`）：热区 48 dp 高、只吃长按不吃点击，
 * 与右侧 `Reconnect` 的点击区互不重叠（`V-29`）。
 */
@Composable
private fun ErrorIdentity(model: AppModel) {
    Row(
        modifier = Modifier
            .heightIn(min = 48.dp)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { model.detailsOpen = true }) }
            // N-4：整簇是一个可长按的单节点。`mergeDescendants = true` 会让 dump 在同一
            // bounds 上留下「描述节点 + 长按动作节点 + 文本节点」三个条目，
            // 与 `TopBarLabel` 的旧写法不一致；这里改用 `clearAndSetSemantics` 收敛成一个。
            .clearAndSetSemantics {
                contentDescription = "Disconnected"
                role = Role.Button
                onLongClick(label = "Details") {
                    model.detailsOpen = true
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        UnpluggedGlyph(20.dp, UiColors.onSurface)
        Spacer(Modifier.width(8.dp))
        BasicText("Disconnected", style = TextSmall.copy(color = UiColors.onSurface), maxLines = 1)
    }
}

/**
 * `ERROR` 顶栏的 `Reconnect` 动作标签（UI-SPEC `V-29`）：12 sp 单行、独立 48 dp 热区，
 * 与内容区同名的 `Reconnect` 插头图标复用同一个重连入口（不另起并发路径）。
 */
@Composable
private fun ReconnectAction(model: AppModel) {
    Box(
        modifier = Modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .tappable { model.callbacks?.onConnectCamera() }
            .semantics { contentDescription = "Reconnect"; role = Role.Button },
        contentAlignment = Alignment.CenterEnd
    ) {
        BasicText(
            "Reconnect",
            style = TextSmall.copy(color = UiColors.onSurface, textAlign = TextAlign.End),
            maxLines = 1
        )
    }
}
