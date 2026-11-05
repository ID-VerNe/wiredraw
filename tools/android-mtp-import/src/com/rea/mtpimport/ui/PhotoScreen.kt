package com.rea.mtpimport.ui

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiText

/**
 * 单张照片页（`photo`）。只有两件信息：`ARW · <size> MB` 胶囊 + 返回。
 *
 * 明确不做（§4.2 / U-51）：Share / Edit / Favorite / Delete / Histogram 一律没有。
 * 内嵌 JPEG 预览未验证（§0.11 第 3 行）：**不显示占位块、不放假图**（spec 增量口径，
 * 只留单行 `ARW · 24.6 MB` 胶囊）。
 */
@Composable
fun PhotoScreen(model: AppModel) {
    val entry = model.openedPhoto ?: return

    Box(Modifier.fillMaxSize().background(UiColors.photoCanvas)) {
        Column(Modifier.fillMaxSize().systemBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
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
                Pill("ARW · " + UiText.capacity(entry.size), onDark = true)
            }
        }
    }
}
