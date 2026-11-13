package com.rea.mtpimport.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.Catalog
import com.rea.mtpimport.ImportGate
import com.rea.mtpimport.PhotoWall
import com.rea.mtpimport.ThumbnailStore
import com.rea.mtpimport.TopBarSpec
import com.rea.mtpimport.UiText
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

/**
 * 照片墙（`CATALOG_READY`）。
 *
 * 顶栏：相机线稿 + 设备短名 + 12 sp 计数胶囊 + 过滤入口（`CATALOG_READY` 形态，UI-SPEC §2.2 / `V-27`）。
 * 内容：按日期分组的缩略图网格；缩略图由 `ThumbnailStore` 在 IO 线程异步取（#59），
 * 取不到时按 §0.7 / V-19 用**同尺寸灰块 + 文件名 12 sp** 降级。
 * 底部：单一主 CTA，48 dp，文案唯一出处是 §4.5 `T-CATALOG`。
 */
@Composable
fun LibraryScreen(model: AppModel) {
    val groups = model.visibleGroups
    val collapsed = model.collapsed.toSet()
    val coroutineScope = rememberCoroutineScope()

    val currentGroup by remember(groups, collapsed) {
        derivedStateOf {
            val firstIndex = model.gridState.firstVisibleItemIndex
            PhotoWall.groupAtItemIndex(groups, collapsed, firstIndex)
        }
    }

    val showFloatingHeader by remember {
        derivedStateOf {
            model.gridState.firstVisibleItemIndex > 0 || model.gridState.firstVisibleItemScrollOffset > 32
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // 顶栏在全部状态常驻（#44 决策 2）；内容按 §2.2 逐态分化，本页是 CATALOG_READY 形态。
        // 「哪些状态有顶栏」与 SelfTest 共用 `TopBarSpec.hasBar`（N-1）。
        if (TopBarSpec.hasBar(model.state)) LibraryTopBar(model)
        // 渐进态（issue #60 / UI-SPEC V-23）：后台补齐时顶栏下方加 2 dp 进度细线，完成后消失。
        if (model.backgroundScanning) ScanLine(model)
        Box(Modifier.weight(1f).fillMaxWidth()) {
            PhotoGrid(model)
            if (showFloatingHeader && currentGroup != null) {
                FloatingDateHeader(
                    currentGroup = currentGroup!!,
                    onClick = {
                        val targetIndex = PhotoWall.groupStartIndex(groups, collapsed, currentGroup!!.key)
                        coroutineScope.launch {
                            model.gridState.animateScrollToItem(targetIndex)
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                )
            }
            FastScroller(
                model = model,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        ImportBar(model)
    }
}

/**
 * 悬浮吸顶日期指示胶囊（issue #365）。
 *
 * 随着照片墙滚动，当前视口所属的拍摄日期常驻显示于顶部；
 * 点击胶囊可平滑回滚至该日期组起始位置。
 */
@Composable
private fun FloatingDateHeader(
    currentGroup: PhotoWall.Group,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(UiColors.surfaceWhite.copy(alpha = 0.95f))
            .border(1.dp, UiColors.outlineSoft, RoundedCornerShape(16.dp))
            .tappable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics {
                role = Role.Button
                contentDescription = "Current date: " + currentGroup.label
                onClick(label = "Scroll to top of date group") {
                    onClick()
                    true
                }
            }
            .testTag("floatingDateHeader")
    ) {
        BasicText(
            text = currentGroup.label,
            style = TextSmall.copy(
                color = UiColors.onSurface,
                fontWeight = FontWeight.Medium
            )
        )
    }
}

/**
 * 渐进态的 2 dp 进度细线（issue #60 / UI-SPEC V-23）。
 *
 * 不写文字、不写数字：扫描分母（1526）按 C-09 只能出现在 `T-SCANNING`，
 * 这里只用宽度表达进度；照片墙仍是本状态唯一的内容区焦点。
 *
 * #265：轨道底用 `outline`（白底 4.47:1 ≥ 3:1），与进度环轨道同判定；
 * `outlineSoft`（1.70:1）只作分隔细线与禁用底色，不再承载「用长度表达进度」的图形。
 */
@Composable
private fun ScanLine(model: AppModel) {
    val fraction = UiText.progressFraction(model.scanDone, model.scanTotal)
    Box(
        Modifier
            .fillMaxWidth()
            .height(2.dp)
            .background(UiColors.outline)
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
        state = model.gridState,
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
                items(count = group.photos.size, key = { i -> group.photos[i].key }) { i ->
                    PhotoCell(model, group.photos[i])
                }
            }
        }
    }
}

/** 日期组头：整行可点（L-08）；文案是组内聚合 `Jun 7 · 43 · 74.0 MB`（U-44/#83）。 */
@Composable
private fun GroupHeader(model: AppModel, group: PhotoWall.Group) {
    // 组头两种呈现共用一次聚合（#214 / F-09）：视觉与读屏都取 summary 的结果。
    val summary = group.summary(model.exportMode)
    // 勾选状态走 AppModel 的统一入口（#214 / F-20），页面不裸读版本号。
    val allSelected = model.isGroupChecked(group)
    val collapsed = model.isCollapsed(group.key)
    val haptic = LocalHapticFeedback.current
    // 手势必须同时满足两件事（#374 / IA-13）：key 只跟 `group.key` 走（对象身份每次重组都变，
    // 拿它当 key 会让检测器反复重启、长按被吞），而闭包读到的必须是**最新**的 `group`
    // （key 不变时 `pointerInput` 不重启，直接捕获会冻结在首次组合的那一版快照上）。
    // 渐进扫描每 200 个对象发布一次（`CatalogScanner.PARTIAL_EVERY`）、filter 档每次重组都重建
    // `Group`，冻结快照会让长按只选中旧规模的那几张，且与同节点的读屏动作（始终读新值）不一致。
    val currentGroup = rememberUpdatedState(group)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            // 单击折叠/展开（L-08），长按 ≥500 ms = 组选（#62）；滚动越界会取消手势，
            // 与单格长按进看图页互不冲突（作用在不同行/格）。
            .pointerInput(group.key) {
                detectTapGestures(
                    onTap = { model.toggleCollapsed(group.key) },
                    onLongPress = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        model.toggleGroupSelection(currentGroup.value)
                    }
                )
            }
            .semantics {
                // A-05：`Date group <date>, N photos, <size>`
                contentDescription = summary.spoken()
                role = Role.Button
                onClick(label = if (collapsed) "Expand date group" else "Collapse date group") {
                    model.toggleCollapsed(group.key)
                    true
                }
                // 手势语义由 `detectTapGestures` 提供不了，读屏靠这条进入组选（#62）。
                onLongClick(
                    label = if (allSelected) "Deselect date group" else "Select date group"
                ) {
                    model.toggleGroupSelection(group)
                    true
                }
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // #82：折叠箭头常驻行首，整组勾只在选中态画在行尾固定槽内（L-08 的顺序：
        // 箭头 · 标题 · 勾槽）。两个槽在两态都参与测量，标题起点与行尾右边缘恒定，
        // 选中只改变右槽内的绘制内容。
        Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterStart) {
            ChevronGlyph(16.dp, UiColors.outline, up = collapsed)
        }
        BasicText(
            summary.header(),
            style = TextSmall.copy(color = UiColors.secondary),
            modifier = Modifier.weight(1f)
        )
        Box(Modifier.width(24.dp), contentAlignment = Alignment.CenterEnd) {
            if (allSelected) CheckGlyph(16.dp, UiColors.onSurface)
        }
    }
}

/**
 * 网格单元：整格可点（L-08），已选用**形态变化**表达（1 dp 墨色描边 + 去饱和解除），
 * 不画勾选圈、不放徽标、不放大小（V-11 / V-15）。
 *
 * 点格 = 切换已选（L-08 的硬要求）；长按 = 进 `photo` 页（§1.2 的入口）。
 *
 * 文件名只在**视觉降级形态**里出现（§0.7 / V-19），但对读屏整体收起：
 * `clearAndSetSemantics` 让这一格只读 `Photo, selected` / `Photo, not selected`（A-05），
 * 也让 dump 里不出现文件名、`ARW` 字样（U-44 的禁项）。
 */
@Composable
private fun PhotoCell(model: AppModel, unit: PhotoWall.Unit) {
    // 勾选状态走 AppModel 的统一入口（#214 / F-20）：入口内部读版本号，勾选后本格才重绘。
    val checked = model.isUnitChecked(unit)

    // #59：缩略图在 IO 线程异步取，composition / 主线程不做同步解码；
    // 渐进扫描期间不碰相机（扫描线程正在同一个 MtpDevice 上发 getObjectInfo）。
    // #83：一格 = 一个照片单元，缓存键优先同名 JPG 句柄，缺失回退 ARW（`V-32`）。
    val key = unit.previewHandle()
    val store = model.thumbnails
    var thumb by remember(key, store) {
        mutableStateOf(store?.cached(key))
    }
    LaunchedEffect(key, store, model.backgroundScanning) {
        if (thumb == null && store != null && !model.backgroundScanning) {
            val bmp = store.loadAsync(unit)
            if (bmp != null) thumb = bmp
        }
    }

    Box(
        Modifier
            .aspectRatio(1.5f)
            .clip(RoundedCornerShape(8.dp))
            .background(UiColors.container.copy(alpha = if (checked) 1f else 0.6f))
            .then(softOutline(checked))
            .pointerInput(unit.key) {
                detectTapGestures(
                    onTap = { model.toggleUnitSelection(unit) },
                    onLongPress = { model.openPhoto(unit) }
                )
            }
            .clearAndSetSemantics {
                selected = checked
                contentDescription = if (checked) "Photo, selected" else "Photo, not selected"
                role = Role.Button
                onClick {
                    model.toggleUnitSelection(unit)
                    true
                }
                // 长按进 `photo` 页（§1.2 的入口）：手势在 `detectTapGestures` 里，
                // 但 `clearAndSetSemantics` 会把手势语义全部清掉，读屏就进不了照片页，
                // 所以这里补一条 `onLongClick`（PR #41 第四轮评审非阻断 1）。
                onLongClick("Open photo") {
                    model.openPhoto(unit)
                    true
                }
            }
    ) {
        val bitmap = thumb
        if (bitmap != null) {
            // 已选态沿用 V-11：外层 softOutline（1 dp 墨色）+ 未选去饱和、已选恢复饱和度。
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                colorFilter = if (!checked) {
                    ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0.55f) })
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // V-19 的降级形态：同尺寸灰块 + 文件名 12 sp（网格内唯一允许出现文件名的场景）。
            BasicText(
                unit.preview().name,
                style = TextSmall.copy(color = if (checked) UiColors.onSurface else UiColors.secondary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(6.dp)
            )
        }
    }
}

/** 底部固定操作区：唯一主 CTA；分子分母都按当前 filter 的可见集合算（`C-08` / `C-10`）。 */
@Composable
private fun ImportBar(model: AppModel) {
    // 已选数由 AppModel 的入口读版本号（#214 / F-20）。
    val selected = model.visibleSelectedCount
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
            text = UiText.importCta(selected, model.visibleExportCount),
            onClick = { model.callbacks?.onImport() },
            // 渐进态禁用：扫描线程与导入不能在同一 MTP 会话上并行发命令。
            // P-09：空间不足时同样禁用。两个条件都必须生效——判定归 `ImportGate`（N-1），
            // 页面里不再写 `enabled = ...` 表达式，SelfTest 直接断言同一个函数。
            enabled = ImportGate.enabled(model.backgroundScanning, model.lowSpace)
        )
    }
}

/**
 * 视口感知与协程协同取消的异步取图拓展（issue #373）。
 * 当 PhotoCell 因滚动离开视口时，Compose 取消 LaunchedEffect，
 * 触发 invokeOnCancellation 将任务标记为已取消；未开始 USB 读取的任务会被队列自动丢弃。
 */
private suspend fun ThumbnailStore.loadAsync(unit: PhotoWall.Unit): android.graphics.Bitmap? =
    suspendCancellableCoroutine { cont ->
        val cancelled = AtomicBoolean(false)
        cont.invokeOnCancellation {
            cancelled.set(true)
        }
        loadAsync(unit, { cancelled.get() || !cont.isActive }) { bmp ->
            if (cont.isActive) {
                cont.resume(bmp)
            }
        }
    }
