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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiText

/**
 * 单张照片页（`photo`）。只有两件信息：`ARW · <size> MB` 胶囊 + 返回。
 *
 * 明确不做（§4.2 / U-51）：Share / Edit / Favorite / Delete / Histogram 一律没有。
 * 真实路径下内嵌 JPEG 预览未验证（§0.11 第 3 行）：**不显示占位块、不放假图**
 * （spec 增量口径，只留单行 `ARW · 24.6 MB` 胶囊）。
 *
 * demo 模式例外（issue #46）：`AppModel.previewSource` 由 debug 的 `DemoImages` 注入，
 * 有真实照片的降采样图时按 U-51「全幅照片」渲染；没有素材时 `previewFor` 返回 null，
 * 自动退回胶囊形态。
 */
@Composable
fun PhotoScreen(model: AppModel) {
    val entry = model.openedPhoto ?: return
    val preview = remember(entry.handle) { model.previewFor(entry) }

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
        if (preview != null) {
            Image(
                bitmap = preview.asImageBitmap(),
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
