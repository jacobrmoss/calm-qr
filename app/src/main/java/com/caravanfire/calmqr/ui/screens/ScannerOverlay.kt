package com.caravanfire.calmqr.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import kotlinx.coroutines.delay

/**
 * A focus indicator drawn at the tapped pixel position, held briefly, then cleared.
 * Replays on every [tick] change so each tap re-triggers it — even repeated taps on
 * the same spot, or a tap while a previous ring is still showing.
 *
 * Drawn as solid black + white at full opacity (no fade or scale animation): while the
 * camera is live the e-ink panel runs in a fast, near-1-bit mode that renders crisp
 * black<->white transitions reliably but drops the gray, gradually-changing pixels an
 * alpha fade produces. The black halo under the white ring keeps it visible against any
 * grayscale camera background.
 */
@Composable
fun FocusRing(
    position: Offset?,
    tick: Int,
    onFinished: () -> Unit
) {
    if (position == null) return
    LaunchedEffect(tick) {
        delay(450)
        onFinished()
    }
    val radiusDp = 30.dp
    Canvas(modifier = Modifier.fillMaxSize()) {
        val r = radiusDp.toPx()
        drawCircle(
            color = Color.Black,
            radius = r,
            center = position,
            style = Stroke(width = 7.dp.toPx())
        )
        drawCircle(
            color = Color.White,
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
