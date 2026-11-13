package com.rea.mtpimport.ui

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.PhotoWall
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/**
 * 照片墙快速滚动条与日期指示拉手（issue #366）。
 *
 * 挂载于照片网格右侧边缘（非侵入式悬浮层）：
 * 1. 触控热区宽 32 dp，常态显示 4 dp 极细半透明拉手；
 * 2. 手势触摸/拖拽时，拉手弹性展开至 8 dp，并在拉手左侧弹出悬浮日期气泡（DateBubble）；
 * 3. 纵向滑动映射归一化比例（0.0 ~ 1.0）至 gridState.scrollToItem；
 * 4. 滑动跨越不同拍摄日期组时触发轻微触感震动反馈（HapticFeedback）。
 */
@Composable
fun FastScroller(
    model: AppModel,
    modifier: Modifier = Modifier
) {
    val groups = model.visibleGroups
    val collapsed = model.collapsed.toSet()
    val totalItems = remember(groups, collapsed) {
        PhotoWall.gridTotalItems(groups, collapsed)
    }

    // 项数过少无需展示快速滚动拉手
    if (totalItems <= 15 || model.backgroundScanning) return

    val coroutineScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var lastGroupKey by remember { mutableStateOf<String?>(null) }

    val scrollFraction by remember(totalItems) {
        derivedStateOf {
            if (isDragging) {
                dragFraction
            } else {
                val firstIndex = model.gridState.firstVisibleItemIndex
                if (totalItems <= 1) 0f else (firstIndex.toFloat() / (totalItems - 1).toFloat()).coerceIn(0f, 1f)
            }
        }
    }

    val animatedFraction by animateFloatAsState(
        targetValue = scrollFraction,
        label = "fastScrollerFraction"
    )

    val thumbWidth by animateDpAsState(
        targetValue = if (isDragging) 8.dp else 4.dp,
        label = "fastScrollerThumbWidth"
    )

    val activeGroup by remember(scrollFraction, groups, collapsed) {
        derivedStateOf {
            val targetIndex = (scrollFraction * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
            PhotoWall.groupAtItemIndex(groups, collapsed, targetIndex)
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxHeight()
            .width(36.dp)
            .testTag("fastScroller")
            .pointerInput(totalItems, groups, collapsed) {
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        val heightPx = size.height.toFloat()
                        val thumbTravel = (heightPx - 48.dp.toPx()).coerceAtLeast(1f)
                        val frac = ((offset.y - 24.dp.toPx()) / thumbTravel).coerceIn(0f, 1f)
                        dragFraction = frac
                        val targetIndex = (frac * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
                        val group = PhotoWall.groupAtItemIndex(groups, collapsed, targetIndex)
                        if (group != null && group.key != lastGroupKey) {
                            lastGroupKey = group.key
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        coroutineScope.launch {
                            model.gridState.scrollToItem(targetIndex)
                        }
                    },
                    onVerticalDrag = { change, _ ->
                        change.consume()
                        val heightPx = size.height.toFloat()
                        val thumbTravel = (heightPx - 48.dp.toPx()).coerceAtLeast(1f)
                        val frac = ((change.position.y - 24.dp.toPx()) / thumbTravel).coerceIn(0f, 1f)
                        dragFraction = frac
                        val targetIndex = (frac * (totalItems - 1)).roundToInt().coerceIn(0, totalItems - 1)
                        val group = PhotoWall.groupAtItemIndex(groups, collapsed, targetIndex)
                        if (group != null && group.key != lastGroupKey) {
                            lastGroupKey = group.key
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                        coroutineScope.launch {
                            model.gridState.scrollToItem(targetIndex)
                        }
                    },
                    onDragEnd = {
                        isDragging = false
                    },
                    onDragCancel = {
                        isDragging = false
                    }
                )
            }
    ) {
        val density = LocalDensity.current
        val trackHeightPx = constraints.maxHeight.toFloat()
        val thumbHeightDp = 48.dp
        val thumbHeightPx = with(density) { thumbHeightDp.toPx() }
        val maxOffsetPx = (trackHeightPx - thumbHeightPx).coerceAtLeast(0f)
        val currentOffsetPx = animatedFraction * maxOffsetPx

        Row(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset { IntOffset(0, currentOffsetPx.roundToInt()) },
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (isDragging && activeGroup != null) {
                DateBubble(label = activeGroup!!.label)
            }
            Box(
                modifier = Modifier
                    .padding(end = 4.dp)
                    .size(width = thumbWidth, height = thumbHeightDp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(if (isDragging) UiColors.onSurface else UiColors.outlineSoft)
            )
        }
    }
}

/**
 * 拖动快速滚动条时在手柄左侧跟随弹出的日期指示气泡（issue #366）。
 */
@Composable
fun DateBubble(
    label: String,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .padding(end = 12.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(UiColors.canvas)
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .semantics {
                contentDescription = label
            }
            .testTag("dateBubble"),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = TextSmall.copy(
                color = UiColors.surfaceWhite,
                fontWeight = FontWeight.Medium
            )
        )
    }
}
