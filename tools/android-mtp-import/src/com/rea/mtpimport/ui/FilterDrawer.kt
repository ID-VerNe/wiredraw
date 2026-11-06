package com.rea.mtpimport.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 过滤抽屉（U-52 / issue #83）：`All` / `RAW` / `JPG` 各一项 + 日期标签逐行，每行热区 ≥48 dp。
 *
 * filter **只决定显示哪些照片单元**，不改变像素来源；分母跟随可见集合：
 * 计数胶囊、`Import all N`、组头聚合全部按当前档位的可见单元算（U-53 / `C-08`）。
 */
@Composable
fun FilterDrawer(model: AppModel) {
    Box(Modifier.fillMaxSize()) {
        Scrim { model.filterOpen = false }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(UiColors.surfaceWhite)
                .systemBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .heightIn(max = 480.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                BasicText("Filter", style = TextBody)
                Spacer(Modifier.weight(1f))
                IconAction(desc = "Close", onClick = { model.filterOpen = false }) {
                    CloseGlyph(16.dp, UiColors.outline)
                }
            }

            FilterRow("All", model.filterKind == FilterKind.ALL) {
                model.setFilter(FilterKind.ALL)
                model.filterOpen = false
            }
            FilterRow("RAW", model.filterKind == FilterKind.RAW) {
                model.setFilter(FilterKind.RAW)
                model.filterOpen = false
            }
            FilterRow("JPG", model.filterKind == FilterKind.JPG) {
                model.setFilter(FilterKind.JPG)
                model.filterOpen = false
            }
            model.allGroups.forEach { group ->
                val active = model.filterKind == FilterKind.DATE && model.filterDate == group.key
                FilterRow(group.label, active) {
                    model.setFilter(FilterKind.DATE, group.key)
                    model.filterOpen = false
                }
            }
        }
    }
}

@Composable
private fun FilterRow(label: String, active: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tappable(onClick = onClick)
            .semantics { selected = active },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(label, style = TextBody.copy(color = if (active) UiColors.onSurface else UiColors.secondary))
        Spacer(Modifier.width(8.dp))
        if (active) {
            Box(Modifier.width(12.dp))
            BasicText("·", style = TextBody)
        }
    }
}
