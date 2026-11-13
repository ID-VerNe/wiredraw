package com.rea.mtpimport.ui

import android.app.Activity
import android.view.WindowInsetsController
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.TopBarSpec
import com.rea.mtpimport.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 单张照片页（`photo`）。只有两件信息：`JPG · 1.7 MB` / `ARW · 23.8 MB` 胶囊 + 返回。
 *
 * 明确不做（§4.2 / U-51）：Share / Edit / Favorite / Delete / Histogram 一律没有。
 *
 * 像素来源（#61 / #83，两段式，`V-32`）：
 * 1. 先在 IO 线程走 `ThumbnailStore.loadPreview` 拿相机缩略图（同名 JPG 优先、缺失回退 ARW），
 *    首帧只读缓存，**不在 composition / 主线程解码**；
 * 2. 停留片刻后异步取同名 JPG **整张**并就地替换；取不到（无 JPG / 失败）就保留缩略图。
 *    绝不为看图页下载整张 ARW——那正是这条两段式要避开的 23.8 MB / 张。
 * 加载中或拿不到像素时保持单行胶囊形态（U-51：不显示占位块、不放假图）。
 * demo 模式（issue #46）由 `ThumbnailStore` 注入整图，仍走同一条 IO 线程链路。
 */
@Composable
fun PhotoScreen(model: AppModel) {
    val unit = model.openedPhoto ?: return
    val store = model.thumbnails
    // 首帧只读缓存；demo 也走同一条异步链路（ThumbnailStore 的 demo 分支在 IO 线程解码），
    // 不在 composition / 主线程同步解码（V-25）。
    var preview by remember(unit.key, store) {
        mutableStateOf(store?.cachedPreview(unit))
    }

    LaunchedEffect(unit.key, store, model.backgroundScanning) {
        if (store == null || model.backgroundScanning) return@LaunchedEffect
        if (preview == null) {
            preview = withContext(Dispatchers.IO) { model.loadPreview(unit) }
        }
        // 停留后再补整张 JPG：普通网格滚动不会触发这条（只有 photo 页在组合里）。
        delay(600)
        val full = withContext(Dispatchers.IO) { model.loadFullJpeg(unit) }
        if (full != null) preview = full
    }

    // photo 页是画布黑：状态栏 / 导航栏图标必须切浅色，离开时按**原值**恢复（P1-4 / P2-4）。
    // B-1：进入时两个位一起清掉，退出时必须两个位一起还原——只回 status 会把导航手势条
    // 留在浅色图标上，回到白底页面就是白压白（V-25 的对比度口径下等于不可见）。
    // 用 `systemBarsAppearance` 记录原状而不是硬编码「都设」，避免把别处的深色栏设置改坏。
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.insetsController
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        val original = controller?.systemBarsAppearance ?: 0
        controller?.setSystemBarsAppearance(0, lightBars)
        onDispose {
            controller?.setSystemBarsAppearance(original and lightBars, lightBars)
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(UiColors.canvas)
            .tappable(onClick = {})
    ) {
        val bitmap = preview
        if (bitmap != null) {
            // 与网格同款（#374 / IA-6）：`asImageBitmap()` 的包装进 `remember`，不在每次重组里重建。
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // 顶栏常驻（#44 决策 2）：photo 页复用同一个 `topBar` 容器，内容仍是返回 + 位置。
            // 判定与 SelfTest 共用 `TopBarSpec.hasBar`（N-1）。
            if (TopBarSpec.hasBar(model.state)) {
                TopBarRow(horizontalPadding = 8.dp) {
                    IconAction(desc = "Back", onClick = { model.callbacks?.onBack() }) {
                        BackGlyph(20.dp, UiColors.surfaceWhite)
                    }
                    Spacer(Modifier.weight(1f))
                    // `Details` 入口与状态屏 `DONE` 共用 `DetailsAction`（#310），着色跟随照片背景。
                    DetailsAction(model, UiColors.surfaceWhite)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth())

            Box(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                // 胶囊 12 sp `mono-data`（#44 / #45 决策），与环内 `12/36` 同族。
                // #83：胶囊描述**当前档位下的导出文件**，不写死 ARW（V-25 / C-05）。
                val shown = unit.export(model.exportMode) ?: unit.preview()
                val label = if (shown.isRaw()) "ARW" else "JPG"
                Pill(label + " · " + UiText.capacity(shown.size), onCanvas = true)
            }
        }
    }
}
