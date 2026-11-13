package com.rea.mtpimport.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** 三档快捷过滤：标签与枚举一一对应，页面不再各写一条（F-17 / #217）。 */
private val QUICK_FILTERS = listOf(
    "All" to FilterKind.ALL,
    "RAW" to FilterKind.RAW,
    "JPG" to FilterKind.JPG
)

/**
 * 过滤抽屉（U-52 / issue #83）：`All` / `RAW` / `JPG` 各一项 + 日期标签逐行，每行热区 ≥48 dp。
 *
 * filter **只决定显示哪些照片单元**，不改变像素来源；分母跟随可见集合：
 * 计数胶囊、`Import all N`、组头聚合全部按当前档位的可见单元算（U-53 / `C-08`）。
 * 抽屉骨架由 [DrawerSkeleton] 唯一提供（F-04 / #217）。
 */
@Composable
fun FilterDrawer(model: AppModel) {
    DrawerSkeleton(title = "Filter", visible = model.filterOpen,
        onClose = { model.filterOpen = false }) {
        QUICK_FILTERS.forEach { (label, kind) ->
            FilterRow(label, model.filterKind == kind) {
                model.setFilter(kind)
                model.filterOpen = false
            }
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
            Spacer(Modifier.width(12.dp))
            BasicText("·", style = TextBody)
        }
    }
}
