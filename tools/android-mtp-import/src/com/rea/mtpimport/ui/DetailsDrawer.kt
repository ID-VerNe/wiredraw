package com.rea.mtpimport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiState

/**
 * 详情抽屉（L-05）：默认收起，展开后覆盖在内容之上，不推挤顶栏与主 CTA。
 *
 * 里面只放排查用的东西：`Location details` 的路径、最近日志、以及（仅 debug 假数据模式）
 * 10 态切换入口。路径只在这里出现（§0.10 的「Location」口径）。
 */
@Composable
fun DetailsDrawer(model: AppModel) {
    Box(Modifier.fillMaxSize()) {
        Scrim { model.detailsOpen = false }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(UiColors.surfaceWhite)
                .systemBarsPadding()
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp)
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Location details", style = TextBody)
                Spacer(Modifier.weight(1f))
                IconAction(desc = "Close", onClick = { model.detailsOpen = false }) {
                    CloseGlyph(16.dp, UiColors.outline)
                }
            }
            BasicText(model.locationPath, style = TextMonoData)

            // P-09：空间预检的差额只在 Details 出现（主视觉只有一行 `Low space`）。
            if (model.lowSpace && model.lowSpaceDetail.isNotEmpty()) {
                Spacer(Modifier.heightIn(min = 8.dp))
                BasicText(model.lowSpaceDetail, style = TextMonoData)
            }

            if (model.logLines.isNotEmpty()) {
                Spacer(Modifier.heightIn(min = 8.dp))
                model.logLines.takeLast(8).forEach { line ->
                    BasicText(line, style = TextMonoData)
                }
            }

            // PD：逐文件失败固定展示，不随日志滚动被挤掉（DONE 首屏受 U-47 约束不能出现 Failed N）。
            if (model.failedLines.isNotEmpty()) {
                Spacer(Modifier.heightIn(min = 8.dp))
                model.failedLines.forEach { line ->
                    BasicText(line, style = TextMonoData)
                }
            }

            if (model.demoActive) {
                Spacer(Modifier.heightIn(min = 12.dp))
                BasicText("Demo state", style = TextSmall)
                UiState.State.values().forEach { s ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .tappable { model.callbacks?.onDemoState(s.name) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(s.name, style = TextBody.copy(color = UiColors.secondary))
                    }
                }
            }
        }
    }
}
