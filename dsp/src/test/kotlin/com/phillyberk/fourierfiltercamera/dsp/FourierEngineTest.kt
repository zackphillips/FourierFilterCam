package com.phillyberk.fourierfiltercamera.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfDouble
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

/**
 * Runs the real OpenCV pipeline on synthetic gratings. Frame sizes are
 * already 2-3-5 smooth and frequencies are whole cycles per frame, so there
 * is no padding and no spectral leakage to blur the assertions.
 */
class FourierEngineTest {
    companion object {
        const val W = 120
        const val H = 80

        @JvmStatic @BeforeClass fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
        }
    }

    /** RGBA frame with value(x, y) in every color channel. */
    private fun frame(value: (Int, Int) -> Double): Mat {
        val m = Mat(H, W, CvType.CV_8UC4)
        val buf = ByteArray(W * H * 4)
        for (y in 0 until H) for (x in 0 until W) {
            val v = value(x, y).roundToInt().coerceIn(0, 255).toByte()
            val i = (y * W + x) * 4
            buf[i] = v; buf[i + 1] = v; buf[i + 2] = v; buf[i + 3] = 255.toByte()
        }
        m.put(0, 0, buf)
        return m
    }

    /** Grating with [cycles] periods across the frame width. */
    private fun grating(cycles: Int, amplitude: Double) =
        { x: Int, _: Int -> amplitude * cos(2 * PI * cycles * x / W) }

    private fun channel(m: Mat, c: Int): Mat = Mat().also { Core.extractChannel(m, it, c) }

    private fun std(m: Mat): Double {
        val mean = MatOfDouble()
        val sd = MatOfDouble()
        Core.meanStdDev(m, mean, sd)
        return sd.toArray()[0]
    }

    private fun engine() = FourierEngine().apply { edgeSoftnessPx = 0.0 }

    @Test fun passAllReproducesTheInput() {
        val input = frame { x, y -> (x * 255.0 / (W - 1) + y) % 256 }
        val out = Mat()
        engine().filter(input, out)
        val diff = Mat()
        Core.absdiff(input, out, diff)
        assertTrue("max error ${Core.minMaxLoc(channel(diff, 0)).maxVal}", Core.minMaxLoc(channel(diff, 0)).maxVal <= 1.0)
    }

    @Test fun lowPassCircleRemovesTheFineGrating() {
        val coarse = grating(3, 60.0)
        val fine = grating(30, 60.0)
        val input = frame { x, y -> 128 + coarse(x, y) + fine(x, y) }
        val expected = frame { x, y -> 128 + coarse(x, y) }
        val e = engine()
        // 3 cycles/120 px = 0.025 cy/px passes, 30/120 = 0.25 cy/px does not.
        e.setFilter(FilterSpec(FilterShape.Circle(0.1), passInside = true))
        val out = Mat()
        e.filter(input, out)
        // Output is range-normalized; compare against the normalized expectation.
        val want = Mat()
        Core.normalize(channel(expected, 0), want, 0.0, 255.0, Core.NORM_MINMAX)
        val diff = Mat()
        Core.absdiff(channel(out, 0), want, diff)
        assertTrue("max error ${Core.minMaxLoc(diff).maxVal}", Core.minMaxLoc(diff).maxVal <= 3.0)
    }

    @Test fun spectrumPeakLandsAtTheGratingFrequency() {
        val e = engine().apply { windowSpectrum = false }
        val input = frame { x, y -> 128 + grating(12, 100.0)(x, y) }
        val out = Mat()
        e.spectrum(input, out)
        val g = e.grid!!
        assertEquals(W, out.cols())
        assertEquals(H, out.rows())
        // Brightest non-DC column on the center row must be DC +/- 12.
        val row = Mat()
        Core.extractChannel(out.row(g.centerY), row, 0) // red of inferno tracks magnitude monotonically
        var best = -1
        var bestVal = -1.0
        for (x in 0 until W) {
            if (x == g.centerX) continue
            val v = row.get(0, x)[0]
            if (v > bestVal) { bestVal = v; best = x }
        }
        assertEquals(12, kotlin.math.abs(best - g.centerX))
    }

    @Test fun opticalColorModeCutsRedBeforeGreen() {
        val e = engine().apply { colorMode = ColorMode.RGB_OPTICAL }
        // Grating at 0.15 cy/px. Circle radius 0.16 at 530 nm passes it for green
        // (and blue), but red sees cutoff 0.16 * 530/600 = 0.141 and loses it.
        val input = frame { x, y -> 128 + grating(18, 100.0)(x, y) }
        e.setFilter(FilterSpec(FilterShape.Circle(0.16), passInside = true))
        val out = Mat()
        e.filter(input, out)
        val red = std(channel(out, 0))
        val green = std(channel(out, 1))
        val blue = std(channel(out, 2))
        assertTrue("red std $red should be ~0", red < 1.0)
        assertTrue("green std $green should carry the grating", green > 50.0)
        assertTrue("blue std $blue should carry the grating", blue > 50.0)
    }

    @Test fun digitalColorModeTreatsChannelsAlike() {
        val e = engine().apply { colorMode = ColorMode.RGB }
        val input = frame { x, y -> 128 + grating(18, 100.0)(x, y) }
        e.setFilter(FilterSpec(FilterShape.Circle(0.16), passInside = true))
        val out = Mat()
        e.filter(input, out)
        assertEquals(std(channel(out, 1)), std(channel(out, 0)), 0.5)
    }

    @Test fun singleSidebandFilterGivesFlatMagnitudeAndOscillatingRealPart() {
        // Pass only the +f lobe of cos(2 pi f x), leaving exp(i 2 pi f x) / 2.
        val f = 10.0 / W
        val spec = FilterSpec(FilterShape.Freeform(listOf(Dab(f, 0.0, 0.02))), passInside = true)
        val input = frame { x, y -> 128 + grating(10, 100.0)(x, y) }

        val out = Mat()
        val real = engine().apply { setFilter(spec); outputMode = OutputMode.REAL }
        real.filter(input, out)
        val mag = engine().apply { setFilter(spec); outputMode = OutputMode.MAGNITUDE }
        mag.filter(input, out)

        // The +f lobe of 100 cos() carries amplitude 50.
        val realRange = Core.minMaxLoc(real.lastFilteredPlane(0))
        assertEquals(50.0, realRange.maxVal, 1.0)
        assertEquals(-50.0, realRange.minVal, 1.0)
        val magRange = Core.minMaxLoc(mag.lastFilteredPlane(0))
        assertEquals(50.0, magRange.minVal, 1.0)
        assertEquals(50.0, magRange.maxVal, 1.0)
    }

    @Test fun oddFrameSizesArePaddedAndCroppedBack() {
        val e = engine()
        val input = Mat(77, 101, CvType.CV_8UC4, org.opencv.core.Scalar(10.0, 20.0, 30.0, 255.0))
        val out = Mat()
        e.filter(input, out)
        assertEquals(101, out.cols())
        assertEquals(77, out.rows())
        assertEquals(FrequencyGrid(108, 80), e.grid)
    }
}
