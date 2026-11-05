package com.rea.mtpimport.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 图标全部用 Canvas 画线稿，不引 `material-icons-extended`：
 * 定稿要的是「FILL 0 / wght 300」的细线几何，手画 1.5 dp 描边比拉一整套图标库更接近，
 * 也避免为几个图标多编进几百 KB 资源。
 *
 * 每个图标都在 24 dp 的坐标系里按比例定位，缩放到 16/18/56 dp 时线条粗细保持不变。
 */

private const val STROKE_DP = 1.5f

private fun DrawScope.strokePx() = STROKE_DP.dp.toPx()

@Composable
fun CameraGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        // 机身
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.30f),
            size = Size(w * 0.80f, h * 0.48f),
            cornerRadius = CornerRadius(w * 0.08f),
            style = Stroke(s)
        )
        // 顶部取景凸起
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.34f, h * 0.22f),
            size = Size(w * 0.28f, h * 0.12f),
            cornerRadius = CornerRadius(w * 0.03f),
            style = Stroke(s)
        )
        // 镜头
        drawCircle(
            color = tint,
            radius = w * 0.15f,
            center = Offset(w * 0.50f, h * 0.54f),
            style = Stroke(s)
        )
    }
}

@Composable
fun BackGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        drawLine(tint, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.86f, h * 0.50f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.38f, h * 0.26f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.14f, h * 0.50f), Offset(w * 0.38f, h * 0.74f), s, StrokeCap.Round)
    }
}

@Composable
fun CloseGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        drawLine(tint, Offset(w * 0.24f, h * 0.24f), Offset(w * 0.76f, h * 0.76f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.76f, h * 0.24f), Offset(w * 0.24f, h * 0.76f), s, StrokeCap.Round)
    }
}

@Composable
fun ChevronGlyph(size: Dp, tint: Color, up: Boolean = false, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        val yTop = if (up) h * 0.66f else h * 0.36f
        val yBottom = if (up) h * 0.36f else h * 0.66f
        drawLine(tint, Offset(w * 0.24f, yTop), Offset(w * 0.50f, yBottom), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.50f, yBottom), Offset(w * 0.76f, yTop), s, StrokeCap.Round)
    }
}

@Composable
fun FilterGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        drawLine(tint, Offset(w * 0.12f, h * 0.28f), Offset(w * 0.88f, h * 0.28f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.26f, h * 0.50f), Offset(w * 0.74f, h * 0.50f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.40f, h * 0.72f), Offset(w * 0.60f, h * 0.72f), s, StrokeCap.Round)
    }
}

@Composable
fun PauseGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.18f
        val top = h * 0.24f
        val barH = h * 0.52f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.28f, top),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barW * 0.25f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.54f, top),
            size = Size(barW, barH),
            cornerRadius = CornerRadius(barW * 0.25f)
        )
    }
}

@Composable
fun PlayGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val path = Path().apply {
            moveTo(w * 0.30f, h * 0.22f)
            lineTo(w * 0.78f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.78f)
            close()
        }
        drawPath(path, tint)
    }
}

@Composable
fun CheckGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        drawLine(tint, Offset(w * 0.20f, h * 0.52f), Offset(w * 0.42f, h * 0.74f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.42f, h * 0.74f), Offset(w * 0.82f, h * 0.26f), s, StrokeCap.Round)
    }
}

@Composable
fun LocationGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        drawCircle(
            color = tint,
            radius = w * 0.20f,
            center = Offset(w * 0.50f, h * 0.40f),
            style = Stroke(s)
        )
        drawLine(tint, Offset(w * 0.35f, h * 0.55f), Offset(w * 0.50f, h * 0.84f), s, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.65f, h * 0.55f), Offset(w * 0.50f, h * 0.84f), s, StrokeCap.Round)
    }
}

@Composable
fun WarningGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.12f)
            lineTo(w * 0.90f, h * 0.84f)
            lineTo(w * 0.10f, h * 0.84f)
            close()
        }
        drawPath(path, tint, style = Stroke(s))
        drawLine(tint, Offset(w * 0.50f, h * 0.40f), Offset(w * 0.50f, h * 0.60f), s, StrokeCap.Round)
        drawCircle(tint, radius = s * 0.8f, center = Offset(w * 0.50f, h * 0.71f))
    }
}

@Composable
fun BlockGlyph(size: Dp, tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val s = strokePx()
        drawCircle(tint, radius = w * 0.34f, center = Offset(w * 0.50f, h * 0.50f), style = Stroke(s))
        drawLine(tint, Offset(w * 0.28f, h * 0.28f), Offset(w * 0.72f, h * 0.72f), s, StrokeCap.Round)
    }
}
