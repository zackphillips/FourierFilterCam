package com.phillyberk.fourierfiltercamera.dsp

import kotlin.math.PI
import kotlin.math.min

/**
 * Optical parameters of a camera module, as reported by Camera2.
 *
 * @property pixelPitchMm native photosite pitch (sensor physical width / pixel array width)
 * @property activeWidthPx width of the active pixel array in native pixels
 * @property activeHeightPx height of the active pixel array in native pixels
 * @property focalLengthMm lens focal length, or null if the device does not report one
 * @property fNumber lens f-number, or null if the device does not report one
 */
data class SensorOptics(
    val pixelPitchMm: Double,
    val activeWidthPx: Int,
    val activeHeightPx: Int,
    val focalLengthMm: Double?,
    val fNumber: Double?,
)

/**
 * Converts frame-pixel frequencies to physical units for a stream of
 * [bufferWidth] x [bufferHeight] frames (sensor orientation, before any
 * display rotation).
 *
 * The stream is assumed to be a centered crop of the active array, scaled
 * uniformly, which is how Camera2 produces non-native aspect ratios. The
 * scale is therefore set by the axis that is not cropped.
 */
class PhysicalScale(val optics: SensorOptics, bufferWidth: Int, bufferHeight: Int) {
    /** Native pixels per frame pixel. */
    val binning: Double = min(
        optics.activeWidthPx.toDouble() / bufferWidth,
        optics.activeHeightPx.toDouble() / bufferHeight,
    )

    /** Effective pixel pitch of the analyzed frame at the sensor, in mm. */
    val pitchMm: Double = optics.pixelPitchMm * binning

    val nyquistCyclesPerMm: Double get() = 0.5 / pitchMm

    /** Spatial frequency at the sensor plane. */
    fun cyclesPerMm(cyclesPerPixel: Double): Double = cyclesPerPixel / pitchMm

    /**
     * Angular frequency in object space, independent of subject distance.
     * One frame pixel subtends pitch / f radians (small-angle).
     */
    fun cyclesPerDegree(cyclesPerPixel: Double): Double? =
        optics.focalLengthMm?.let { f -> cyclesPerPixel * f / pitchMm * PI / 180.0 }

    fun cyclesPerPixel(cyclesPerMm: Double): Double = cyclesPerMm * pitchMm

    /**
     * Incoherent (OTF) diffraction cutoff 1 / (lambda N) at the sensor. The
     * scene is incoherently lit, so this is the limit that applies to the
     * captured image.
     */
    fun diffractionCutoffCyclesPerMm(wavelengthNm: Double): Double? =
        optics.fNumber?.let { n -> 1.0 / (wavelengthNm * 1e-6 * n) }
}

/**
 * Effective center wavelengths of the color channels. Values are typical
 * for a Bayer CMOS sensor behind an IR-cut filter; individual sensors vary
 * by +/- 20 nm.
 */
data class Wavelengths(val redNm: Double, val greenNm: Double, val blueNm: Double) {
    /** Reference wavelength the user draws the mask at. */
    val referenceNm: Double get() = greenNm

    companion object {
        val BAYER_TYPICAL = Wavelengths(redNm = 600.0, greenNm = 530.0, blueNm = 465.0)
    }
}
