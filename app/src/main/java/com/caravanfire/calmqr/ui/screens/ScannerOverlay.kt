package com.caravanfire.calmqr.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp

/** A pulsing ring that animates at the given pixel position, then fades. */
@Composable
fun FocusRing(
    position: Offset?,
    onFinished: () -> Unit
) {
    if (position == null) return
    val scale = remember(position) { Animatable(1.4f) }
    val alpha = remember(position) { Animatable(1f) }
    LaunchedEffect(position) {
        scale.animateTo(1f, tween(180))
        alpha.animateTo(0f, tween(420))
        onFinished()
    }
    val radiusDp = 36.dp
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = radiusDp.toPx() * scale.value
        drawCircle(
            color = Color.White.copy(alpha = alpha.value),
            radius = r,
            center = position,
            style = Stroke(width = 3.dp.toPx())
        )
    }
}

/** Returns the centered ROI rectangle in pixels, covering [fraction] of the shorter axis. */
fun computeViewfinderRect(canvasSize: Size, fraction: Float = 0.7f): Rect {
    val side = kotlin.math.min(canvasSize.width, canvasSize.height) * fraction
    val left = (canvasSize.width - side) / 2f
    val top = (canvasSize.height - side) / 2f
    return Rect(left, top, left + side, top + side)
}

/** Draws a translucent scrim around a centered square cutout with a stroked border. */
@Composable
fun ViewfinderBox(modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val rect = computeViewfinderRect(size)
        val cornerRadius = 12.dp.toPx()

        val scrim = Path().apply {
            addRect(Rect(0f, 0f, size.width, size.height))
        }
        val hole = Path().apply {
            addRoundRect(RoundRect(rect, cornerRadius, cornerRadius))
        }
        val masked = Path.combine(PathOperation.Difference, scrim, hole)

        drawPath(masked, color = Color.Black.copy(alpha = 0.45f))
        drawPath(
            hole,
            color = Color.White.copy(alpha = 0.9f),
            style = Stroke(width = 2.dp.toPx())
        )
    }
}
