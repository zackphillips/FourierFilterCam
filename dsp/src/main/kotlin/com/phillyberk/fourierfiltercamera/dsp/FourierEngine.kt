package com.phillyberk.fourierfiltercamera.dsp

import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc

enum class ColorMode {
    /** Luminance only. */
    GRAY,

    /** Each channel filtered with the same mask (a digital filter). */
    RGB,

    /**
     * Each channel filtered with the mask scaled by its wavelength, as a
     * physical mask in the Fourier plane of a 4f system would act.
     */
    RGB_OPTICAL,
}

enum class OutputMode {
    /** Real part of the filtered field: what a linear digital filter produces. */
    REAL,

    /** |filtered field|: the intensity-like view a coherent 4f system would show. */
    MAGNITUDE,
}

/**
 * Frequency-domain filtering of camera frames.
 *
 * Frames come in as RGBA CV_8UC4 Mats and go out as RGBA CV_8UC4. The
 * engine owns all intermediate buffers and reuses them across frames; the
 * filter is rebuilt only when [setFilter] receives a new spec or the frame
 * size or color mode changes.
 *
 * Not thread-safe: call [spectrum] and [filter] from one thread. Settings
 * may be changed from any thread and take effect on the next frame.
 */
class FourierEngine(
    private val wavelengths: Wavelengths = Wavelengths.BAYER_TYPICAL,
) {
    @Volatile var colorMode: ColorMode = ColorMode.GRAY

    @Volatile var outputMode: OutputMode = OutputMode.REAL

    /** Gaussian sigma, in spectrum pixels, used to soften mask edges and cut ringing. 0 disables. */
    @Volatile var edgeSoftnessPx: Double = 1.5

    /** Apply a Hann window before the display spectrum, suppressing the edge-discontinuity cross. */
    @Volatile var windowSpectrum: Boolean = true

    @Volatile var colormap: Int = Imgproc.COLORMAP_INFERNO

    @Volatile private var spec: FilterSpec = FilterSpec.PASS_ALL

    fun setFilter(spec: FilterSpec) {
        this.spec = spec
    }

    /** Padded spectrum grid for the current frame size, or null before the first frame. */
    @Volatile var grid: FrequencyGrid? = null
        private set

    private var frameWidth = 0
    private var frameHeight = 0

    // Per-frame working buffers.
    private val gray = Mat()
    private val planeU8 = Mat()
    private val planeF = Mat()
    private val padded = Mat()
    private val complex = Mat()
    private val inverse = Mat()
    private val re = Mat()
    private val im = Mat()
    private val magnitude = Mat()
    private val quadrant = Mat()
    private val displayU8 = Mat()
    private val colored = Mat()
    private val outPlanes = List(3) { Mat() }
    private val outPlanesU8 = List(3) { Mat() }
    private val alpha = Mat()
    private val hann = Mat()

    // Filter state.
    private var builtSpec: FilterSpec? = null
    private var builtMode: ColorMode? = null
    private var builtSoftness = Double.NaN
    private var maskBuffer = FloatArray(0)
    private val maskCentered = Mat()
    private val maskOverlay = Mat()
    private val filters = List(3) { Mat() }
    private var filterScales = doubleArrayOf(1.0)

    // Exponential moving averages of the display range, to stop flicker.
    private val spectrumRange = RunningRange(minSpan = 1e-3)

    // Floor of one 8-bit level keeps a flat image flat instead of stretching noise.
    private val imageRange = RunningRange(minSpan = 1.0)

    /** Centered-layout transmission of the reference-wavelength mask (CV_32F, grid size). */
    fun maskCentered(): Mat = maskCentered

    /**
     * Log-magnitude spectrum of the frame's luminance, fftshifted so DC is
     * at the center, colormapped, with blocked regions dimmed. [out] is
     * resized to the grid.
     */
    fun spectrum(rgba: Mat, out: Mat) {
        val g = prepare(rgba)
        Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY)
        gray.convertTo(planeF, CvType.CV_32F)
        pad(planeF)
        if (windowSpectrum) Core.multiply(padded, hann, padded)
        Core.dft(padded, complex, Core.DFT_COMPLEX_OUTPUT)
        splitComplex(complex)
        Core.magnitude(re, im, magnitude)
        Core.add(magnitude, ONE, magnitude)
        Core.log(magnitude, magnitude)
        fftShiftInPlace(magnitude)

        val mm = Core.minMaxLoc(magnitude)
        spectrumRange.update(mm.minVal, mm.maxVal)
        val lo = spectrumRange.lo
        val span = spectrumRange.span
        Core.subtract(magnitude, org.opencv.core.Scalar(lo), magnitude)
        Core.multiply(magnitude, maskOverlay, magnitude)
        magnitude.convertTo(displayU8, CvType.CV_8U, 255.0 / span)
        Imgproc.applyColorMap(displayU8, colored, colormap)
        Imgproc.cvtColor(colored, out, Imgproc.COLOR_BGR2RGBA)
        check(out.cols() == g.width && out.rows() == g.height)
    }

    /** Filtered frame at the input size. */
    fun filter(rgba: Mat, out: Mat) {
        prepare(rgba)
        val mode = builtMode!!
        val channels = if (mode == ColorMode.GRAY) 1 else 3
        for (c in 0 until channels) {
            if (mode == ColorMode.GRAY) {
                Imgproc.cvtColor(rgba, planeU8, Imgproc.COLOR_RGBA2GRAY)
            } else {
                Core.extractChannel(rgba, planeU8, c)
            }
            planeU8.convertTo(planeF, CvType.CV_32F)
            filterPlane(filters[if (filterScales.size == 1) 0 else c], outPlanes[c])
        }

        // One range for all channels so the color balance survives.
        var lo = Double.POSITIVE_INFINITY
        var hi = Double.NEGATIVE_INFINITY
        for (c in 0 until channels) {
            val mm = Core.minMaxLoc(outPlanes[c])
            lo = minOf(lo, mm.minVal)
            hi = maxOf(hi, mm.maxVal)
        }
        imageRange.update(lo, hi)
        val scale = 255.0 / imageRange.span
        val offset = -imageRange.lo * scale
        for (c in 0 until channels) outPlanes[c].convertTo(outPlanesU8[c], CvType.CV_8U, scale, offset)

        if (channels == 1) {
            Imgproc.cvtColor(outPlanesU8[0], out, Imgproc.COLOR_GRAY2RGBA)
        } else {
            if (alpha.rows() != frameHeight || alpha.cols() != frameWidth) {
                alpha.create(frameHeight, frameWidth, CvType.CV_8U)
                alpha.setTo(org.opencv.core.Scalar(255.0))
            }
            Core.merge(outPlanesU8 + alpha, out)
        }
    }

    /** Float result of the last [filter] call for channel [c], before display scaling. */
    internal fun lastFilteredPlane(c: Int): Mat = outPlanes[c]

    fun release() {
        listOf(
            gray, planeU8, planeF, padded, complex, inverse, re, im, magnitude, quadrant,
            displayU8, colored, alpha, hann, maskCentered, maskOverlay,
        ).forEach { it.release() }
        (outPlanes + outPlanesU8 + filters).forEach { it.release() }
    }

    /** Pads [planeF] into the grid, filters it, and writes the cropped float result to [dst]. */
    private fun filterPlane(filter: Mat, dst: Mat) {
        pad(planeF)
        Core.dft(padded, complex, Core.DFT_COMPLEX_OUTPUT)
        Core.mulSpectrums(complex, filter, complex, 0)
        val symmetric = builtSpec!!.isPointSymmetric
        if (symmetric) {
            // A point-symmetric real mask keeps the spectrum Hermitian, so the
            // result is real and the cheaper real-output inverse is exact.
            Core.idft(complex, inverse, Core.DFT_SCALE or Core.DFT_REAL_OUTPUT)
            val crop = inverse.submat(0, frameHeight, 0, frameWidth)
            if (outputMode == OutputMode.MAGNITUDE) Core.absdiff(crop, ZERO, dst) else crop.copyTo(dst)
            crop.release()
        } else {
            Core.idft(complex, inverse, Core.DFT_SCALE or Core.DFT_COMPLEX_OUTPUT)
            splitComplex(inverse)
            val reCrop = re.submat(0, frameHeight, 0, frameWidth)
            if (outputMode == OutputMode.MAGNITUDE) {
                val imCrop = im.submat(0, frameHeight, 0, frameWidth)
                Core.magnitude(reCrop, imCrop, dst)
                imCrop.release()
            } else {
                reCrop.copyTo(dst)
            }
            reCrop.release()
        }
    }

    private fun prepare(rgba: Mat): FrequencyGrid {
        require(rgba.type() == CvType.CV_8UC4) { "Expected CV_8UC4 RGBA, got ${CvType.typeToString(rgba.type())}" }
        if (rgba.cols() != frameWidth || rgba.rows() != frameHeight || grid == null) {
            frameWidth = rgba.cols()
            frameHeight = rgba.rows()
            val g = FrequencyGrid.forFrame(frameWidth, frameHeight)
            grid = g
            Imgproc.createHanningWindow(hann, Size(g.width.toDouble(), g.height.toDouble()), CvType.CV_32F)
            builtSpec = null
            spectrumRange.reset()
            imageRange.reset()
        }
        val mode = colorMode
        if (mode != builtMode) imageRange.reset()
        val s = spec
        val softness = edgeSoftnessPx
        if (s !== builtSpec || mode != builtMode || softness != builtSoftness) buildFilter(s, mode, softness)
        return grid!!
    }

    private fun buildFilter(s: FilterSpec, mode: ColorMode, softness: Double) {
        val g = grid!!
        if (maskBuffer.size != g.width * g.height) maskBuffer = FloatArray(g.width * g.height)
        filterScales = if (mode == ColorMode.RGB_OPTICAL) {
            val ref = wavelengths.referenceNm
            doubleArrayOf(wavelengths.redNm / ref, wavelengths.greenNm / ref, wavelengths.blueNm / ref)
        } else {
            doubleArrayOf(1.0)
        }

        val mask = Mat(g.height, g.width, CvType.CV_32F)
        val zeros = Mat.zeros(g.height, g.width, CvType.CV_32F)
        for ((i, scale) in filterScales.withIndex()) {
            MaskRasterizer.rasterize(s, g, maskBuffer, scale)
            mask.put(0, 0, maskBuffer)
            if (softness > 0) Imgproc.GaussianBlur(mask, mask, Size(0.0, 0.0), softness, softness, Core.BORDER_REFLECT_101)
            if (scale == 1.0) mask.copyTo(maskCentered)
            fftShiftInPlace(mask) // centered -> DFT layout (same swap for even dims)
            Core.merge(listOf(mask, zeros), filters[i])
        }
        mask.release()
        zeros.release()

        // Dim blocked regions in the spectrum view instead of hiding them.
        maskCentered.convertTo(maskOverlay, CvType.CV_32F, 1.0 - OVERLAY_FLOOR, OVERLAY_FLOOR)

        builtSpec = s
        builtMode = mode
        builtSoftness = softness
    }

    /** Copies [src] into the top-left of a grid-sized buffer, mirror-padding the rest. */
    private fun pad(src: Mat) {
        val g = grid!!
        Core.copyMakeBorder(src, padded, 0, g.height - src.rows(), 0, g.width - src.cols(), Core.BORDER_REFLECT_101)
    }

    private fun splitComplex(src: Mat) {
        Core.extractChannel(src, re, 0)
        Core.extractChannel(src, im, 1)
    }

    /** Swaps diagonal quadrants. Requires even dimensions, which [FrequencyGrid] guarantees. */
    private fun fftShiftInPlace(m: Mat) {
        val cx = m.cols() / 2
        val cy = m.rows() / 2
        val q0 = m.submat(0, cy, 0, cx)
        val q1 = m.submat(0, cy, cx, 2 * cx)
        val q2 = m.submat(cy, 2 * cy, 0, cx)
        val q3 = m.submat(cy, 2 * cy, cx, 2 * cx)
        q0.copyTo(quadrant); q3.copyTo(q0); quadrant.copyTo(q3)
        q1.copyTo(quadrant); q2.copyTo(q1); quadrant.copyTo(q2)
        q0.release(); q1.release(); q2.release(); q3.release()
    }

    private class RunningRange(private val minSpan: Double, private val alpha: Double = 0.2) {
        var lo = 0.0
            private set
        private var hi = 1.0
        private var primed = false

        val span: Double get() = (hi - lo).coerceAtLeast(minSpan)

        fun update(newLo: Double, newHi: Double) {
            if (!primed) {
                lo = newLo; hi = newHi; primed = true
            } else {
                lo += alpha * (newLo - lo)
                hi += alpha * (newHi - hi)
            }
        }

        fun reset() {
            primed = false
        }
    }

    private companion object {
        const val OVERLAY_FLOOR = 0.25
        val ONE = org.opencv.core.Scalar(1.0)
        val ZERO = org.opencv.core.Scalar(0.0)
    }
}
