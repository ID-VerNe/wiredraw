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
import com.rea.mtpimport.UiText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 单张照片页（`photo`）。只有两件信息：`ARW · <size> MB` 胶囊 + 返回。
 *
 * 明确不做（§4.2 / U-51）：Share / Edit / Favorite / Delete / Histogram 一律没有。
 *
 * 像素来源（#61）：真实 MTP 路径在 IO 线程走 `ThumbnailStore.loadPreview`
 * （只读 `getThumbnail`：ARW → 同名同目录 JPG），**不在 composition / 主线程解码**。
 * 加载中或拿不到像素时保持单行 `ARW · <size> MB` 胶囊形态（U-51：不显示占位块、不放假图）。
 * demo 模式（issue #46）由 `ThumbnailStore` 注入整图，仍走 `loadPreview` 的 IO 线程解码；
 * 没有素材时与真实路径一样退回胶囊。
 */
@Composable
fun PhotoScreen(model: AppModel) {
    val entry = model.openedPhoto ?: return
    val store = model.thumbnails
    // 首帧只读缓存；demo 也走同一条异步链路（ThumbnailStore 的 demo 分支在 IO 线程解码），
    // 不在 composition / 主线程同步解码（V-25）。
    var preview by remember(entry.handle, store) {
        mutableStateOf(store?.cachedPreview(entry.handle))
    }

    LaunchedEffect(entry.handle, store, model.backgroundScanning) {
        if (preview == null && store != null && !model.backgroundScanning) {
            preview = withContext(Dispatchers.IO) { model.loadPreview(entry) }
        }
    }

    // photo 页是画布黑：状态栏 / 导航栏图标必须切浅色，离开时恢复深色（P1-4 / P2-4）。
    val view = LocalView.current
    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.insetsController
        val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
            WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
        controller?.setSystemBarsAppearance(0, lightBars)
        onDispose {
            controller?.setSystemBarsAppearance(
                WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS,
                lightBars
            )
        }
    }

    Box(Modifier.fillMaxSize().background(UiColors.photoCanvas)) {
        val bitmap = preview
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )
        }
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            // 顶栏常驻（#44 决策 2）：photo 页复用同一个 `topBar` 容器，内容仍是返回 + 位置。
            TopBarRow(horizontalPadding = 8.dp) {
                IconAction(desc = "Back", onClick = { model.callbacks?.onBack() }) {
                    BackGlyph(20.dp, UiColors.canvas)
                }
                Spacer(Modifier.weight(1f))
                IconAction(desc = "Location", onClick = { model.detailsOpen = true }) {
                    LocationGlyph(20.dp, UiColors.canvas)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth())

            Box(
                Modifier.fillMaxWidth().padding(bottom = 16.dp),
                contentAlignment = Alignment.Center
            ) {
                // 胶囊 12 sp `mono-data`（#44 / #45 决策），与环内 `12/36` 同族。
                Pill("ARW · " + UiText.capacity(entry.size), onDark = true)
            }
        }
    }
}
