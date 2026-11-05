package com.rea.mtpimport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.Catalog
import com.rea.mtpimport.PhotoWall
import com.rea.mtpimport.UiText

/**
 * 照片墙（`CATALOG_READY`）。
 *
 * 顶栏：相机图标 + 设备短名 + 徽标（ARW 张数 = `Import all N` 的 N，U-53）+ 过滤入口。
 * 内容：按日期分组的缩略图网格；缩略图未落地，按 §0.7 / V-19 用**同尺寸灰块 + 文件名 12 sp**。
 * 底部：单一主 CTA，48 dp，文案唯一出处是 §4.5 `T-CATALOG`。
 */
@Composable
fun LibraryScreen(model: AppModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        TopBar(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PhotoGrid(model)
        }
        ImportBar(model)
    }
}

@Composable
private fun TopBar(model: AppModel) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CameraGlyph(20.dp, UiColors.ink)
        Spacer(Modifier.width(8.dp))
        // 设备短名只作标识（§1.1）；长按它打开详情抽屉（L-05 的入口之一）。
        BasicText(
            model.deviceName,
            style = TextLabel,
            modifier = Modifier.pointerInput(Unit) {
                detectTapGestures(onLongPress = { model.detailsOpen = true })
            }
        )
        Spacer(Modifier.weight(1f))
        Pill(model.photoCount.toString())
        Spacer(Modifier.width(4.dp))
        IconAction(desc = "Filter", onClick = { model.filterOpen = true }) {
            FilterGlyph(20.dp, UiColors.outline)
        }
    }
}

@Composable
private fun PhotoGrid(model: AppModel) {
    val groups = model.visibleGroups
    LazyVerticalGrid(
        columns = GridCells.Fixed(3),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        groups.forEach { group ->
            item(key = "group-" + group.key, span = { GridItemSpan(maxLineSpan) }) {
                GroupHeader(model, group)
            }
            if (!model.isCollapsed(group.key)) {
                items(count = group.photos.size, key = { i -> group.photos[i].handle }) { i ->
                    PhotoCell(model, group.photos[i])
                }
            }
        }
    }
}

/** 日期组头：整行可点（L-08）；文案是组内聚合 `Jun 7 · 12 · 284 MB`（U-44）。 */
@Composable
private fun GroupHeader(model: AppModel, group: PhotoWall.Group) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .tappable { model.toggleCollapsed(group.key) }
            .semantics {
                // A-05：`Date group <date>, N photos, <size>`
                contentDescription = "Date group " + group.label + ", " + group.count() +
                    " photos, " + UiText.capacity(group.bytes)
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(
            group.header(),
            style = TextSmall.copy(color = UiColors.secondary),
            modifier = Modifier.weight(1f)
        )
        ChevronGlyph(16.dp, UiColors.outline, up = model.isCollapsed(group.key))
    }
}

/**
 * 网格单元：整格可点（L-08），已选用**形态变化**表达（2 dp 墨色描边 + 去饱和解除），
 * 不画勾选圈、不放徽标、不放大小（V-11 / V-15）。
 *
 * 点格 = 切换已选（L-08 的硬要求）；长按 = 进 `photo` 页（§1.2 的入口）。
 *
 * 文件名只在**视觉降级形态**里出现（§0.7 / V-19），但对读屏整体收起：
 * `clearAndSetSemantics` 让这一格只读 `Photo, selected` / `Photo, not selected`（A-05），
 * 也让 dump 里不出现文件名、`ARW` 字样（U-44 的禁项）。
 */
@Composable
private fun PhotoCell(model: AppModel, entry: Catalog.Entry) {
    // Selection 本身不是 observable：读一次版本号，勾选后本格才会重绘。
    val version = model.selectionVersion
    val checked = model.selection?.isChecked(entry.handle) == true

    Box(
        Modifier
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(UiColors.container.copy(alpha = if (checked) 1f else 0.6f))
            .then(softOutline(checked))
            .pointerInput(entry.handle) {
                detectTapGestures(
                    onTap = { model.toggleSelection(entry) },
                    onLongPress = { model.openPhoto(entry) }
                )
            }
            .clearAndSetSemantics {
                selected = checked
                contentDescription = if (checked) "Photo, selected" else "Photo, not selected"
                role = Role.Button
                onClick {
                    model.toggleSelection(entry)
                    true
                }
            }
    ) {
        // V-19 的降级形态：同尺寸灰块 + 文件名 12 sp（网格内唯一允许出现文件名的场景）。
        BasicText(
            entry.name,
            style = TextSmall.copy(color = if (checked) UiColors.ink else UiColors.secondary),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
        )
    }
}

/** 底部固定操作区：唯一主 CTA，已选 0 → `Import all N`，已选 ≥1 → `Import N`。 */
@Composable
private fun ImportBar(model: AppModel) {
    val version = model.selectionVersion
    val selected = model.selectedCount
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PrimaryButton(
            text = UiText.importCta(selected, model.photoCount),
            onClick = { model.callbacks?.onImport() }
        )
    }
}
