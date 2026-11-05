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
 * 过滤抽屉（U-52）：`All` / `RAW` / 日期（组头原样）三类，每行热区 ≥48 dp。
 *
 * 三类都只是**过滤**，不改分母：徽标、`Import all N`、组头聚合始终按完整照片墙算
 * （U-53 的「同分母」要求）。
 */
@Composable
fun FilterDrawer(model: AppModel) {
    Box(Modifier.fillMaxSize()) {
        Scrim { model.filterOpen = false }
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(UiColors.canvas)
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
        BasicText(label, style = TextBody.copy(color = if (active) UiColors.ink else UiColors.secondary))
        Spacer(Modifier.width(8.dp))
        if (active) {
            Box(Modifier.width(12.dp))
            BasicText("·", style = TextBody)
        }
    }
}
