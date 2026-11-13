package com.phillyberk.fourierfiltercamera.dsp

import org.junit.Assert.assertEquals
import org.junit.BeforeClass
import org.junit.Test
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.File

/**
 * Cross-platform parity fixtures.
 *
 * Writes `fixtures/dsp.json` with masks, grid sizes, unit conversions,
 * colormap tables, the Hann window and a blurred mask, all computed by
 * this module and desktop OpenCV. The iOS port's tests read the same file,
 * so a change to filter semantics fails on both platforms until the
 * fixture is regenerated and both sides agree.
 *
 * Normal runs check the committed file is current. Regenerate with
 * `./gradlew :dsp:test -PupdateFixtures`.
 */
class FixtureExportTest {
    companion object {
        @JvmStatic @BeforeClass fun loadOpenCv() {
            nu.pattern.OpenCV.loadLocally()
        }

        val SIZES = listOf(1, 2, 77, 101, 481, 482, 720, 1080, 1920, 4000)
        val GRIDS = listOf(FrequencyGrid(64, 48), FrequencyGrid(30, 20))
        val SCALES = listOf(1.0, 600.0 / 530.0, 465.0 / 530.0)

        val SPECS: List<Pair<String, FilterSpec>> = listOf(
            "empty" to FilterSpec.PASS_ALL,
            "box" to FilterSpec(FilterShape.Box(0.1, 0.2), passInside = true),
            "halfBox" to FilterSpec(FilterShape.HalfBox(0.13, 0.21), passInside = true),
            "circle" to FilterSpec(FilterShape.Circle(0.2), passInside = true),
            "circleBlock" to FilterSpec(FilterShape.Circle(0.137), passInside = false),
            "halfCircle" to FilterSpec(FilterShape.HalfCircle(0.23), passInside = true),
            "annulus" to FilterSpec(FilterShape.Annulus(0.3, 0.07), passInside = true),
            "annulusClamped" to FilterSpec(FilterShape.Annulus(0.05, 0.2), passInside = false),
            "freeform" to FilterSpec(
                FilterShape.Freeform(listOf(Dab(0.1, -0.05, 0.03), Dab(-0.31, 0.2, 0.05), Dab(0.49, 0.49, 0.04))),
                passInside = true,
            ),
            "freeformBlock" to FilterSpec(
                FilterShape.Freeform(listOf(Dab(0.0, 0.0, 0.06), Dab(0.21, 0.11, 0.02))),
                passInside = false,
            ),
        )

        val COLORMAPS = listOf(
            "inferno" to Imgproc.COLORMAP_INFERNO,
            "viridis" to Imgproc.COLORMAP_VIRIDIS,
            "magma" to Imgproc.COLORMAP_MAGMA,
            "turbo" to Imgproc.COLORMAP_TURBO,
            "jet" to Imgproc.COLORMAP_JET,
        )
    }

    @Test fun fixturesAreCurrent() {
        val dir = File(System.getProperty("fixtures.dir") ?: error("fixtures.dir not set by Gradle"))
        val file = File(dir, "dsp.json")
        val json = buildFixtures()
        if (System.getProperty("fixtures.update") == "true") {
            dir.mkdirs()
            file.writeText(json)
        }
        val committed = if (file.exists()) file.readText() else ""
        assertEquals(
            "fixtures/dsp.json is stale. Regenerate with ./gradlew :dsp:test -PupdateFixtures and commit it.",
            json,
            committed,
        )
    }

    private fun buildFixtures(): String {
        val out = StringBuilder()
        out.append("{\n")
        out.append("  \"version\": 1,\n")

        out.append("  \"nextFastEven\": [")
        out.append(SIZES.joinToString(", ") { "[$it, ${FftSize.nextFastEven(it)}]" })
        out.append("],\n")

        out.append("  \"masks\": [\n")
        val maskLines = mutableListOf<String>()
        for (g in GRIDS) for ((name, spec) in SPECS) for (s in SCALES) {
            val buf = FloatArray(g.width * g.height)
            MaskRasterizer.rasterize(spec, g, buf, s)
            maskLines += "    {\"name\": \"$name\", \"width\": ${g.width}, \"height\": ${g.height}, " +
                "\"scale\": $s, \"spec\": ${specJson(spec)}, \"runs\": [${runs(buf).joinToString(",")}]}"
        }
        out.append(maskLines.joinToString(",\n"))
        out.append("\n  ],\n")

        out.append("  \"physicalScale\": [\n")
        val physical = listOf(
            Triple(SensorOptics(0.001, 4000, 3000, 5.0, 1.8), 1280, 720),
            Triple(SensorOptics(0.0008, 8000, 6000, 6.86, 1.78), 1920, 1440),
            Triple(SensorOptics(0.0014, 4032, 3024, null, null), 640, 480),
        )
        out.append(
            physical.joinToString(",\n") { (o, w, h) ->
                val p = PhysicalScale(o, w, h)
                "    {\"pixelPitchMm\": ${o.pixelPitchMm}, \"activeWidthPx\": ${o.activeWidthPx}, " +
                    "\"activeHeightPx\": ${o.activeHeightPx}, \"focalLengthMm\": ${o.focalLengthMm}, " +
                    "\"fNumber\": ${o.fNumber}, \"bufferWidth\": $w, \"bufferHeight\": $h, " +
                    "\"binning\": ${p.binning}, \"pitchMm\": ${p.pitchMm}, " +
                    "\"cyclesPerMmAt0.1\": ${p.cyclesPerMm(0.1)}, \"cyclesPerDegreeAt0.1\": ${p.cyclesPerDegree(0.1)}, " +
                    "\"cutoff550\": ${p.diffractionCutoffCyclesPerMm(550.0)}}"
            },
        )
        out.append("\n  ],\n")

        out.append("  \"colormaps\": {\n")
        out.append(COLORMAPS.joinToString(",\n") { (name, code) -> "    \"$name\": [${colormapRgb(code).joinToString(",")}]" })
        out.append("\n  },\n")

        // createHanningWindow(cols=8, rows=6), row-major.
        val hann = Mat()
        Imgproc.createHanningWindow(hann, Size(8.0, 6.0), CvType.CV_32F)
        out.append("  \"hann\": {\"width\": 8, \"height\": 6, \"values\": [${floats(hann).joinToString(",")}]},\n")

        // Circle(0.2) on 64x48, Gaussian blurred as the engine does it.
        val g = FrequencyGrid(64, 48)
        val buf = FloatArray(g.width * g.height)
        MaskRasterizer.rasterize(FilterSpec(FilterShape.Circle(0.2), passInside = true), g, buf)
        val m = Mat(g.height, g.width, CvType.CV_32F)
        m.put(0, 0, buf)
        Imgproc.GaussianBlur(m, m, Size(0.0, 0.0), 1.5, 1.5, Core.BORDER_REFLECT_101)
        out.append("  \"blurredCircle\": {\"width\": 64, \"height\": 48, \"radius\": 0.2, \"sigma\": 1.5, \"values\": [${floats(m).joinToString(",")}]}\n")

        out.append("}\n")
        return out.toString()
    }

    private fun specJson(spec: FilterSpec): String {
        val shape = when (val s = spec.shape) {
            FilterShape.Empty -> "{\"type\": \"empty\"}"
            is FilterShape.Box -> "{\"type\": \"box\", \"halfU\": ${s.halfU}, \"halfV\": ${s.halfV}}"
            is FilterShape.HalfBox -> "{\"type\": \"halfBox\", \"halfU\": ${s.halfU}, \"halfV\": ${s.halfV}}"
            is FilterShape.Circle -> "{\"type\": \"circle\", \"radius\": ${s.radius}}"
            is FilterShape.HalfCircle -> "{\"type\": \"halfCircle\", \"radius\": ${s.radius}}"
            is FilterShape.Annulus -> "{\"type\": \"annulus\", \"outer\": ${s.outer}, \"width\": ${s.width}}"
            is FilterShape.Freeform -> "{\"type\": \"freeform\", \"dabs\": [" +
                s.dabs.joinToString(", ") { "[${it.u}, ${it.v}, ${it.radius}]" } + "]}"
        }
        return "{\"passInside\": ${spec.passInside}, \"shape\": $shape}"
    }

    /** Run lengths of a 0/1 mask, starting with a (possibly empty) run of zeros. */
    private fun runs(mask: FloatArray): List<Int> {
        val result = mutableListOf<Int>()
        var current = 0f
        var count = 0
        for (v in mask) {
            if (v == current) {
                count++
            } else {
                result += count
                current = v
                count = 1
            }
        }
        result += count
        return result
    }

    private fun colormapRgb(code: Int): List<Int> {
        val ramp = Mat(1, 256, CvType.CV_8U)
        ramp.put(0, 0, ByteArray(256) { it.toByte() })
        val bgr = Mat()
        Imgproc.applyColorMap(ramp, bgr, code)
        val bytes = ByteArray(256 * 3)
        bgr.get(0, 0, bytes)
        val rgb = mutableListOf<Int>()
        for (i in 0 until 256) {
            rgb += bytes[i * 3 + 2].toInt() and 0xff
            rgb += bytes[i * 3 + 1].toInt() and 0xff
            rgb += bytes[i * 3].toInt() and 0xff
        }
        return rgb
    }

    private fun floats(m: Mat): FloatArray = FloatArray(m.rows() * m.cols()).also { m.get(0, 0, it) }
}
