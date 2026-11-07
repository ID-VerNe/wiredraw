package com.rea.mtpimport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import com.rea.mtpimport.Dims
import com.rea.mtpimport.TopBarSpec
import com.rea.mtpimport.UiState
import com.rea.mtpimport.UiText

/**
 * 其余 7 个状态屏：`COLD` / `PERMISSION_PENDING` / `SCANNING` / `EMPTY_CATALOG` /
 * `UNAVAILABLE` / `DONE` / `ERROR`。
 *
 * 共同口径（§4.5 `T-xx`）：
 * - 一屏一个图标 + 一行主文案，动作固定在底部；
 * - `ERROR` 的动作词是 `Reconnect`（顶栏 12 sp 标签 + 内容区插头图标同名），全文没有 `Retry`
 *   （真机实测应用内重试唤不醒相机）；`COLD` / `EMPTY_CATALOG` 仍用 `Connect camera`；
 * - 不露内部状态名、不加解释句。
 */
@Composable
fun StatusScreen(model: AppModel) {
    val cb = model.callbacks

    when (model.state) {
        UiState.State.COLD -> StatusLayout(
            model = model,
            icon = { CameraGlyph(56.dp, UiColors.onSurface) },
            title = "No camera",
            actions = {
                // §2.1：主按钮水平 16 dp、底部 `inset + 8 dp`（inset 已由 `systemBarsPadding` 吃掉）。
                PrimaryButton(
                    "Connect camera",
                    onClick = { cb?.onConnectCamera() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        )

        UiState.State.PERMISSION_PENDING -> StatusLayout(
            model = model,
            icon = { CameraGlyph(56.dp, UiColors.onSurface) },
            actions = {
                // 主文案与唯一动作都是 `Allow`（T-PERMISSION）。
                PrimaryButton(
                    "Allow",
                    onClick = { cb?.onAllow() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        )

        UiState.State.SCANNING -> StatusLayout(
            model = model,
            icon = {},
            actions = {
                SecondaryButton("Cancel", onClick = { cb?.onCancel() })
            },
            customCenter = {
                ProgressRing(
                    progress = UiText.progressFraction(model.scanDone, model.scanTotal),
                    stateText = null,
                    diameter = Dims.SCAN_RING_DIAMETER_DP.dp,
                    stroke = Dims.SCAN_RING_STROKE_DP.dp
                ) {}
                Spacer(Modifier.height(16.dp))
                // 1526 只在这个位置出现（C-09 白名单：扫描进度分母）。
                BasicText(
                    UiText.fraction(model.scanDone, model.scanTotal),
                    style = TextLabel.copy(color = UiColors.secondary)
                )
            }
        )

        UiState.State.EMPTY_CATALOG -> StatusLayout(
            model = model,
            icon = { CameraGlyph(56.dp, UiColors.onSurface) },
            title = "No photos",
            actions = {
                // V-09：`EMPTY_CATALOG` 没有实心主按钮，`Connect camera` 以图标按钮呈现（次按钮语义）。
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                    IconAction(desc = "Connect camera", onClick = { cb?.onConnectCamera() }) {
                        PlugGlyph(24.dp, UiColors.onSurface)
                    }
                }
            }
        )

        UiState.State.UNAVAILABLE -> StatusLayout(
            model = model,
            icon = { BlockGlyph(56.dp, UiColors.outline) },
            title = "Not available",
            actions = {
                Row(Modifier.fillMaxWidth().padding(8.dp), horizontalArrangement = Arrangement.Center) {
                    SecondaryButton("OK", onClick = { model.backToCatalog() })
                }
            }
        )

        UiState.State.DONE -> StatusLayout(
            model = model,
            icon = { CheckGlyph(56.dp, UiColors.onSurface) },
            actions = {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center
                ) {
                    SecondaryButton("Open", onClick = { cb?.onOpen() })
                }
            },
            content = {
                Spacer(Modifier.height(16.dp))
                BasicText("Done", style = TextDisplay)
                Spacer(Modifier.height(8.dp))
                BasicText(
                    UiText.doneSummary(model.donePhotos, model.doneBytes),
                    style = TextDisplay
                )
                Spacer(Modifier.height(8.dp))
                // `Location` 必须是可点节点（U-47）；路径只在 Location details 里出现。
                IconAction(desc = "Location", onClick = { model.detailsOpen = true }) {
                    LocationGlyph(20.dp, UiColors.onSurface)
                }
            }
        )

        UiState.State.ERROR -> StatusLayout(
            model = model,
            icon = { WarningGlyph(56.dp, UiColors.onSurface) },
            actions = {
                Row(
                    Modifier.fillMaxWidth().padding(8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // V-09 / T-ERROR：错误态没有实心主按钮，动作是 `Reconnect` 次按钮图标
                    // （与顶栏 12 sp 标签同一动作、同一无障碍名，§4.5 / V-29）。
                    IconAction(desc = "Reconnect", onClick = { cb?.onConnectCamera() }) {
                        PlugGlyph(24.dp, UiColors.onSurface)
                    }
                    Spacer(Modifier.width(8.dp))
                    SecondaryButton("Details", onClick = { model.detailsOpen = true })
                }
            },
            content = {
                Spacer(Modifier.height(16.dp))
                // 唯一允许出现中文的位置（C-19），≤20 字（C-01）。三段式只在 Details 抽屉里。
                BasicText(model.errorReason, style = TextBody)
            }
        )

        else -> StatusLayout(
            model = model,
            icon = { CameraGlyph(56.dp, UiColors.onSurface) },
            title = "No camera",
            actions = {
                PrimaryButton(
                    "Connect camera",
                    onClick = { cb?.onConnectCamera() },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        )
    }
}

/**
 * 状态屏骨架：顶栏常驻（#44 决策 2），图标 + 主文案在内容区居中，动作固定在底部（L-04）。
 * 扫描态要走自己的居中内容，所以留 `customCenter`。
 */
@Composable
private fun StatusLayout(
    model: AppModel,
    icon: @Composable () -> Unit,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit = {},
    actions: @Composable ColumnScope.() -> Unit,
    customCenter: @Composable ColumnScope.() -> Unit = {}
) {
    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        // 顶栏判定与 SelfTest 共用 `TopBarSpec.hasBar`（N-1）。
        if (TopBarSpec.hasBar(model.state)) StatusTopBar(model)
        Box(Modifier.weight(1f).fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                icon()
                if (title != null) {
                    Spacer(Modifier.height(16.dp))
                    BasicText(title, style = TextDisplay)
                }
                content()
                customCenter()
            }
        }
        actions()
    }
}
