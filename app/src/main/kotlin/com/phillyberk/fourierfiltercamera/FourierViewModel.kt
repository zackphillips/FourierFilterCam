package com.phillyberk.fourierfiltercamera

import android.app.Application
import android.graphics.Bitmap
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phillyberk.fourierfiltercamera.camera.CaptureSaver
import com.phillyberk.fourierfiltercamera.camera.FrameAnalyzer
import com.phillyberk.fourierfiltercamera.camera.FrameGeometry
import com.phillyberk.fourierfiltercamera.camera.FrameRenderer
import com.phillyberk.fourierfiltercamera.camera.FrameSettings
import com.phillyberk.fourierfiltercamera.dsp.ColorMode
import com.phillyberk.fourierfiltercamera.dsp.Dab
import com.phillyberk.fourierfiltercamera.dsp.FilterShape
import com.phillyberk.fourierfiltercamera.dsp.FilterSpec
import com.phillyberk.fourierfiltercamera.dsp.FilterType
import com.phillyberk.fourierfiltercamera.dsp.FourierEngine
import com.phillyberk.fourierfiltercamera.dsp.OutputMode
import com.phillyberk.fourierfiltercamera.dsp.PhysicalScale
import com.phillyberk.fourierfiltercamera.dsp.SensorOptics
import com.phillyberk.fourierfiltercamera.ui.Colormap
import com.phillyberk.fourierfiltercamera.ui.describe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors
import kotlin.math.hypot

enum class Resolution(val label: String, val width: Int, val height: Int, val is16x9: Boolean) {
    VGA("640 × 480", 640, 480, is16x9 = false),
    HD("1280 × 720", 1280, 720, is16x9 = true),
    FHD("1920 × 1080", 1920, 1080, is16x9 = true),
}

enum class AxisUnits(val label: String) {
    CYCLES_PER_PIXEL("cycles/pixel"),
    CYCLES_PER_MM("cycles/mm (sensor)"),
    CYCLES_PER_DEGREE("cycles/degree (field)"),
}

data class UiState(
    val showSpectrum: Boolean = false,
    val filterType: FilterType = FilterType.CIRCLE,
    val spec: FilterSpec = FilterSpec.PASS_ALL,
    val colorMode: ColorMode = ColorMode.GRAY,
    val outputMode: OutputMode = OutputMode.REAL,
    val resolution: Resolution = Resolution.HD,
    val lensFacing: Int = CameraSelector.LENS_FACING_BACK,
    val axisUnits: AxisUnits = AxisUnits.CYCLES_PER_MM,
    val colormap: Colormap = Colormap.INFERNO,
    val windowSpectrum: Boolean = true,
    val edgeSoftnessPx: Float = 1.5f,
    /** Last touched frequency in cycles/pixel, for the readout. */
    val cursor: Pair<Double, Double>? = null,
    val geometry: FrameGeometry? = null,
    val optics: SensorOptics? = null,
    val showSettings: Boolean = false,
) {
    val physicalScale: PhysicalScale?
        get() {
            val o = optics ?: return null
            val g = geometry ?: return null
            return PhysicalScale(o, g.bufferWidth, g.bufferHeight)
        }
}

class FourierViewModel(app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages = _messages.receiveAsFlow()

    val engine = FourierEngine()
    val renderer = FrameRenderer()
    val analysisExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "fourier-analyzer") }

    val analyzer = FrameAnalyzer(
        engine = engine,
        renderer = renderer,
        settings = {
            val s = _state.value
            FrameSettings(showSpectrum = s.showSpectrum, mirror = s.lensFacing == CameraSelector.LENS_FACING_FRONT)
        },
        onGeometry = { g -> _state.update { it.copy(geometry = g) } },
        onCapture = ::saveCapture,
    )

    init {
        applyToEngine(_state.value)
    }

    private fun mutate(block: (UiState) -> UiState) {
        _state.update(block)
        applyToEngine(_state.value)
    }

    private fun applyToEngine(s: UiState) {
        engine.setFilter(s.spec)
        engine.colorMode = s.colorMode
        engine.outputMode = s.outputMode
        engine.windowSpectrum = s.windowSpectrum
        engine.edgeSoftnessPx = s.edgeSoftnessPx.toDouble()
        engine.colormap = s.colormap.cvCode
    }

    fun setShowSpectrum(show: Boolean) = mutate { it.copy(showSpectrum = show) }

    fun selectFilterType(type: FilterType) = mutate {
        val (u, v) = DEFAULT_TOUCH
        val shape = type.shapeThrough(u, v, ANNULUS_WIDTH)
        it.copy(filterType = type, spec = FilterSpec(shape, type.passInsideByDefault), cursor = if (type == FilterType.FREEFORM) null else DEFAULT_TOUCH)
    }

    fun invert() = mutate { it.copy(spec = FilterSpec(it.spec.shape, !it.spec.passInside)) }

    fun clearFilter() = mutate {
        val shape = if (it.filterType == FilterType.FREEFORM) FilterShape.Freeform(emptyList()) else FilterShape.Empty
        it.copy(spec = FilterSpec(shape, passInside = false), cursor = null)
    }

    /** A touch on the spectrum at frequency (u, v) cycles/pixel. */
    fun onSpectrumTouch(u: Double, v: Double) = mutate { s ->
        val spec = when (s.filterType) {
            FilterType.FREEFORM -> {
                val current = s.spec.shape as? FilterShape.Freeform ?: FilterShape.Freeform(emptyList())
                val last = current.dabs.lastOrNull()
                // Skip dabs that would mostly overlap the previous one.
                if (last != null && hypot(u - last.u, v - last.v) < BRUSH_RADIUS / 3) return@mutate s
                if (current.dabs.size >= MAX_DABS) return@mutate s
                val passInside = if (s.spec.shape is FilterShape.Freeform) s.spec.passInside else FilterType.FREEFORM.passInsideByDefault
                FilterSpec(current.plus(Dab(u, v, BRUSH_RADIUS)), passInside)
            }
            else -> FilterSpec(s.filterType.shapeThrough(u, v, ANNULUS_WIDTH), s.spec.passInside)
        }
        s.copy(spec = spec, cursor = u to v)
    }

    fun setColorMode(mode: ColorMode) = mutate { it.copy(colorMode = mode) }
    fun setOutputMode(mode: OutputMode) = mutate { it.copy(outputMode = mode) }
    fun setResolution(r: Resolution) = mutate { it.copy(resolution = r) }
    fun setAxisUnits(u: AxisUnits) = mutate { it.copy(axisUnits = u) }
    fun setColormap(c: Colormap) = mutate { it.copy(colormap = c) }
    fun setWindowSpectrum(on: Boolean) = mutate { it.copy(windowSpectrum = on) }
    fun setEdgeSoftness(px: Float) = mutate { it.copy(edgeSoftnessPx = px) }
    fun setShowSettings(show: Boolean) = mutate { it.copy(showSettings = show) }

    fun flipCamera() = mutate {
        val next = if (it.lensFacing == CameraSelector.LENS_FACING_BACK) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK
        it.copy(lensFacing = next, optics = null)
    }

    fun onCameraBound(optics: SensorOptics?) = _state.update { it.copy(optics = optics) }

    fun onCameraError(message: String) {
        _messages.trySend(message)
    }

    fun capture() = analyzer.requestCapture()

    private fun saveCapture(bitmap: Bitmap) {
        val s = _state.value
        val description = describe(s)
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { CaptureSaver.save(getApplication(), bitmap, description) }
            }
            bitmap.recycle()
            result
                .onSuccess { _messages.send("Saved to Pictures/${CaptureSaver.ALBUM}") }
                .onFailure {
                    Log.e(TAG, "Capture failed", it)
                    _messages.send("Could not save: ${it.message}")
                }
        }
    }

    override fun onCleared() {
        // Release on the analyzer thread so no frame is mid-flight.
        analysisExecutor.execute {
            analyzer.release()
            engine.release()
        }
        analysisExecutor.shutdown()
    }

    companion object {
        private const val TAG = "FourierViewModel"

        /** Freeform brush radius, cycles/pixel. */
        const val BRUSH_RADIUS = 0.02

        /** Radial width of the annulus, cycles/pixel. */
        const val ANNULUS_WIDTH = 0.02
        const val MAX_DABS = 4000

        /** Where shape filters start when picked, cycles/pixel. */
        val DEFAULT_TOUCH = 0.08 to 0.06
    }
}
