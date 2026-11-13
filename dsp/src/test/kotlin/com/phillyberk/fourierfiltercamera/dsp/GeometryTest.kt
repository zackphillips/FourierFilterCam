package com.phillyberk.fourierfiltercamera.dsp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class GeometryTest {
    @Test fun nextFastEvenPicksSmallEven235Numbers() {
        assertEquals(2, FftSize.nextFastEven(1))
        assertEquals(486, FftSize.nextFastEven(481)) // 2 * 3^5
        assertEquals(720, FftSize.nextFastEven(720))
        assertEquals(1080, FftSize.nextFastEven(1080))
        assertEquals(1920, FftSize.nextFastEven(1920))
        assertEquals(486, FftSize.nextFastEven(482)) // 482 = 2 * 241
    }

    @Test fun gridMapsCenterToDcAndEdgeToNyquist() {
        val g = FrequencyGrid(640, 480)
        assertEquals(0.0, g.u(320.0), 0.0)
        assertEquals(0.0, g.v(240.0), 0.0)
        assertEquals(-0.5, g.u(0.0), 0.0)
        assertEquals(-0.5, g.v(0.0), 0.0)
        assertEquals(100.0, g.x(g.u(100.0)), 1e-9)
    }

    @Test fun circleAreaMatchesPiRSquared() {
        val g = FrequencyGrid(400, 300)
        val out = FloatArray(g.width * g.height)
        val r = 0.2
        MaskRasterizer.rasterize(FilterSpec(FilterShape.Circle(r), passInside = true), g, out)
        val expected = PI * r * r * g.width * g.height
        assertEquals(expected, out.sum().toDouble(), expected * 0.01)
    }

    @Test fun blockingInvertsTransmission() {
        val g = FrequencyGrid(64, 64)
        val pass = FloatArray(64 * 64)
        val block = FloatArray(64 * 64)
        MaskRasterizer.rasterize(FilterSpec(FilterShape.Box(0.1, 0.2), passInside = true), g, pass)
        MaskRasterizer.rasterize(FilterSpec(FilterShape.Box(0.1, 0.2), passInside = false), g, block)
        for (i in pass.indices) assertEquals(1f, pass[i] + block[i], 0f)
    }

    @Test fun frequencyScaleShrinksTheShapeForLongerWavelengths() {
        val g = FrequencyGrid(400, 400)
        val ref = FloatArray(g.width * g.height)
        val red = FloatArray(g.width * g.height)
        val spec = FilterSpec(FilterShape.Circle(0.2), passInside = true)
        MaskRasterizer.rasterize(spec, g, ref, 1.0)
        MaskRasterizer.rasterize(spec, g, red, 600.0 / 530.0)
        val ratio = red.sum() / ref.sum()
        val expected = (530.0 / 600.0).let { it * it }
        assertEquals(expected, ratio.toDouble(), 0.01)
    }

    @Test fun freeformBoundingBoxPaintingMatchesBruteForce() {
        val g = FrequencyGrid(120, 80)
        val shape = FilterShape.Freeform(listOf(Dab(0.1, -0.05, 0.03), Dab(-0.31, 0.2, 0.05), Dab(0.49, 0.49, 0.04)))
        for (pass in listOf(true, false)) for (s in listOf(1.0, 1.13, 0.88)) {
            val spec = FilterSpec(shape, pass)
            val fast = FloatArray(g.width * g.height)
            MaskRasterizer.rasterize(spec, g, fast, s)
            for (y in 0 until g.height) for (x in 0 until g.width) {
                val want = spec.transmission(g.u(x.toDouble()) * s, g.v(y.toDouble()) * s)
                assertEquals("x=$x y=$y s=$s pass=$pass", want, fast[y * g.width + x], 0f)
            }
        }
    }

    @Test fun halfShapesAreNotPointSymmetric() {
        assertFalse(FilterShape.HalfBox(0.1, 0.1).isPointSymmetric)
        assertFalse(FilterShape.HalfCircle(0.1).isPointSymmetric)
        assertTrue(FilterShape.Annulus(0.2, 0.05).isPointSymmetric)
        assertTrue(FilterShape.Freeform(emptyList()).isPointSymmetric)
        assertFalse(FilterShape.Freeform(listOf(Dab(0.1, 0.0, 0.01))).isPointSymmetric)
    }

    @Test fun physicalScaleUsesTheUncroppedAxis() {
        // 4000x3000 active array at 1.0 um, streamed at 1280x720 (16:9 crop of 4:3).
        val optics = SensorOptics(pixelPitchMm = 0.001, activeWidthPx = 4000, activeHeightPx = 3000, focalLengthMm = 5.0, fNumber = 1.8)
        val p = PhysicalScale(optics, 1280, 720)
        assertEquals(3.125, p.binning, 1e-12)
        assertEquals(0.003125, p.pitchMm, 1e-12)
        assertEquals(160.0, p.nyquistCyclesPerMm, 1e-9)
        assertEquals(32.0, p.cyclesPerMm(0.1), 1e-9)
        // 0.1 cy/px * 5 mm / 0.003125 mm = 160 cy/rad = 2.7925 cy/deg
        assertEquals(160.0 * PI / 180.0, p.cyclesPerDegree(0.1)!!, 1e-9)
        // 1 / (550e-6 mm * 1.8) = 1010.1 cy/mm
        assertEquals(1010.10, p.diffractionCutoffCyclesPerMm(550.0)!!, 0.01)
        assertEquals(0.1, p.cyclesPerPixel(p.cyclesPerMm(0.1)), 1e-12)
    }
}
