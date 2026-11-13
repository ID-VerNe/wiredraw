package com.rea.mtpimport.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.unit.Dp
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
        UiState.State.COLD -> NoCameraStatus(model)

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
                // `Cancel` 是扫描态唯一的动作，必须走 `StatusActionRow`（#374 / IA-9）：
                // 裸放 `SecondaryButton` 会按内容宽度被 `Column` 排到起点，贴着屏幕左边缘（dump 实测 x=0）。
                // 不贴边的保证来自这一行的 `fillMaxWidth` + `Arrangement.Center`（居中由行给，
                // 不是按钮自己带的）；左右留白仍走 `StatusActionRow` 的默认 8 dp，与 `EMPTY_CATALOG`
                // / `UNAVAILABLE` / `ERROR` 同口径。`§2.1` 的 16 dp 是页面水平内边距，不是底部动作行。
                StatusActionRow {
                    SecondaryButton("Cancel", onClick = { cb?.onCancel() })
                }
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
                StatusActionRow {
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
                StatusActionRow {
                    SecondaryButton("OK", onClick = { model.backToCatalog() })
                }
            }
        )

        UiState.State.DONE -> StatusLayout(
            model = model,
            icon = { CheckGlyph(56.dp, UiColors.onSurface) },
            actions = {
                StatusActionRow(horizontalPadding = 16.dp) {
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
                // 与看图页顶栏共用 `DetailsAction`（#310），着色跟随画布背景。
                DetailsAction(model, UiColors.onSurface)
            }
        )

        UiState.State.ERROR -> StatusLayout(
            model = model,
            icon = { WarningGlyph(56.dp, UiColors.onSurface) },
            actions = {
                StatusActionRow {
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

        else -> NoCameraStatus(model)
    }
}

/**
 * 无相机状态屏（`T-COLD` / issue #309）：`COLD` 与兜底的未枚举态呈现的是同一块屏。
 *
 * 两处原本各写一份 11 行载荷，改 CTA 文案、图标尺寸或 padding 时只改一处会让兜底屏与
 * `COLD` 视觉分叉。这里只收敛**载荷**，两个分支头仍各自保留：`COLD` 是有名状态，
 * 将来要分化时改它自己的分支即可。
 */
@Composable
private fun NoCameraStatus(model: AppModel) {
    StatusLayout(
        model = model,
        icon = { CameraGlyph(56.dp, UiColors.onSurface) },
        title = "No camera",
        actions = {
            // §2.1：主按钮水平 16 dp、底部 `inset + 8 dp`（inset 已由 `systemBarsPadding` 吃掉）。
            PrimaryButton(
                "Connect camera",
                onClick = { model.callbacks?.onConnectCamera() },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    )
}

/**
 * 状态屏动作行（F-18 / issue #217）：全宽、水平居中、上下 8 dp。
 * 唯一允许的差异是水平内边距：`SCANNING` / `EMPTY_CATALOG` / `UNAVAILABLE` / `ERROR` 用默认 8 dp，
 * `DONE` 为 16 dp。居中由本行的 `Arrangement.Center` 保证 —— 裸放的按钮会被 `Column` 排到起点。
 */
@Composable
private fun StatusActionRow(
    horizontalPadding: Dp = 8.dp,
    content: @Composable RowScope.() -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = horizontalPadding, vertical = 8.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
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
