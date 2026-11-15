package com.smoothcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.util.Log
import android.util.Size
import android.view.SurfaceHolder
import java.util.Locale
import kotlin.math.abs

/**
 * One physical lens behind the phone's multi-lens camera, described by its field of view.
 * tanX / tanY: tangent of the half field of view along the sensor's long and short side.
 * offX / offY: where the optical centre sits relative to the image centre (same tangent units).
 * base: the zoom ratio at which this lens' full view fills the frame (main lens = 1, ultrawide ~0.5).
 */
class LensInfo(
    val id: String,
    val chars: CameraCharacteristics,
    val tanX: Float,
    val tanY: Float,
    val offX: Float,
    val offY: Float,
    val bgSize: Size?,
    val fromCalibration: Boolean,
) {
    var base = 1f
    var role = "lens"

    override fun toString(): String = String.format(
        Locale.US, "%s %s: %.2f× view (%s)", role, id, base,
        if (fromCalibration) "calibrated" else "from focal length"
    )
}

object LensModel {
    private const val TAG = "LensModel"

    /** Field of view from the lens calibration if the camera publishes it, else from focal length. */
    fun describe(id: String, c: CameraCharacteristics): LensInfo? {
        var tanX = 0f
        var tanY = 0f
        var offX = 0f
        var offY = 0f
        var calibrated = false
        val intr = c.get(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) // fx, fy, cx, cy, s
        val pre = c.get(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
        if (intr != null && intr.size >= 4 && pre != null && intr[0] > 0f && intr[1] > 0f) {
            val w = pre.width().toFloat()
            val h = pre.height().toFloat()
            val tx = (w / 2f) / intr[0]
            val ty = (h / 2f) / intr[1]
            if (tx in 0.05f..3f && ty in 0.05f..3f) {
                tanX = tx
                tanY = ty
                calibrated = true
                val ox = (intr[2] - w / 2f) / intr[0]
                val oy = (intr[3] - h / 2f) / intr[1]
                // Ignore optical-centre values that look like they use a different origin.
                if (abs(ox) < tx * 0.15f && abs(oy) < ty * 0.15f) {
                    offX = ox
                    offY = oy
                }
            }
        }
        if (!calibrated) {
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
            val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
            if (f <= 0f) return null
            tanX = size.width / 2f / f
            tanY = size.height / 2f / f
        }
        val sizes = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(SurfaceHolder::class.java)
        val bg = sizes?.filter { it.width * 3 == it.height * 4 && it.width <= 1440 }?.maxByOrNull { it.width }
        return LensInfo(id, c, tanX, tanY, offX, offY, bg, calibrated)
    }

    /**
     * All lenses behind the logical camera, with the ultrawide and main lens identified.
     * Zoom ratio 1.0 is the main lens by definition; if the zoom range goes below 1, the ultrawide's
     * view is 1/lower times wider, which tells us which lens is the main one.
     */
    fun build(manager: CameraManager, logical: CameraCharacteristics, zoomLower: Float): Triple<List<LensInfo>, LensInfo?, LensInfo?> {
        val lenses = try {
            logical.physicalCameraIds.mapNotNull { id ->
                try { describe(id, manager.getCameraCharacteristics(id)) } catch (e: Exception) { null }
            }
        } catch (e: Exception) {
            emptyList()
        }
        if (lenses.isEmpty()) return Triple(emptyList(), null, null)
        val widest = lenses.maxByOrNull { it.tanX }!!
        val main: LensInfo
        val uw: LensInfo?
        if (zoomLower < 0.95f && lenses.size >= 2) {
            uw = widest
            val target = widest.tanX * zoomLower
            main = lenses.filter { it !== widest }.minByOrNull { abs(it.tanX - target) } ?: widest
        } else {
            uw = null
            main = widest
        }
        for (l in lenses) {
            l.base = main.tanX / l.tanX
            l.role = when {
                l === main -> "main"
                l === uw -> "ultrawide"
                l.base > 1.5f -> "telephoto"
                else -> "lens"
            }
        }
        Log.i(TAG, "lenses: ${lenses.joinToString()}")
        return Triple(lenses, uw, main)
    }
}
