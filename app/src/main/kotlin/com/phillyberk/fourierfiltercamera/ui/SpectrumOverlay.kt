package com.phillyberk.fourierfiltercamera.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
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

        drawAxes(grid, scale, units, ::px, ::py, Offset(r.left, r.top), Size(r.width(), r.height()))

        scale?.diffractionCutoffCyclesPerMm(550.0)?.let { cutMm ->
            val cut = scale.cyclesPerPixel(cutMm)
            if (cut < 0.5) {
                val rx = (cut * r.width()).toFloat()
                val ry = (cut * r.height()).toFloat()
                drawOval(
                    color = Color.White.copy(alpha = 0.6f),
                    topLeft = Offset(px(0.0) - rx, py(0.0) - ry),
                    size = Size(2 * rx, 2 * ry),
                    style = Stroke(width = 2f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f))),
                )
            }
        }

        cursor?.let { (u, v) ->
            val c = Offset(px(u), py(v))
            val arm = 18f
            drawLine(Color.Cyan, c - Offset(arm, 0f), c + Offset(arm, 0f), strokeWidth = 2f)
            drawLine(Color.Cyan, c - Offset(0f, arm), c + Offset(0f, arm), strokeWidth = 2f)
        }
    }
}

private class DrawScopeLike(val width: Float, val height: Float)

private val tickPaint = android.graphics.Paint().apply {
    color = android.graphics.Color.WHITE
    textSize = 28f
    isAntiAlias = true
    setShadowLayer(3f, 0f, 0f, android.graphics.Color.BLACK)
}

private fun DrawScope.drawAxes(
    grid: FrequencyGrid,
    scale: PhysicalScale?,
    requested: AxisUnits,
    px: (Double) -> Float,
    py: (Double) -> Float,
    origin: Offset,
    area: Size,
) {
    // Fall back to cycles/pixel when the camera did not report what the units need.
    val units = if (convert(0.5, requested, scale) != null) requested else AxisUnits.CYCLES_PER_PIXEL
    val nyquist = convert(0.5, units, scale)!!
    val step = niceStep(2 * nyquist)
    val axisColor = Color.White.copy(alpha = 0.35f)

    // Axes through DC.
    drawLine(axisColor, Offset(origin.x, py(0.0)), Offset(origin.x + area.width, py(0.0)), strokeWidth = 1f)
    drawLine(axisColor, Offset(px(0.0), origin.y), Offset(px(0.0), origin.y + area.height), strokeWidth = 1f)

    val first = ceil(-nyquist / step).toInt()
    val last = floor(nyquist / step).toInt()
    val native = drawContext.canvas.nativeCanvas
    for (k in first..last) {
        if (k == 0) continue
        val value = k * step
        val f = toCyclesPerPixel(value, units, scale) ?: continue
        val x = px(f)
        val y = py(f)
        drawLine(Color.White, Offset(x, py(0.0) - 8f), Offset(x, py(0.0) + 8f), strokeWidth = 2f)
        drawLine(Color.White, Offset(px(0.0) - 8f, y), Offset(px(0.0) + 8f, y), strokeWidth = 2f)
        native.drawText(formatTick(value), x + 4f, py(0.0) + 34f, tickPaint)
        native.drawText(formatTick(value), px(0.0) + 12f, y + 10f, tickPaint)
    }
    native.drawText(units.short, origin.x + area.width - 110f, py(0.0) - 14f, tickPaint)
}
