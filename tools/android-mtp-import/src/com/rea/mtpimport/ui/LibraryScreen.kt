package com.rea.mtpimport.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
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
import androidx.compose.ui.semantics.onLongClick
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
 * 顶栏：相机线稿 + 设备短名 + 12 sp 计数胶囊 + 过滤入口（`CATALOG_READY` 形态，UI-SPEC §2.2 / `V-27`）。
 * 内容：按日期分组的缩略图网格；缩略图未落地，按 §0.7 / V-19 用**同尺寸灰块 + 文件名 12 sp**。
 * 底部：单一主 CTA，48 dp，文案唯一出处是 §4.5 `T-CATALOG`。
 */
@Composable
fun LibraryScreen(model: AppModel) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // 顶栏在全部状态常驻（#44 决策 2）；内容按 §2.2 逐态分化，本页是 CATALOG_READY 形态。
        LibraryTopBar(model)
        // 渐进态（issue #60 / UI-SPEC V-23）：后台补齐时顶栏下方加 2 dp 进度细线，完成后消失。
        if (model.backgroundScanning) ScanLine(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PhotoGrid(model)
        }
        ImportBar(model)
    }
}

/**
 * 渐进态的 2 dp 进度细线（issue #60 / UI-SPEC V-23）。
 *
 * 不写文字、不写数字：扫描分母（1526）按 C-09 只能出现在 `T-SCANNING`，
 * 这里只用宽度表达进度；照片墙仍是本状态唯一的内容区焦点。
 */
@Composable
private fun ScanLine(model: AppModel) {
    val fraction = if (model.scanTotal > 0) {
        (model.scanDone.toFloat() / model.scanTotal).coerceIn(0f, 1f)
    } else {
        0f
    }
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(UiColors.outlineSoft)
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(2.dp)
                .background(UiColors.ink)
        )
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
                // 长按进 `photo` 页（§1.2 的入口）：手势在 `detectTapGestures` 里，
                // 但 `clearAndSetSemantics` 会把手势语义全部清掉，读屏就进不了照片页，
                // 所以这里补一条 `onLongClick`（PR #41 第四轮评审非阻断 1）。
                onLongClick("Open photo") {
                    model.openPhoto(entry)
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
        // P-09：空间不足时 CTA 被禁用，并在其上方加一行 `Low space`；差额只进 Details。
        if (model.lowSpace) {
            BasicText(
                "Low space",
                style = TextSmall.copy(color = UiColors.secondary),
                modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
            )
        }
        PrimaryButton(
            text = UiText.importCta(selected, model.photoCount),
            onClick = { model.callbacks?.onImport() },
            // 渐进态禁用：扫描线程与导入不能在同一 MTP 会话上并行发命令。
            // P-09：空间不足时同样禁用，两个条件都必须生效。
            enabled = !model.backgroundScanning && !model.lowSpace
        )
    }
}
