package com.rea.mtpimport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.rea.mtpimport.UiState
import com.rea.mtpimport.UiText

/**
 * 传输屏（`IMPORTING` / `PAUSED`）。
 *
 * 一屏只有一个焦点：环里的主数字。环下 `12/36` 是同一批次的另一种写法，
 * 速率与 ETA 一律进 Details 抽屉（P-03 / U-45），不在这里出现。
 */
@Composable
fun TransferScreen(model: AppModel) {
    val done = model.importDoneFiles
    val total = model.importTotalFiles
    val progress = if (total > 0) done.toFloat() / total.toFloat() else 0f

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
            IconAction(desc = "Back", onClick = { model.callbacks?.onBack() }) {
                BackGlyph(20.dp, UiColors.ink)
            }
        }

        Column(
            Modifier.weight(1f).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            ProgressRing(
                progress = progress,
                stateText = UiText.fraction(done, total).replace("/", " of ")
            ) {
                BasicText(UiText.percentText(done, total), style = TextDisplay)
            }
            Spacer(Modifier.height(16.dp))
            BasicText(UiText.fraction(done, total), style = TextLabel.copy(color = UiColors.secondary))
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                Modifier
                    .height(48.dp)
                    .padding(horizontal = 12.dp)
                    .tappable { model.callbacks?.onPauseResume() },
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (model.state == UiState.State.PAUSED) {
                    PlayGlyph(16.dp, UiColors.ink)
                    Spacer(Modifier.width(8.dp))
                    BasicText("Resume", style = TextLabel)
                } else {
                    PauseGlyph(16.dp, UiColors.ink)
                    Spacer(Modifier.width(8.dp))
                    BasicText("Pause", style = TextLabel)
                }
            }
            Spacer(Modifier.width(8.dp))
            SecondaryButton("Cancel", onClick = { model.callbacks?.onCancel() })
        }
    }
}
