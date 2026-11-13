package com.phillyberk.fourierfiltercamera.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.test.core.app.ActivityScenario
import com.github.takahirom.roborazzi.captureRoboImage
import com.phillyberk.fourierfiltercamera.UiState
import com.phillyberk.fourierfiltercamera.camera.FrameGeometry
import com.phillyberk.fourierfiltercamera.dsp.FilterShape
import com.phillyberk.fourierfiltercamera.dsp.FilterSpec
import com.phillyberk.fourierfiltercamera.dsp.FilterType
import com.phillyberk.fourierfiltercamera.dsp.FrequencyGrid
import com.phillyberk.fourierfiltercamera.dsp.SensorOptics
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the camera screen at real-world window sizes and checks that
 * every control is on screen (or reachable by scrolling), large enough to
 * touch, and not overlapping another control. Screenshots are written to
 * app/build/outputs/roborazzi when run with -Proborazzi.test.record=true.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [36])
class ScreenLayoutTest(private val window: Window, private val spectrum: Boolean) {

    data class Window(val name: String, val widthDp: Int, val heightDp: Int, val density: String = "xxhdpi", val fontScale: Float = 1f) {
        val qualifiers get() = "w${widthDp}dp-h${heightDp}dp-${if (widthDp > heightDp) "land" else "port"}-$density"
        override fun toString() = name
    }

    companion object {
        /** One representative per layout class, plus the hardest edge cases. */
        private val WINDOWS = listOf(
            Window("small-phone", 320, 568, "xhdpi"),
            Window("phone", 411, 914, "xxxhdpi"),
            Window("phone-landscape", 914, 411, "xxxhdpi"),
            Window("fold-inner", 673, 841),
            Window("tablet-portrait", 800, 1280, "xhdpi"),
            Window("tablet-landscape", 1280, 800, "xhdpi"),
            // Multi-window pane at Android's 220dp minimum
            Window("split-minimum", 320, 220),
            // Largest accessibility font scale on a common phone
            Window("phone-font-2x", 360, 640, fontScale = 2f),
        )

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}-spectrum={1}")
        fun params(): List<Array<Any>> = WINDOWS.flatMap { w -> listOf(arrayOf<Any>(w, true), arrayOf<Any>(w, false)) }

        private val CONTROLS = listOf(
            "Filter type: Circle",
            "Invert filter",
            "Clear filter",
            "Settings",
            "Switch camera",
            "Save filtered image",
        )
    }

    @get:Rule val compose = createEmptyComposeRule()

    private var scenario: ActivityScenario<ComponentActivity>? = null

    @After fun close() {
        scenario?.close()
    }

    private fun state(): UiState {
        val portrait = window.heightDp > window.widthDp
        val (fw, fh) = if (portrait) 720 to 1280 else 1280 to 720
        return UiState(
            showSpectrum = spectrum,
            filterType = FilterType.CIRCLE,
            spec = FilterSpec(FilterShape.Circle(0.1), passInside = true),
            cursor = 0.08 to 0.06,
            geometry = FrameGeometry(FrequencyGrid.forFrame(fw, fh), fw, fh, 1280, 720),
            optics = SensorOptics(pixelPitchMm = 0.0008, activeWidthPx = 4000, activeHeightPx = 3000, focalLengthMm = 6.0, fNumber = 1.8),
        )
    }

    @Test fun controlsFitAndDoNotOverlap() {
        RuntimeEnvironment.setQualifiers(window.qualifiers)
        RuntimeEnvironment.setFontScale(window.fontScale)
        val noop = ScreenActions({}, {}, {}, {}, {}, {}, {}, { _, _ -> })
        scenario = ActivityScenario.launch(ComponentActivity::class.java).onActivity {
            it.setContent { MaterialTheme(colorScheme = darkColorScheme()) { FourierScreen(state(), renderer = null, actions = noop) } }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("build/outputs/roborazzi/${window.name}-${if (spectrum) "spectrum" else "image"}.png")

        // The shutter must be visible without scrolling at every size.
        compose.onNodeWithContentDescription("Save filtered image").assertIsDisplayed()

        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val density = RuntimeEnvironment.getApplication().resources.displayMetrics.density
        val minTouchPx = 48 * density - 1

        val nodes: Map<String, SemanticsNodeInteraction> =
            CONTROLS.associateWith { compose.onNodeWithContentDescription(it) } +
                mapOf(
                    "Image tab" to compose.onNodeWithText("Image"),
                    "Spectrum tab" to compose.onNodeWithText("Spectrum"),
                    "Readout" to compose.onNodeWithText("Passing inside circle", substring = true),
                )

        val bounds = mutableMapOf<String, Rect>()
        for ((name, node) in nodes) {
            // In the shortest panes the action rail scrolls; reachable counts as present.
            runCatching { node.performScrollTo() }
            node.assertExists(name)
            val sem = node.fetchSemanticsNode()
            val b = sem.boundsInRoot
            assertTrue("$name ($b) is outside the window ($root) at ${window.name}", b.left >= root.left - 1 && b.top >= root.top - 1 && b.right <= root.right + 1 && b.bottom <= root.bottom + 1)
            assertTrue("$name has zero size at ${window.name}", b.width > 0 && b.height > 0)
            if (name != "Readout") {
                val t = sem.touchBoundsInRoot
                assertTrue("$name touch target ${t.width}x${t.height}px < 48dp at ${window.name}", t.width >= minTouchPx && t.height >= minTouchPx)
            }
            bounds[name] = b
        }

        if (!railScrolls()) {
            val names = bounds.keys.toList()
            for (i in names.indices) for (j in i + 1 until names.size) {
                val a = bounds.getValue(names[i])
                val b = bounds.getValue(names[j])
                val overlap = a.intersect(b)
                assertTrue(
                    "${names[i]} overlaps ${names[j]} at ${window.name}: $a vs $b",
                    // Segmented buttons share a 1dp border by design.
                    overlap.width <= 1.5f * density || overlap.height <= 1.5f * density,
                )
            }
        }

    }

    /** When the rail scrolls, positions are measured at different scroll offsets and can't be compared. */
    private fun railScrolls() = window.widthDp > window.heightDp && window.heightDp < 296
}
