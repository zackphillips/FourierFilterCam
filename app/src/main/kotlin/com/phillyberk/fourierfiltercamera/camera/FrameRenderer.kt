package com.phillyberk.fourierfiltercamera.camera

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.SurfaceHolder
import org.opencv.android.Utils
import org.opencv.core.Mat

/** Largest rect with the source aspect ratio that fits, centered, in the destination. */
fun fitRect(srcWidth: Int, srcHeight: Int, dstWidth: Float, dstHeight: Float): RectF {
    val scale = minOf(dstWidth / srcWidth, dstHeight / srcHeight)
    val w = srcWidth * scale
    val h = srcHeight * scale
    val left = (dstWidth - w) / 2f
    val top = (dstHeight - h) / 2f
    return RectF(left, top, left + w, top + h)
}

/**
 * Draws processed frames onto a SurfaceView from the analyzer thread.
 * Drawing and surface teardown share a lock, so the surface is never
 * destroyed mid-frame.
 */
class FrameRenderer : SurfaceHolder.Callback {
    private val lock = Any()
    private var holder: SurfaceHolder? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun draw(rgba: Mat) = synchronized(lock) {
        val h = holder ?: return
        val bmp = bitmap?.takeIf { it.width == rgba.cols() && it.height == rgba.rows() }
            ?: Bitmap.createBitmap(rgba.cols(), rgba.rows(), Bitmap.Config.ARGB_8888).also {
                bitmap?.recycle()
                bitmap = it
            }
        Utils.matToBitmap(rgba, bmp)
        val canvas = runCatching { h.lockHardwareCanvas() }.getOrNull() ?: return
        try {
            canvas.drawColor(Color.BLACK)
            canvas.drawBitmap(bmp, null, fitRect(bmp.width, bmp.height, surfaceWidth.toFloat(), surfaceHeight.toFloat()), paint)
        } finally {
            h.unlockCanvasAndPost(canvas)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) = Unit

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = synchronized(lock) {
        this.holder = holder
        surfaceWidth = width
        surfaceHeight = height
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) = synchronized(lock) {
        this.holder = null
    }
}
