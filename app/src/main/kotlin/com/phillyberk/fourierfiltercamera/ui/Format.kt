package com.phillyberk.fourierfiltercamera.ui

import com.phillyberk.fourierfiltercamera.AxisUnits
import com.phillyberk.fourierfiltercamera.UiState
import com.phillyberk.fourierfiltercamera.dsp.ColorMode
import com.phillyberk.fourierfiltercamera.dsp.FilterShape
import com.phillyberk.fourierfiltercamera.dsp.OutputMode
import com.phillyberk.fourierfiltercamera.dsp.PhysicalScale
import org.opencv.imgproc.Imgproc
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log10
import kotlin.math.pow

enum class Colormap(val label: String, val cvCode: Int) {
    INFERNO("Inferno", Imgproc.COLORMAP_INFERNO),
    VIRIDIS("Viridis", Imgproc.COLORMAP_VIRIDIS),
    MAGMA("Magma", Imgproc.COLORMAP_MAGMA),
    TURBO("Turbo", Imgproc.COLORMAP_TURBO),
    JET("Jet (classic)", Imgproc.COLORMAP_JET),
}

val ColorMode.label: String
    get() = when (this) {
        ColorMode.GRAY -> "Grayscale"
        ColorMode.RGB -> "Color, digital (same mask per channel)"
        ColorMode.RGB_OPTICAL -> "Color, optical 4f (mask scales with wavelength)"
    }

val OutputMode.label: String
    get() = when (this) {
        OutputMode.REAL -> "Real part (digital filter)"
        OutputMode.MAGNITUDE -> "Magnitude (coherent 4f intensity)"
    }

private fun fmt(x: Double): String = when {
    x == 0.0 -> "0"
    abs(x) >= 100 -> String.format(Locale.US, "%.0f", x)
    abs(x) >= 10 -> String.format(Locale.US, "%.1f", x)
    abs(x) >= 1 -> String.format(Locale.US, "%.2f", x)
    else -> String.format(Locale.US, "%.3f", x)
}

/** Converts cycles/pixel into [units], or null if the camera did not report what is needed. */
fun convert(cyclesPerPixel: Double, units: AxisUnits, scale: PhysicalScale?): Double? = when (units) {
    AxisUnits.CYCLES_PER_PIXEL -> cyclesPerPixel
    AxisUnits.CYCLES_PER_MM -> scale?.cyclesPerMm(cyclesPerPixel)
    AxisUnits.CYCLES_PER_DEGREE -> scale?.cyclesPerDegree(cyclesPerPixel)
}

/** Inverse of [convert]. */
fun toCyclesPerPixel(value: Double, units: AxisUnits, scale: PhysicalScale?): Double? = when (units) {
    AxisUnits.CYCLES_PER_PIXEL -> value
    AxisUnits.CYCLES_PER_MM -> scale?.cyclesPerPixel(value)
    AxisUnits.CYCLES_PER_DEGREE -> scale?.let { s ->
        s.cyclesPerDegree(1.0)?.let { perUnit -> value / perUnit }
    }
}

val AxisUnits.short: String
    get() = when (this) {
        AxisUnits.CYCLES_PER_PIXEL -> "cy/px"
        AxisUnits.CYCLES_PER_MM -> "cy/mm"
        AxisUnits.CYCLES_PER_DEGREE -> "cy/°"
    }

fun formatTick(value: Double): String = fmt(value)

/** 1-2-5 step giving roughly [targetTicks] ticks over [span]. */
fun niceStep(span: Double, targetTicks: Int = 4): Double {
    val raw = span / targetTicks
    val mag = 10.0.pow(floor(log10(raw)))
    val norm = raw / mag
    val step = when {
        norm < 1.5 -> 1.0
        norm < 3.5 -> 2.0
        norm < 7.5 -> 5.0
        else -> 10.0
    }
    return step * mag
}

/** One-line readout for a frequency (u, v) in cycles/pixel. */
fun frequencyReadout(u: Double, v: Double, scale: PhysicalScale?): String {
    val r = hypot(u, v)
    val parts = mutableListOf("|ν| ${fmt(r)} cy/px")
    scale?.let { s ->
        parts += "${fmt(s.cyclesPerMm(r))} cy/mm"
        s.cyclesPerDegree(r)?.let { parts += "${fmt(it)} cy/°" }
    }
    if (r > 0) parts += "period ${fmt(1 / r)} px"
    return parts.joinToString(" · ")
}

/** Optics summary line shown under the spectrum. */
fun opticsReadout(scale: PhysicalScale?): String? {
    val s = scale ?: return null
    val parts = mutableListOf(
        "pitch ${fmt(s.pitchMm * 1000)} µm (×${fmt(s.binning)} binned)",
        "Nyquist ${fmt(s.nyquistCyclesPerMm)} cy/mm",
    )
    s.diffractionCutoffCyclesPerMm(550.0)?.let { cut ->
        val f = s.optics.fNumber
        parts += "diffraction cutoff ${fmt(cut)} cy/mm @550 nm, f/${fmt(f ?: 0.0)}"
    }
    return parts.joinToString(" · ")
}

/** Text written into the saved image's MediaStore description. */
fun describe(s: UiState): String {
    val shape = when (val sh = s.spec.shape) {
        FilterShape.Empty -> "none"
        is FilterShape.Box -> "box ±${fmt(sh.halfU)} × ±${fmt(sh.halfV)} cy/px"
        is FilterShape.HalfBox -> "half box ${fmt(sh.halfU)} × ±${fmt(sh.halfV)} cy/px"
        is FilterShape.Circle -> "circle r=${fmt(sh.radius)} cy/px"
        is FilterShape.HalfCircle -> "half circle r=${fmt(sh.radius)} cy/px"
        is FilterShape.Annulus -> "annulus ${fmt(sh.inner)}–${fmt(sh.outer)} cy/px"
        is FilterShape.Freeform -> "freeform, ${sh.dabs.size} dabs"
    }
    val action = if (s.spec.passInside) "pass" else "block"
    return "Fourier Filter Cam: $action $shape; ${s.colorMode.label}; ${s.outputMode.label}"
}
