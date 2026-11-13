package com.phillyberk.fourierfiltercamera.camera

import android.hardware.camera2.CameraCharacteristics
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraInfo
import com.phillyberk.fourierfiltercamera.dsp.SensorOptics

/**
 * Reads pixel pitch, focal length and f-number from Camera2. Returns null
 * when the device does not report the sensor size, in which case the UI
 * falls back to cycles/pixel.
 */
@OptIn(ExperimentalCamera2Interop::class)
fun sensorOptics(info: CameraInfo): SensorOptics? {
    val c2 = Camera2CameraInfo.from(info)
    val physical = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val pixelArray = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null
    val active = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return null
    if (pixelArray.width <= 0 || physical.width <= 0f) return null
    return SensorOptics(
        pixelPitchMm = physical.width.toDouble() / pixelArray.width,
        activeWidthPx = active.width(),
        activeHeightPx = active.height(),
        focalLengthMm = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
            ?.firstOrNull()?.toDouble(),
        fNumber = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
            ?.firstOrNull()?.toDouble(),
    )
}
