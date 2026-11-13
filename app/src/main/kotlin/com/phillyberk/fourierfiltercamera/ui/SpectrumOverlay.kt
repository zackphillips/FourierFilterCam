package com.phillyberk.fourierfiltercamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phillyberk.fourierfiltercamera.AxisUnits
import com.phillyberk.fourierfiltercamera.camera.fitRect
import com.phillyberk.fourierfiltercamera.dsp.FrequencyGrid
import com.phillyberk.fourierfiltercamera.dsp.PhysicalScale
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Touch surface and annotations drawn over the spectrum: frequency axes
 * in the chosen units, the diffraction cutoff when it falls inside the
 * sampled band, and a crosshair at the last touch.
 */
@Composable
fun SpectrumOverlay(
    grid: FrequencyGrid,
    scale: PhysicalScale?,
    units: AxisUnits,
    cursor: Pair<Double, Double>?,
    onTouch: (u: Double, v: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val metrics = remember(density) {
        with(density) { OverlayMetrics(unit = 1.dp.toPx(), textPx = 12.sp.toPx(), minTickSpacingPx = 72.dp.toPx()) }
    }
    val touch = rememberUpdatedState(onTouch)
    val gridState = rememberUpdatedState(grid)

    fun DrawScopeLike.toFrequency(p: Offset): Pair<Double, Double>? {
        val g = gridState.value
        val rect = fitRect(g.width, g.height, width, height)
        if (!rect.contains(p.x, p.y)) return null
        val x = (p.x - rect.left) / rect.width() * g.width
        val y = (p.y - rect.top) / rect.height() * g.height
        return g.u(x.toDouble()) to g.v(y.toDouble())
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                val area = DrawScopeLike(size.width.toFloat(), size.height.toFloat())
                detectTapGestures { p -> area.toFrequency(p)?.let { (u, v) -> touch.value(u, v) } }
            }
            .pointerInput(Unit) {
                val area = DrawScopeLike(size.width.toFloat(), size.height.toFloat())
                detectDragGestures(
                    onDragStart = { p -> area.toFrequency(p)?.let { (u, v) -> touch.value(u, v) } },
                    onDrag = { change, _ ->
                        change.consume()
                        area.toFrequency(change.position)?.let { (u, v) -> touch.value(u, v) }
                    },
                )
            },
    ) {
        val r = fitRect(grid.width, grid.height, size.width, size.height)
        fun px(u: Double) = (r.left + grid.x(u) / grid.width * r.width()).toFloat()
        fun py(v: Double) = (r.top + grid.y(v) / grid.height * r.height()).toFloat()

        drawAxes(grid, scale, units, ::px, ::py, Offset(r.left, r.top), Size(r.width(), r.height()), metrics)
        val m = metrics.unit

        scale?.diffractionCutoffCyclesPerMm(550.0)?.let { cutMm ->
            val cut = scale.cyclesPerPixel(cutMm)
            if (cut < 0.5) {
                val rx = (cut * r.width()).toFloat()
                val ry = (cut * r.height()).toFloat()
                drawOval(
                    color = Color.White.copy(alpha = 0.6f),
                    topLeft = Offset(px(0.0) - rx, py(0.0) - ry),
                    size = Size(2 * rx, 2 * ry),
                    style = Stroke(width = 1.5f * m, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6 * m, 5 * m))),
                )
            }
        }

        cursor?.let { (u, v) ->
            val c = Offset(px(u), py(v))
            val arm = 10 * m
            drawLine(Color.Cyan, c - Offset(arm, 0f), c + Offset(arm, 0f), strokeWidth = 1.5f * m)
            drawLine(Color.Cyan, c - Offset(0f, arm), c + Offset(0f, arm), strokeWidth = 1.5f * m)
        }
    }
}

private class DrawScopeLike(val width: Float, val height: Float)

private fun tickPaint(textSizePx: Float) = android.graphics.Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = textSizePx
    isAntiAlias = true
    setShadowLayer(textSizePx / 8f, 0f, 0f, android.graphics.Color.BLACK)
}

/** Spacing, in px, that the overlay's text and marks scale from. */
private data class OverlayMetrics(val unit: Float, val textPx: Float, val minTickSpacingPx: Float)

private fun DrawScope.drawAxes(
    grid: FrequencyGrid,
    scale: PhysicalScale?,
    requested: AxisUnits,
    px: (Double) -> Float,
    py: (Double) -> Float,
    origin: Offset,
    area: Size,
    metrics: OverlayMetrics,
) {
    val paint = tickPaint(metrics.textPx)
    val m = metrics.unit
    // Fall back to cycles/pixel when the camera did not report what the units need.
    val units = if (convert(0.5, requested, scale) != null) requested else AxisUnits.CYCLES_PER_PIXEL
    val nyquist = convert(0.5, units, scale)!!
    // Tick density follows the drawn size so labels never collide.
    val stepX = niceStep(2 * nyquist, (area.width / metrics.minTickSpacingPx).toInt().coerceIn(2, 10))
    val stepY = niceStep(2 * nyquist, (area.height / metrics.minTickSpacingPx).toInt().coerceIn(2, 10))
    val axisColor = Color.White.copy(alpha = 0.35f)

    // Axes through DC.
    drawLine(axisColor, Offset(origin.x, py(0.0)), Offset(origin.x + area.width, py(0.0)), strokeWidth = 1f)
    drawLine(axisColor, Offset(px(0.0), origin.y), Offset(px(0.0), origin.y + area.height), strokeWidth = 1f)

    val native = drawContext.canvas.nativeCanvas
    for (k in ceil(-nyquist / stepX).toInt()..floor(nyquist / stepX).toInt()) {
        if (k == 0) continue
        val value = k * stepX
        val x = px(toCyclesPerPixel(value, units, scale) ?: continue)
        drawLine(Color.White, Offset(x, py(0.0) - 4 * m), Offset(x, py(0.0) + 4 * m), strokeWidth = m)
        native.drawText(formatTick(value), x + 2 * m, py(0.0) + 4 * m + metrics.textPx, paint)
    }
    for (k in ceil(-nyquist / stepY).toInt()..floor(nyquist / stepY).toInt()) {
        if (k == 0) continue
        val value = k * stepY
        val y = py(toCyclesPerPixel(value, units, scale) ?: continue)
        drawLine(Color.White, Offset(px(0.0) - 4 * m, y), Offset(px(0.0) + 4 * m, y), strokeWidth = m)
        native.drawText(formatTick(value), px(0.0) + 6 * m, y + metrics.textPx / 3, paint)
    }
    val label = units.short
    native.drawText(label, origin.x + area.width - paint.measureText(label) - 4 * m, py(0.0) - 4 * m, paint)
}
