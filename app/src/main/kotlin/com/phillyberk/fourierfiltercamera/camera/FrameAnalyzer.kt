package com.phillyberk.fourierfiltercamera.camera

import android.graphics.Bitmap
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.phillyberk.fourierfiltercamera.dsp.FourierEngine
import com.phillyberk.fourierfiltercamera.dsp.FrequencyGrid
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/** What the analyzer needs from UI state for each frame. */
data class FrameSettings(val showSpectrum: Boolean, val mirror: Boolean)

/** Frame geometry reported back to the UI for touch mapping and units. */
data class FrameGeometry(
    val grid: FrequencyGrid,
    val frameWidth: Int,
    val frameHeight: Int,
    /** Buffer size in sensor orientation, before display rotation. */
    val bufferWidth: Int,
    val bufferHeight: Int,
)

/**
 * Turns RGBA camera frames into either the filtered image or its
 * spectrum, rotated to display orientation. Runs on a single background
 * thread; all Mats are reused across frames.
 */
class FrameAnalyzer(
    private val engine: FourierEngine,
    private val renderer: FrameRenderer,
    private val settings: () -> FrameSettings,
    private val onGeometry: (FrameGeometry) -> Unit,
    private val onCapture: (Bitmap) -> Unit,
) : ImageAnalysis.Analyzer {
    private val captureRequested = AtomicBoolean(false)
    private var scratch: ByteBuffer? = null
    private val rotated = Mat()
    private val display = Mat()
    private val capture = Mat()
    private var lastGeometry: FrameGeometry? = null

    fun requestCapture() = captureRequested.set(true)

    override fun analyze(image: ImageProxy) {
        image.use { processFrame(it) }
    }

    private fun processFrame(image: ImageProxy) {
        val plane = image.planes[0]
        val stride = plane.rowStride
        val buffer = plane.buffer.rewind() as ByteBuffer
        // The last row of a strided buffer can be shorter than the stride, and
        // OpenCV wants rows * stride bytes, so copy into a buffer that has them.
        val needed = image.height * stride
        val data = if (buffer.capacity() >= needed) {
            buffer
        } else {
            val s = scratch?.takeIf { it.capacity() >= needed }
                ?: ByteBuffer.allocateDirect(needed).order(ByteOrder.nativeOrder()).also { scratch = it }
            s.clear()
            s.put(buffer)
            s.rewind()
            s
        }
        val full = Mat(image.height, image.width, CvType.CV_8UC4, data, stride.toLong())
        val crop = image.cropRect
        val src = full.submat(crop.top, crop.bottom, crop.left, crop.right)

        when (image.imageInfo.rotationDegrees) {
            90 -> Core.rotate(src, rotated, Core.ROTATE_90_CLOCKWISE)
            180 -> Core.rotate(src, rotated, Core.ROTATE_180)
            270 -> Core.rotate(src, rotated, Core.ROTATE_90_COUNTERCLOCKWISE)
            else -> src.copyTo(rotated)
        }
        src.release()
        full.release()

        val s = settings()
        if (s.mirror) Core.flip(rotated, rotated, 1)

        if (s.showSpectrum) engine.spectrum(rotated, display) else engine.filter(rotated, display)

        engine.grid?.let { grid ->
            val geometry = FrameGeometry(grid, rotated.cols(), rotated.rows(), crop.width(), crop.height())
            if (geometry != lastGeometry) {
                lastGeometry = geometry
                onGeometry(geometry)
            }
        }

        if (captureRequested.getAndSet(false)) {
            val filtered = if (s.showSpectrum) capture.also { engine.filter(rotated, it) } else display
            val bmp = Bitmap.createBitmap(filtered.cols(), filtered.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(filtered, bmp)
            onCapture(bmp)
        }

        renderer.draw(display)
    }

    fun release() {
        rotated.release()
        display.release()
        capture.release()
    }
}
