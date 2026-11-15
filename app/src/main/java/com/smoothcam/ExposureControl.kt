package com.smoothcam

import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.os.Build
import android.util.Range
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Manual exposure state (EV compensation, shutter speed, ISO) and how to turn it into
 * capture-request settings.
 *
 *  - Shutter only: on Android 16 cameras with shutter-priority AE, ISO keeps adapting.
 *    Otherwise ISO is computed once from the last auto-exposure reading and held.
 *  - ISO only: same idea with ISO-priority AE (Android 16), else shutter is derived once.
 *  - Both: fully manual.
 *  - EV compensation works whenever auto-exposure is still running (auto or a priority mode).
 */
class ExposureControl(chars: CameraCharacteristics) {

    sealed class Shutter {
        object Auto : Shutter()
        /** 180° shutter: half the frame time, recalculated for the current frame rate. */
        object Angle180 : Shutter()
        data class Fixed(val ns: Long) : Shutter()
    }

    val isoRange: Range<Int> = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: Range(50, 3200)
    val expRange: Range<Long> = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
        ?: Range(100_000L, 1_000_000_000L)
    val evRange: Range<Int> = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE) ?: Range(0, 0)
    val evStep: Float = chars.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP)?.toFloat() ?: 0f

    private val priorityModes: IntArray =
        if (Build.VERSION.SDK_INT >= 36) {
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES) ?: intArrayOf()
        } else {
            intArrayOf()
        }
    val shutterPriority = Build.VERSION.SDK_INT >= 36 &&
        priorityModes.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
    val isoPriority = Build.VERSION.SDK_INT >= 36 &&
        priorityModes.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
    private val boostRange: Range<Int>? = chars.get(CameraCharacteristics.CONTROL_POST_RAW_SENSITIVITY_BOOST_RANGE)

    @Volatile var evIndex = 0
    @Volatile var shutter: Shutter = Shutter.Auto
    @Volatile var iso: Int? = null

    // Last values chosen by fully-automatic exposure, used to derive the other value when the
    // camera has no priority mode.
    @Volatile var lastAutoExposureNs = 0L
    @Volatile var lastAutoIso = 0
    @Volatile var lastAutoBoost = 100
    @Volatile private var frozenLight = 0.0 // exposure time x ISO at the moment manual mode began
    @Volatile private var frozenBoost = 100

    val isFullyAuto: Boolean get() = shutter == Shutter.Auto && iso == null

    /** True when auto-exposure still runs, so EV compensation has an effect. */
    val evActive: Boolean
        get() = when {
            shutter != Shutter.Auto && iso != null -> false
            shutter != Shutter.Auto -> shutterPriority
            iso != null -> isoPriority
            else -> true
        }

    /** Call before switching from fully-auto to any manual value, to remember the scene brightness. */
    fun freezeIfLeavingAuto() {
        if (!isFullyAuto) return
        val e = if (lastAutoExposureNs > 0) lastAutoExposureNs else 16_666_666L
        val i = if (lastAutoIso > 0) lastAutoIso else 400
        frozenLight = e.toDouble() * i
        frozenBoost = lastAutoBoost
    }

    fun shutterNs(fps: Int): Long? = when (val s = shutter) {
        Shutter.Auto -> null
        Shutter.Angle180 -> 1_000_000_000L / (2L * fps)
        is Shutter.Fixed -> s.ns
    }

    /**
     * @param fps frame rate used for the 180° value
     * @param frameNs frame duration to keep (video: 1/fps; photo preview: 1/30 s)
     * @param video if true, exposure can't be longer than one frame
     */
    fun apply(b: CaptureRequest.Builder, fps: Int, frameNs: Long, video: Boolean) {
        val maxExp = if (video) frameNs else expRange.upper
        val s = shutterNs(fps)?.coerceIn(expRange.lower, max(expRange.lower, minOf(expRange.upper, maxExp)))
        val i = iso?.coerceIn(isoRange.lower, isoRange.upper)
        val light = if (frozenLight > 0) frozenLight else 16_666_666.0 * 400

        when {
            s != null && i != null -> manual(b, s, i, frameNs)
            s != null && shutterPriority -> {
                aeOn(b, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY)
                b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, s)
            }
            s != null -> manual(b, s, (light / s).roundToInt().coerceIn(isoRange.lower, isoRange.upper), frameNs)
            i != null && isoPriority -> {
                aeOn(b, CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_SENSITIVITY_PRIORITY)
                b.set(CaptureRequest.SENSOR_SENSITIVITY, i)
            }
            i != null -> {
                val e = (light / i).roundToLong().coerceIn(expRange.lower, max(expRange.lower, minOf(expRange.upper, maxExp)))
                manual(b, e, i, frameNs)
            }
            else -> aeOn(b, if (Build.VERSION.SDK_INT >= 36 && priorityModes.isNotEmpty()) CameraMetadata.CONTROL_AE_PRIORITY_MODE_OFF else null)
        }
    }

    private fun aeOn(b: CaptureRequest.Builder, priority: Int?) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON)
        if (priority != null && Build.VERSION.SDK_INT >= 36) b.set(CaptureRequest.CONTROL_AE_PRIORITY_MODE, priority)
        if (evRange.upper > evRange.lower) {
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, evIndex.coerceIn(evRange.lower, evRange.upper))
        }
    }

    private fun manual(b: CaptureRequest.Builder, expNs: Long, iso: Int, frameNs: Long) {
        b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
        b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
        b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
        b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(expNs, frameNs))
        boostRange?.let { b.set(CaptureRequest.CONTROL_POST_RAW_SENSITIVITY_BOOST, frozenBoost.coerceIn(it.lower, it.upper)) }
    }

    // ---------------------------------------------------------------- option lists for the UI

    fun shutterOptions(fps: Int, video: Boolean): List<Shutter> {
        val out = mutableListOf<Shutter>(Shutter.Auto)
        if (video) out += Shutter.Angle180
        val frameNs = 1_000_000_000L / fps
        val denominators = listOf(8000, 4000, 2000, 1000, 500, 250, 125, 100, 60, 50, 30, 25, 15, 8, 4, 2)
        // Long exposures from 1/30 s down; in photo mode also 1 s.
        val fixed = denominators.map { 1_000_000_000L / it } + if (video) emptyList() else listOf(1_000_000_000L)
        for (ns in fixed) {
            if (ns < expRange.lower || ns > expRange.upper) continue
            if (video && ns > frameNs) continue
            out += Shutter.Fixed(ns)
        }
        return out
    }

    fun isoOptions(): List<Int?> {
        val standard = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600,
            2000, 2500, 3200, 4000, 5000, 6400, 8000, 10000, 12800)
        return listOf<Int?>(null) + standard.filter { it in isoRange.lower..isoRange.upper }
    }

    fun evOptions(): List<Int> = (evRange.lower..evRange.upper).toList()

    fun shutterLabel(s: Shutter, fps: Int): String = when (s) {
        Shutter.Auto -> "Auto"
        Shutter.Angle180 -> "180° (1/${2 * fps})"
        is Shutter.Fixed -> formatShutter(s.ns)
    }

    fun evLabel(index: Int): String {
        val v = index * evStep
        return when {
            abs(v) < 0.05f -> "±0"
            v > 0 -> String.format(Locale.US, "+%.1f", v)
            else -> String.format(Locale.US, "%.1f", v)
        }
    }

    companion object {
        fun formatShutter(ns: Long): String =
            if (ns >= 500_000_000L) String.format(Locale.US, "%.1fs", ns / 1e9)
            else "1/${(1e9 / ns).roundToInt()}"
    }
}
