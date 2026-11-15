package com.smoothcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentValues
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.drawable.GradientDrawable
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.DynamicRangeProfiles
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.OrientationEventListener
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * SmoothCam: a minimal video camera tuned for smooth motion on Pixel phones.
 *
 * What it does differently from a typical third-party camera app:
 *  1. Locks the sensor to a fixed 60 fps range (not 15–60), so frame timing never drifts.
 *  2. Preview and recording come from one capture session; starting a recording only adds an
 *     output target, so the viewfinder never stutters or blacks out when you press record.
 *  3. Preview goes to a SurfaceView (straight to the display compositor, lowest latency) and tells
 *     the system the content is 60 fps, and asks for a 120 Hz display mode, so every frame is shown
 *     for exactly the same amount of time (no 60-on-90 Hz judder).
 *  4. "180°" shutter: holds exposure at 1/120 s (for 60 fps), giving each frame natural motion blur
 *     instead of razor-sharp, choppy-looking frames. On Android 16 cameras that support
 *     shutter-priority auto-exposure, ISO keeps adapting to the light (like iOS apps can do);
 *     otherwise the exposure is metered once and frozen.
 *  5. Preview stabilization: the viewfinder shows the same stabilized image that gets recorded.
 *  6. 10-bit HLG HDR (default when supported), like the iPhone's default HDR video.
 */
class MainActivity : Activity() {

    private data class VideoMode(val size: Size, val fps: Int, val fpsRange: Range<Int>, val resLabel: String) {
        val label get() = "$resLabel $fps"
    }

    /** One way of building the video session; tried in order until the camera accepts one. */
    private data class SessionVariant(val hdr: Boolean, val stab: Int, val previewHdr: Boolean)

    private lateinit var cameraManager: CameraManager
    private lateinit var cameraId: String
    private lateinit var chars: CameraCharacteristics
    private var sensorOrientation = 90
    private lateinit var previewSize: Size
    private val modes = mutableListOf<VideoMode>()
    @Volatile private var modeIndex = 0

    private var hdrSupported = false
    @Volatile private var hdrOn = false          // what the user wants
    @Volatile private var sessionHdr = false     // what the running session was built with
    private var aePrioritySupported = false

    // Session fallback ladder (video)
    private var variants: List<SessionVariant> = emptyList()
    private var variantIndex = 0
    @Volatile private var sessionStab = CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF
    private val failureLog = ArrayDeque<String>() // recent session failures, for the camera-info dialog
    private var wantedLabel = ""                  // what the user asked for, for fallback messages

    // Zoom / lenses
    private var zoomRange: Range<Float> = Range(1f, 1f)
    @Volatile private var zoomRatio = 1f
    private val zoomPresets = mutableListOf<Float>()

    // Photo mode
    @Volatile private var photoMode = false
    private lateinit var photoSize: Size          // full-res YUV stream for zero-shutter-lag capture
    private lateinit var photoPreviewSize: Size   // 4:3 viewfinder
    private lateinit var photoFpsRange: Range<Int>
    private var photoReader: ImageReader? = null
    private val pendingReaderClose = mutableListOf<ImageReader>()

    // Full-resolution (e.g. 50 MP) mode, only if the camera exposes it to apps.
    private var maxResSize: Size? = null
    @Volatile private var fiftyMpOn = false
    private var maxResReader: ImageReader? = null
    private var awaitingMaxRes = false
    private var maxResIso = 0
    private var maxResOrientation = 0
    private val zslRing = ArrayDeque<Image>()     // most recent frames, newest last
    @Volatile private var processingPhoto = false
    private val photoExecutor = Executors.newSingleThreadExecutor()

    // Mode switching (main thread)
    private var switching = false
    private var awaitingSize: Size? = null
    private var surfaceSize: Size? = null

    private val cameraThread = HandlerThread("camera").apply { start() }
    private val cameraHandler = Handler(cameraThread.looper)
    private val cameraExecutor = Executor { cameraHandler.post(it) }
    private val mainHandler = Handler(Looper.getMainLooper())

    // Camera state: touched only on the camera thread (except reads marked @Volatile).
    @Volatile private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var recordSurface: Surface? = null
    private var recorder: MediaRecorder? = null
    private var hdrRecorder: HdrRecorder? = null
    private var currentUri: Uri? = null
    private var currentPfd: ParcelFileDescriptor? = null
    @Volatile private var recording = false
    @Volatile private var recordStart = 0L

    // Exposure (manual controls live in ExposureControl)
    private lateinit var exposure: ExposureControl
    @Volatile private var lastExposureNs = 0L
    @Volatile private var lastIso = 0
    @Volatile private var lastBoost = 100

    // Measured frame rate from sensor timestamps
    private var lastTs = 0L
    private var frameCount = 0
    private var accumNs = 0L
    @Volatile private var measuredFps = 0.0

    // UI / lifecycle (main thread)
    @Volatile private var resumed = false
    private var surfaceReady = false
    private var opening = false
    @Volatile private var deviceOrientation = 0
    private var orientationListener: OrientationEventListener? = null

    private lateinit var surfaceView: SurfaceView
    private lateinit var statusText: TextView
    private lateinit var recText: TextView
    private lateinit var recordButton: View
    private lateinit var rootView: FrameLayout
    private lateinit var videoSettings: LinearLayout
    private lateinit var resLabel: TextView
    private lateinit var fpsLabel: TextView
    private lateinit var lensRow: LinearLayout
    private val lensButtons = mutableListOf<TextView>()
    private lateinit var evChip: TextView
    private lateinit var shutterChip: TextView
    private lateinit var isoChip: TextView
    private lateinit var sliderPanel: LinearLayout
    private lateinit var sliderLabel: TextView
    private lateinit var slider: SeekBar
    private var activeControl: String? = null      // "ev", "s" or "iso" while its slider is open
    private val rotatables = mutableListOf<View>()  // controls that turn with the phone
    private var uiRotation = 0f
    private lateinit var hdrButton: Button
    private lateinit var maxResButton: Button
    private lateinit var photoTab: TextView
    private lateinit var videoTab: TextView

    // ---------------------------------------------------------------- lifecycle

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraManager = getSystemService(CameraManager::class.java)

        if (!pickCameraAndModes()) {
            Toast.makeText(this, "No suitable back camera found", Toast.LENGTH_LONG).show()
            finish()
            return
        }

        buildUi()
        preferHighRefreshDisplayMode()

        orientationListener = object : OrientationEventListener(this) {
            override fun onOrientationChanged(orientation: Int) {
                if (orientation == OrientationEventListener.ORIENTATION_UNKNOWN) return
                deviceOrientation = ((orientation + 45) / 90 * 90) % 360
                applyUiRotation(deviceOrientation)
            }
        }

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) requestPermissions(needed.toTypedArray(), REQ_PERMS)
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        orientationListener?.enable()
        maybeStartCamera()
    }

    override fun onPause() {
        resumed = false
        orientationListener?.disable()
        runOnCameraBlocking { closeCamera() }
        opening = false
        switching = false
        awaitingSize = null
        mainHandler.removeCallbacks(timerTick)
        super.onPause()
    }

    override fun onDestroy() {
        runOnCameraBlocking {
            closeCamera()
            recordSurface?.release()
            recordSurface = null
        }
        cameraThread.quitSafely()
        photoExecutor.shutdown()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            maybeStartCamera()
        } else {
            statusText.text = "Camera permission is needed. Enable it in Settings > Apps > SmoothCam."
        }
    }

    // Volume keys act as the shutter, like the stock camera.
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (event?.repeatCount == 0) shutterPressed()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- camera selection

    private fun pickCameraAndModes(): Boolean {
        // Prefer the back camera that covers the most lenses through zoom (on Pixel: the logical
        // multi-camera, which switches between ultrawide, main and telephoto by zoom ratio).
        val backIds = cameraManager.cameraIdList.filter {
            cameraManager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) ==
                CameraCharacteristics.LENS_FACING_BACK
        }
        if (backIds.isEmpty()) return false
        fun zoomOf(id: String): Range<Float> =
            cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) ?: Range(1f, 1f)
        val id = backIds.minWithOrNull(compareBy<String>({ zoomOf(it).lower }, { -zoomOf(it).upper })) ?: backIds[0]
        cameraId = id
        chars = cameraManager.getCameraCharacteristics(id)
        exposure = ExposureControl(chars)
        zoomRange = zoomOf(id)
        zoomPresets.clear()
        if (zoomRange.lower < 0.95f) zoomPresets += zoomRange.lower
        zoomPresets += 1f
        for (z in listOf(2f, 5f, 10f)) if (zoomRange.upper >= z) zoomPresets += z
        sensorOrientation = chars.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

        val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return false
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES) ?: emptyArray()
        val recSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()

        // Prefer a fixed range like [60, 60]; a range like [15, 60] lets AE drop the frame rate.
        fun rangeFor(fps: Int): Range<Int>? = fpsRanges.filter { it.upper == fps }.maxByOrNull { it.lower }

        fun supports(size: Size, fps: Int): Boolean {
            if (recSizes.none { it == size }) return false
            val minFrameNs = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
            return minFrameNs <= 1_000_000_000L / fps + 100_000L
        }

        val uhd = Size(3840, 2160)
        val fhd = Size(1920, 1080)
        // Order = fallback order: highest frame rate first, then resolution.
        for (fps in listOf(60, 30, 24)) {
            val r = rangeFor(fps) ?: continue
            if (supports(uhd, fps)) modes += VideoMode(uhd, fps, r, "4K")
            if (supports(fhd, fps)) modes += VideoMode(fhd, fps, r, "1080p")
        }
        if (modes.isEmpty()) return false

        // 10-bit HLG HDR: Android 13+ requires it on every camera that has 10-bit output.
        val caps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val profiles = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
        hdrSupported = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DYNAMIC_RANGE_TEN_BIT) &&
            profiles?.supportedProfiles?.contains(DynamicRangeProfiles.HLG10) == true
        hdrOn = hdrSupported

        // Shutter-priority auto-exposure (Android 16 / API 36): app fixes exposure time, AE adjusts ISO.
        aePrioritySupported = Build.VERSION.SDK_INT >= 36 &&
            chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_PRIORITY_MODES)
                ?.contains(CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY) == true

        val previewSizes = map.getOutputSizes(SurfaceHolder::class.java) ?: emptyArray()
        previewSize = previewSizes.firstOrNull { it == fhd }
            ?: previewSizes.filter { it.width * 9 == it.height * 16 && it.width <= 1920 }.maxByOrNull { it.width }
            ?: fhd

        // Photo mode: 4:3 viewfinder plus the largest 4:3 YUV stream (<= ~12.5 MP) that runs at 30 fps.
        photoPreviewSize = previewSizes.firstOrNull { it == Size(1440, 1080) }
            ?: previewSizes.filter { it.width * 3 == it.height * 4 && it.width <= 1920 }.maxByOrNull { it.width }
            ?: Size(1440, 1080)
        val yuvSizes = map.getOutputSizes(ImageFormat.YUV_420_888) ?: emptyArray()
        val yuv30 = yuvSizes.filter {
            map.getOutputMinFrameDuration(ImageFormat.YUV_420_888, it) <= 33_400_000L
        }
        photoSize = yuv30.filter { it.width * 3 == it.height * 4 && it.width <= 4096 }.maxByOrNull { it.width * it.height }
            ?: yuv30.filter { it.width <= 4096 }.maxByOrNull { it.width * it.height }
            ?: Size(1920, 1440)
        photoFpsRange = rangeFor(30) ?: fpsRanges.maxByOrNull { it.upper } ?: Range(15, 30)

        // Full sensor resolution: Android 12+ "maximum resolution" sensor mode. Many phone makers keep
        // it for their own camera app, so only offer it if this camera exposes a bigger YUV size to apps.
        val requestKeys = chars.availableCaptureRequestKeys
        val pixelModeAllowed = caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR) ||
            requestKeys.contains(CaptureRequest.SENSOR_PIXEL_MODE)
        val maxMap = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION)
        val maxYuv = maxMap?.getOutputSizes(ImageFormat.YUV_420_888)?.maxByOrNull { it.width * it.height }
        maxResSize = if (pixelModeAllowed && maxYuv != null &&
            maxYuv.width * maxYuv.height >= 2 * photoSize.width * photoSize.height) maxYuv else null
        Log.i(TAG, "max-res mode: allowed=$pixelModeAllowed size=$maxYuv -> ${maxResSize ?: "not available"}")
        return true
    }

    // ---------------------------------------------------------------- display refresh

    /** Ask for the highest refresh rate that is a multiple of 60 (120 Hz on Pixel 10). */
    private fun preferHighRefreshDisplayMode() {
        val d = try { display } catch (e: Exception) { null } ?: return
        val current = d.mode
        val best = d.supportedModes
            .filter { it.physicalWidth == current.physicalWidth && it.physicalHeight == current.physicalHeight }
            .filter { it.refreshRate.roundToInt() % 60 == 0 }
            .maxByOrNull { it.refreshRate } ?: return
        val attrs = window.attributes
        attrs.preferredDisplayModeId = best.modeId
        window.attributes = attrs
    }

    /** Tell the compositor this surface carries fixed-rate video, so it picks a matching refresh rate. */
    private fun applyFrameRateHint(fps: Int) {
        val s = surfaceView.holder.surface
        if (s.isValid) {
            try {
                s.setFrameRate(fps.toFloat(), Surface.FRAME_RATE_COMPATIBILITY_FIXED_SOURCE)
            } catch (e: Exception) {
                Log.w(TAG, "setFrameRate failed", e)
            }
        }
    }

    // ---------------------------------------------------------------- camera open / session

    private fun maybeStartCamera() {
        if (!resumed || !surfaceReady || opening || device != null) return
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return
        opening = true
        openCamera()
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        try {
            cameraManager.openCamera(cameraId, cameraExecutor, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    mainHandler.post { opening = false }
                    if (!resumed) {
                        camera.close()
                        return
                    }
                    device = camera
                    startSession()
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    device = null
                    mainHandler.post { opening = false }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    device = null
                    mainHandler.post { opening = false }
                    showStatus("Camera error $error")
                }
            })
        } catch (e: Exception) {
            opening = false
            statusText.text = "Could not open camera: ${e.message}"
        }
    }

    /**
     * Ways to build the video session, best first. A camera may accept 4K 60 HDR only with a
     * particular stabilization mode, or only with an SDR viewfinder, so we try those before giving
     * up on HDR. The first variant the camera accepts wins.
     */
    private fun buildVariants(hdrWanted: Boolean): List<SessionVariant> {
        val available = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES) ?: intArrayOf()
        val stabs = listOf(
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON,
            CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
        ).filter { it == CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF || available.contains(it) }
        val out = mutableListOf<SessionVariant>()
        if (hdrWanted && hdrSupported) {
            for (st in stabs) out += SessionVariant(hdr = true, stab = st, previewHdr = true)
            // HDR recording with an SDR viewfinder, if the camera allows mixing the two.
            val constraints = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_DYNAMIC_RANGE_PROFILES)
                ?.getProfileCaptureRequestConstraints(DynamicRangeProfiles.HLG10)
            if (constraints?.contains(DynamicRangeProfiles.STANDARD) == true) {
                for (st in stabs) out += SessionVariant(hdr = true, stab = st, previewHdr = false)
            }
        }
        for (st in stabs) out += SessionVariant(hdr = false, stab = st, previewHdr = false)
        return out
    }

    /** Camera thread. Start the current video mode from the best variant. */
    private fun restartVideo() {
        variants = buildVariants(hdrOn)
        variantIndex = 0
        wantedLabel = modeName(modes[modeIndex], hdrOn && hdrSupported)
        startSession()
    }

    private fun stabName(st: Int) = when (st) {
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_PREVIEW_STABILIZATION -> "preview stabilization"
        CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON -> "standard stabilization"
        else -> "no electronic stabilization"
    }

    private fun logFailure(msg: String, e: Throwable? = null) {
        val line = if (e != null) "$msg: ${e.javaClass.simpleName} ${e.message}" else msg
        Log.w(TAG, line, e)
        synchronized(failureLog) {
            failureLog.addLast(line)
            while (failureLog.size > 12) failureLog.removeFirst()
        }
    }

    /** Runs on the camera thread. Creates one session with both the preview and the recorder surface. */
    private fun startSession() {
        if (photoMode) {
            startPhotoSession()
            return
        }
        closePhotoReader()
        val cam = device ?: return
        if (variants.isEmpty()) {
            variants = buildVariants(hdrOn)
            variantIndex = 0
            wantedLabel = modeName(modes[modeIndex], hdrOn && hdrSupported)
        }
        val mode = modes[modeIndex]
        val variant = variants[variantIndex]
        session?.close()
        session = null
        lastTs = 0L; frameCount = 0; accumNs = 0L

        // A persistent encoder surface lets us start and stop many recordings without
        // rebuilding the capture session, so the viewfinder never hiccups when you press record.
        val oldSurface = recordSurface
        val hdr = variant.hdr
        val rs = MediaCodec.createPersistentInputSurface()
        try {
            if (hdr) {
                HdrRecorder.probe(rs, mode.size.width, mode.size.height, mode.fps, bitRateFor(mode, true))
            } else {
                val probe = File(cacheDir, "probe.mp4")
                createRecorder(rs, mode, null, probe, withAudio = false, orientationHint = 0).apply {
                    prepare()
                    release()
                }
                probe.delete()
            }
        } catch (e: Exception) {
            logFailure("Encoder rejected ${modeName(mode, hdr)}", e)
            rs.release()
            if (hdr) {
                // The encoder itself can't do HDR here: skip the remaining HDR variants.
                variantIndex = variants.indexOfFirst { !it.hdr }.coerceAtLeast(variantIndex)
                nextVariantOrFallBack(alreadyAdvanced = true)
            } else {
                nextVariantOrFallBack()
            }
            return
        }
        recordSurface = rs
        sessionHdr = hdr

        val preview = surfaceView.holder.surface
        val params = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
        applyControls(params, mode, variant.stab)

        val previewConfig = OutputConfiguration(preview)
        val recordConfig = OutputConfiguration(rs)
        if (hdr) {
            if (variant.previewHdr) previewConfig.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            recordConfig.setDynamicRangeProfile(DynamicRangeProfiles.HLG10)
            // HdrRecorder times audio with System.nanoTime(), so ask for video timestamps on the same clock.
            recordConfig.setTimestampBase(OutputConfiguration.TIMESTAMP_BASE_MONOTONIC)
        }
        val outputs = listOf(previewConfig, recordConfig)
        val desc = "${modeName(mode, hdr)}${if (hdr && !variant.previewHdr) " (SDR viewfinder)" else ""}, ${stabName(variant.stab)}"
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    oldSurface?.release()
                    if (device == null) {
                        s.close()
                        return
                    }
                    session = s
                    sessionStab = variant.stab
                    setRepeating(withRecord = false)
                    reportVariantResult(mode, variant)
                    mainHandler.post {
                        applyFrameRateHint(mode.fps)
                        // Let the window use the screen's HDR brightness headroom for the preview.
                        window.colorMode = if (hdr && variant.previewHdr) ActivityInfo.COLOR_MODE_HDR else ActivityInfo.COLOR_MODE_DEFAULT
                        updateButtons()
                        updateStatus()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    oldSurface?.release()
                    logFailure("Camera rejected session: $desc")
                    nextVariantOrFallBack()
                }
            }
        )
        // Session parameters let the camera pick its 60 fps sensor mode up front.
        config.setSessionParameters(params.build())
        try {
            // Ask first when possible: much faster than letting a full session setup fail.
            val supported = try { cam.isSessionConfigurationSupported(config) } catch (e: Exception) { true }
            if (!supported) {
                oldSurface?.release()
                logFailure("Camera reports unsupported: $desc")
                nextVariantOrFallBack()
                return
            }
            cam.createCaptureSession(config)
        } catch (e: Exception) {
            logFailure("createCaptureSession failed: $desc", e)
            nextVariantOrFallBack()
        }
    }

    /** Camera thread. Tell the user only when we had to give something up. */
    private fun reportVariantResult(mode: VideoMode, v: SessionVariant) {
        val first = variants.firstOrNull() ?: return
        if (v == first) return
        val got = modeName(mode, v.hdr)
        val msg = when {
            first.hdr && v.hdr && !v.previewHdr -> "$got: recording HDR with an SDR viewfinder (the camera won't preview HDR here)"
            first.hdr && v.hdr -> "$got works with ${stabName(v.stab)} only"
            first.hdr && !v.hdr -> "The camera doesn't offer ${modeName(mode, true)} to apps; using SDR. Tap the status bar for details."
            else -> "$got works with ${stabName(v.stab)} only"
        }
        if (first.hdr && !v.hdr) hdrOn = false
        toast(msg)
    }

    /** Camera thread. Try the next variant; when none are left, step down to the next video mode. */
    private fun nextVariantOrFallBack(alreadyAdvanced: Boolean = false) {
        if (!alreadyAdvanced) variantIndex++
        if (variantIndex < variants.size) {
            startSession()
            return
        }
        if (modeIndex < modes.size - 1) {
            modeIndex++
            variants = buildVariants(hdrOn)
            variantIndex = 0
            toast("Camera can't do $wantedLabel, trying ${modes[modeIndex].label}")
            startSession()
        } else {
            showStatus("No working video mode found. Tap here for details.")
        }
    }

    private fun modeName(mode: VideoMode, hdr: Boolean) = if (hdr) "${mode.label} HDR" else mode.label

    private fun bitRateFor(mode: VideoMode, hdr: Boolean): Int {
        val uhd = mode.size.width >= 3840
        val base = if (uhd) 80_000_000 else 30_000_000
        val scaled = if (mode.fps >= 60) base else base * 2 / 3
        return if (hdr) scaled * 5 / 4 else scaled
    }

    private fun applyControls(b: CaptureRequest.Builder, mode: VideoMode, stab: Int = sessionStab) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mode.fpsRange)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, stab)
        applyOis(b)
        applyZoom(b)
        exposure.apply(b, mode.fps, 1_000_000_000L / mode.fps, video = true)
    }

    private fun applyOis(b: CaptureRequest.Builder) {
        val ois = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: intArrayOf()
        if (ois.contains(CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun applyZoom(b: CaptureRequest.Builder) {
        if (zoomRange.upper > zoomRange.lower) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, zoomRatio.coerceIn(zoomRange.lower, zoomRange.upper))
        }
    }

    /** Camera thread. Re-issue the repeating request soon, coalescing rapid changes (pinch, sliders). */
    private val repeatUpdate = Runnable { setRepeating(recording) }
    private fun requestRepeatingUpdate() {
        cameraHandler.removeCallbacks(repeatUpdate)
        cameraHandler.post(repeatUpdate)
    }

    private fun setRepeating(withRecord: Boolean) {
        val cam = device ?: return
        val s = session ?: return
        try {
            if (photoMode) {
                val b = photoRequestBuilder(cam)
                b.addTarget(surfaceView.holder.surface)
                photoReader?.let { b.addTarget(it.surface) }
                s.setRepeatingRequest(b.build(), captureCallback, cameraHandler)
                return
            }
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            b.addTarget(surfaceView.holder.surface)
            if (withRecord) recordSurface?.let { b.addTarget(it) }
            applyControls(b, modes[modeIndex])
            s.setRepeatingRequest(b.build(), captureCallback, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "setRepeatingRequest failed", e)
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(s: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { lastExposureNs = it }
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { lastIso = it }
            result.get(CaptureResult.CONTROL_POST_RAW_SENSITIVITY_BOOST)?.let { lastBoost = it }
            if (exposure.isFullyAuto) {
                exposure.lastAutoExposureNs = lastExposureNs
                exposure.lastAutoIso = lastIso
                exposure.lastAutoBoost = lastBoost
            }

            val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
            if (lastTs != 0L) {
                accumNs += ts - lastTs
                frameCount++
            }
            lastTs = ts
            if (frameCount >= 30 && accumNs > 0) {
                measuredFps = frameCount * 1e9 / accumNs
                frameCount = 0
                accumNs = 0L
                mainHandler.post { updateStatus() }
            }
        }
    }

    private fun closeCamera() {
        if (recording) stopRecording()
        try { session?.close() } catch (e: Exception) { /* ignore */ }
        session = null
        closePhotoReader()
        device?.close()
        device = null
        lastTs = 0L
    }

    // ---------------------------------------------------------------- recording

    private fun toggleRecording() {
        cameraHandler.post { if (recording) stopRecording() else startRecording() }
    }

    private fun createRecorder(
        surface: Surface,
        mode: VideoMode,
        fd: FileDescriptor?,
        file: File?,
        withAudio: Boolean,
        orientationHint: Int,
    ): MediaRecorder {
        val r = MediaRecorder(this)
        if (withAudio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
        if (fd != null) r.setOutputFile(fd) else r.setOutputFile(file!!)
        r.setVideoEncodingBitRate(bitRateFor(mode, false))
        r.setVideoFrameRate(mode.fps)
        r.setVideoSize(mode.size.width, mode.size.height)
        r.setVideoEncoder(MediaRecorder.VideoEncoder.HEVC)
        if (withAudio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(48_000)
            r.setAudioEncodingBitRate(256_000)
            r.setAudioChannels(2)
        }
        r.setOrientationHint(orientationHint)
        r.setInputSurface(surface)
        return r
    }

    private fun startRecording() {
        if (recording || session == null) return
        val rs = recordSurface ?: return
        val mode = modes[modeIndex]

        val name = "SmoothCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".mp4"
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/SmoothCam")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri == null) {
            toast("Could not create video file")
            return
        }
        var pfd: ParcelFileDescriptor? = null
        try {
            pfd = contentResolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("no file descriptor")
            val hasAudio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            // Back camera: rotate by sensor orientation plus how the phone is held.
            val hint = (sensorOrientation + deviceOrientation) % 360
            if (sessionHdr) {
                val hr = HdrRecorder(
                    rs, mode.size.width, mode.size.height, mode.fps, bitRateFor(mode, true),
                    pfd.fileDescriptor, hasAudio, hint
                )
                hdrRecorder = hr // set first so the catch block below can clean it up
                hr.start()
                setRepeating(withRecord = true)
            } else {
                val r = createRecorder(rs, mode, pfd.fileDescriptor, null, hasAudio, hint)
                r.prepare()
                setRepeating(withRecord = true)
                r.start()
                recorder = r
            }
            currentUri = uri
            currentPfd = pfd
            recordStart = SystemClock.elapsedRealtime()
            recording = true
            mainHandler.post { onRecordingChanged() }
        } catch (e: Exception) {
            Log.e(TAG, "startRecording failed", e)
            setRepeating(withRecord = false)
            hdrRecorder?.let { try { it.stop() } catch (ignored: Exception) { } }
            hdrRecorder = null
            try { pfd?.close() } catch (ignored: Exception) { }
            contentResolver.delete(uri, null, null)
            toast("Recording failed: ${e.message}")
        }
    }

    private fun stopRecording() {
        if (!recording) return
        recording = false
        setRepeating(withRecord = false)
        val r = recorder
        recorder = null
        val hr = hdrRecorder
        hdrRecorder = null
        var ok = true
        if (hr != null) {
            ok = try { hr.stop() } catch (e: Exception) { false }
        }
        try {
            r?.stop()
        } catch (e: RuntimeException) {
            ok = false // happens if the clip had no frames (too short)
        }
        r?.release()
        try { currentPfd?.close() } catch (ignored: Exception) { }
        currentPfd = null
        val uri = currentUri
        currentUri = null
        if (uri != null) {
            if (ok) {
                val done = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                contentResolver.update(uri, done, null, null)
            } else {
                contentResolver.delete(uri, null, null)
            }
        }
        mainHandler.post { onRecordingChanged() }
        toast(if (ok) "Saved to Movies/SmoothCam" else "Clip too short, discarded")
    }

    // ---------------------------------------------------------------- UI

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildUi() {
        val root = FrameLayout(this)
        root.setBackgroundColor(Color.BLACK)
        rootView = root

        // Portrait UI: the 16:9 sensor image is shown rotated, so the view is 9:16.
        val w = resources.displayMetrics.widthPixels
        val h = w * previewSize.width / previewSize.height
        surfaceView = SurfaceView(this)
        root.addView(surfaceView, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        surfaceView.holder.setFixedSize(previewSize.width, previewSize.height)
        // Pinch to zoom; the camera switches lenses by itself as the zoom ratio crosses them.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoomRatio * detector.scaleFactor)
                return true
            }
        })
        surfaceView.setOnTouchListener { v, e ->
            scaleDetector.onTouchEvent(e)
            if (e.actionMasked == MotionEvent.ACTION_UP) v.performClick()
            true
        }
        surfaceView.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                surfaceReady = true
                applyFrameRateHint(modes[modeIndex].fps)
                maybeStartCamera()
            }

            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                val size = Size(width, height)
                surfaceSize = size
                if (awaitingSize == size) {
                    // Viewfinder now has the new mode's size: build the new session.
                    awaitingSize = null
                    switching = false
                    cameraHandler.post { startSession() }
                }
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                surfaceReady = false
                surfaceSize = null
                switching = false
                awaitingSize = null
                runOnCameraBlocking { closeCamera() }
            }
        })

        statusText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 13f
            setPadding(dp(12), dp(6), dp(12), dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(140, 0, 0, 0))
                cornerRadius = dp(12).toFloat()
            }
            text = "Starting camera…"
            setOnClickListener { showCameraInfo() }
        }
        root.addView(statusText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(48) })

        recText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(dp(12), dp(4), dp(12), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.rgb(200, 30, 30))
                cornerRadius = dp(8).toFloat()
            }
            visibility = View.GONE
        }
        root.addView(recText, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.CENTER_HORIZONTAL
        ).apply { topMargin = dp(92) })

        recordButton = View(this).apply {
            setOnClickListener { shutterPressed() }
        }
        root.addView(recordButton, FrameLayout.LayoutParams(dp(76), dp(76),
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(56) })

        // Resolution and frame rate, top right, each with its own menu (like iPhone's "4K · 60").
        videoSettings = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        resLabel = settingLabel().apply { setOnClickListener { showResolutionMenu(it) } }
        fpsLabel = settingLabel().apply { setOnClickListener { showFpsMenu(it) } }
        videoSettings.addView(resLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)))
        videoSettings.addView(fpsLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)).apply {
            leftMargin = dp(8)
        })
        root.addView(videoSettings, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(92); rightMargin = dp(16) })

        // Lens / zoom presets (0.5x, 1x, 2x, 5x...). Pinch works too.
        lensRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(110, 0, 0, 0))
                cornerRadius = dp(24).toFloat()
            }
        }
        for (z in zoomPresets) {
            val t = TextView(this).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setTextColor(Color.WHITE)
                setOnClickListener { setZoom(z) }
            }
            lensButtons += t
            lensRow.addView(t, LinearLayout.LayoutParams(dp(40), dp(40)).apply {
                leftMargin = dp(2); rightMargin = dp(2)
            })
        }
        if (zoomPresets.size < 2) lensRow.visibility = View.GONE
        root.addView(lensRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(194) })

        // Manual exposure: tap a chip to open its slider, long-press to reset it to auto.
        val chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        evChip = chip("ev")
        shutterChip = chip("s")
        isoChip = chip("iso")
        for (c in listOf(evChip, shutterChip, isoChip)) {
            chipRow.addView(c, LinearLayout.LayoutParams(dp(96), dp(34)).apply {
                leftMargin = dp(4); rightMargin = dp(4)
            })
        }
        root.addView(chipRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(248) })

        sliderPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(170, 0, 0, 0))
                cornerRadius = dp(16).toFloat()
            }
            visibility = View.GONE
        }
        sliderLabel = TextView(this).apply {
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            textSize = 14f
        }
        slider = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) applySlider(progress)
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {}
            })
        }
        sliderPanel.addView(sliderLabel, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        sliderPanel.addView(slider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))
        root.addView(sliderPanel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        ).apply { bottomMargin = dp(292); leftMargin = dp(24); rightMargin = dp(24) })

        hdrButton = pillButton().apply {
            setOnClickListener { cameraHandler.post { toggleHdr() } }
            visibility = if (hdrSupported) View.VISIBLE else View.GONE
        }
        root.addView(hdrButton, FrameLayout.LayoutParams(dp(72), dp(34),
            Gravity.TOP or Gravity.START
        ).apply { topMargin = dp(92); leftMargin = dp(16) })

        maxResButton = pillButton().apply {
            text = "50 MP"
            setOnClickListener { cameraHandler.post { toggleMaxRes() } }
        }
        root.addView(maxResButton, FrameLayout.LayoutParams(dp(72), dp(34),
            Gravity.TOP or Gravity.START
        ).apply { topMargin = dp(92); leftMargin = dp(16) })

        // PHOTO | VIDEO switch above the shutter, like the iPhone camera.
        val tabs = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        photoTab = tabLabel("PHOTO").apply { setOnClickListener { switchCaptureMode(true) } }
        videoTab = tabLabel("VIDEO").apply { setOnClickListener { switchCaptureMode(false) } }
        tabs.addView(photoTab, LinearLayout.LayoutParams(dp(96), dp(40)))
        tabs.addView(videoTab, LinearLayout.LayoutParams(dp(96), dp(40)))
        root.addView(tabs, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(146) })

        setContentView(root)
        rotatables += listOf(recText, hdrButton, maxResButton, resLabel, fpsLabel, evChip, shutterChip, isoChip,
            photoTab, videoTab, sliderLabel)
        rotatables += lensButtons
        styleRecordButton(false)
        updateButtons()
    }

    private fun settingLabel() = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(dp(12), 0, dp(12), 0)
        background = GradientDrawable().apply {
            setColor(Color.argb(140, 0, 0, 0))
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
    }

    private fun chip(which: String) = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 12f
        setTextColor(Color.WHITE)
        setOnClickListener { toggleControl(which) }
        setOnLongClickListener {
            resetControl(which)
            true
        }
    }

    private fun pillButton() = Button(this).apply {
        isAllCaps = false
        setTextColor(Color.WHITE)
        textSize = 13f
        stateListAnimator = null
        setPadding(0, 0, 0, 0)
    }

    private fun stylePill(b: Button, active: Boolean) {
        b.background = GradientDrawable().apply {
            setColor(if (active) Color.rgb(255, 196, 0) else Color.argb(140, 0, 0, 0))
            cornerRadius = dp(22).toFloat()
            setStroke(dp(1), Color.argb(120, 255, 255, 255))
        }
        b.setTextColor(if (active) Color.BLACK else Color.WHITE)
    }

    private fun tabLabel(label: String) = TextView(this).apply {
        text = label
        textSize = 14f
        gravity = Gravity.CENTER
        letterSpacing = 0.1f
    }

    private fun styleRecordButton(rec: Boolean) {
        recordButton.background = GradientDrawable().apply {
            setColor(if (photoMode) Color.WHITE else Color.rgb(230, 40, 40))
            if (photoMode) setStroke(dp(4), Color.argb(255, 120, 120, 120)) else
            setStroke(dp(4), Color.WHITE)
            if (rec) {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
            } else {
                shape = GradientDrawable.OVAL
            }
        }
    }

    private fun updateButtons() {
        val m = modes[modeIndex]
        videoSettings.visibility = if (photoMode) View.GONE else View.VISIBLE
        resLabel.text = m.resLabel
        fpsLabel.text = "${m.fps}"
        val idleAlpha = if (recording) 0.4f else 1f
        resLabel.alpha = idleAlpha
        fpsLabel.alpha = idleAlpha
        hdrButton.visibility = if (photoMode || !hdrSupported) View.GONE else View.VISIBLE
        maxResButton.visibility = if (photoMode && maxResSize != null) View.VISIBLE else View.GONE
        maxResSize?.let { maxResButton.text = String.format(Locale.US, "%.0f MP", it.width * it.height / 1_000_000.0) }
        stylePill(maxResButton, fiftyMpOn)
        photoTab.setTextColor(if (photoMode) Color.rgb(255, 196, 0) else Color.WHITE)
        videoTab.setTextColor(if (photoMode) Color.WHITE else Color.rgb(255, 196, 0))
        val tabsEnabled = !recording
        photoTab.isEnabled = tabsEnabled
        videoTab.isEnabled = tabsEnabled
        photoTab.alpha = idleAlpha
        videoTab.alpha = idleAlpha
        hdrButton.text = if (hdrOn) "HDR" else "SDR"
        stylePill(hdrButton, hdrOn)
        hdrButton.isEnabled = !recording
        hdrButton.alpha = idleAlpha
        updateChips()
        updateLensButtons()
    }

    // ---------------------------------------------------------------- resolution / frame rate menus

    private fun showResolutionMenu(anchor: View) {
        if (recording) return
        val labels = modes.map { it.resLabel }.distinct()
        val pm = PopupMenu(this, anchor)
        labels.forEachIndexed { i, l ->
            pm.menu.add(0, i, i, l).apply {
                isCheckable = true
                isChecked = l == modes[modeIndex].resLabel
            }
        }
        pm.setOnMenuItemClickListener { item ->
            selectMode(labels[item.itemId], modes[modeIndex].fps)
            true
        }
        pm.show()
    }

    private fun showFpsMenu(anchor: View) {
        if (recording) return
        val cur = modes[modeIndex]
        val fpsList = modes.filter { it.resLabel == cur.resLabel }.map { it.fps }.distinct().sortedDescending()
        val pm = PopupMenu(this, anchor)
        fpsList.forEachIndexed { i, f ->
            pm.menu.add(0, i, i, "$f fps").apply {
                isCheckable = true
                isChecked = f == cur.fps
            }
        }
        pm.setOnMenuItemClickListener { item ->
            selectMode(cur.resLabel, fpsList[item.itemId])
            true
        }
        pm.show()
    }

    /** Main thread. Resolution and frame rate are chosen independently; keep the other one when possible. */
    private fun selectMode(res: String, fps: Int) {
        val exact = modes.indexOfFirst { it.resLabel == res && it.fps == fps }
        val idx = if (exact >= 0) exact else modes.indexOfFirst { it.resLabel == res } // modes are sorted by fps, high first
        if (idx < 0 || idx == modeIndex) return
        modeIndex = idx
        if (exact < 0) toast("$res doesn't support $fps fps here, using ${modes[idx].fps} fps")
        updateButtons()
        cameraHandler.post { restartVideo() }
    }

    // ---------------------------------------------------------------- zoom

    /** Main thread. */
    private fun setZoom(z: Float) {
        zoomRatio = z.coerceIn(zoomRange.lower, zoomRange.upper)
        updateLensButtons()
        requestRepeatingUpdate()
    }

    private fun zoomLabel(z: Float): String = when {
        z < 1f -> String.format(Locale.US, "%.1f", z).removePrefix("0")
        abs(z - z.roundToInt()) < 0.05f -> "${z.roundToInt()}×"
        else -> String.format(Locale.US, "%.1f×", z)
    }

    private fun updateLensButtons() {
        if (lensButtons.isEmpty()) return
        // The active button is the largest preset not above the current zoom; it shows the exact zoom.
        var active = 0
        zoomPresets.forEachIndexed { i, p -> if (zoomRatio >= p - 0.01f) active = i }
        lensButtons.forEachIndexed { i, t ->
            val on = i == active
            t.text = if (on) zoomLabel(zoomRatio) else zoomLabel(zoomPresets[i])
            t.setTextColor(if (on) Color.rgb(255, 196, 0) else Color.WHITE)
            t.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (on) Color.argb(200, 30, 30, 30) else Color.argb(90, 0, 0, 0))
            }
        }
    }

    // ---------------------------------------------------------------- manual exposure UI

    private fun currentFps(): Int = if (photoMode) photoFpsRange.upper else modes[modeIndex].fps
    private fun shutterOpts() = exposure.shutterOptions(currentFps(), video = !photoMode)
    private fun isoOpts() = exposure.isoOptions()
    private fun evOpts() = exposure.evOptions()

    private fun toggleControl(which: String) {
        if (activeControl == which) {
            activeControl = null
            sliderPanel.visibility = View.GONE
            updateChips()
            return
        }
        val (count, index) = when (which) {
            "ev" -> evOpts().size to evOpts().indexOf(exposure.evIndex)
            "s" -> shutterOpts().size to shutterOpts().indexOf(exposure.shutter)
            else -> isoOpts().size to isoOpts().indexOf(exposure.iso)
        }
        if (count < 2) {
            toast("This camera doesn't allow changing that")
            return
        }
        activeControl = which
        slider.max = count - 1
        slider.progress = index.coerceAtLeast(0)
        sliderPanel.visibility = View.VISIBLE
        updateChips()
    }

    private fun applySlider(i: Int) {
        when (activeControl) {
            "ev" -> evOpts().getOrNull(i)?.let { exposure.evIndex = it }
            "s" -> shutterOpts().getOrNull(i)?.let {
                exposure.freezeIfLeavingAuto()
                exposure.shutter = it
            }
            "iso" -> if (i in isoOpts().indices) {
                exposure.freezeIfLeavingAuto()
                exposure.iso = isoOpts()[i]
            }
        }
        updateChips()
        requestRepeatingUpdate()
    }

    private fun resetControl(which: String) {
        when (which) {
            "ev" -> exposure.evIndex = 0
            "s" -> exposure.shutter = ExposureControl.Shutter.Auto
            else -> exposure.iso = null
        }
        if (activeControl == which) {
            val idx = when (which) {
                "ev" -> evOpts().indexOf(0)
                else -> 0
            }
            slider.progress = idx.coerceAtLeast(0)
        }
        updateChips()
        requestRepeatingUpdate()
        toast("${which.uppercase(Locale.US)} reset to auto")
    }

    private fun updateChips() {
        val fps = currentFps()
        val sAuto = exposure.shutter == ExposureControl.Shutter.Auto
        val evText = "EV ${exposure.evLabel(exposure.evIndex)}"
        val sText = if (sAuto) "S Auto" else "S ${exposure.shutterLabel(exposure.shutter, fps).substringBefore(" (")}"
        val isoText = exposure.iso?.let { "ISO $it" } ?: "ISO Auto"
        styleChip(evChip, evText, exposure.evIndex != 0, exposure.evActive)
        styleChip(shutterChip, sText, !sAuto, true)
        styleChip(isoChip, isoText, exposure.iso != null, true)
        sliderLabel.text = when (activeControl) {
            "ev" -> "Exposure compensation: ${exposure.evLabel(exposure.evIndex)}" +
                if (!exposure.evActive) " (no effect while shutter and ISO are both manual)" else ""
            "s" -> "Shutter: ${exposure.shutterLabel(exposure.shutter, fps)}" + when {
                sAuto -> ""
                exposure.iso == null && exposure.shutterPriority -> " · ISO stays automatic"
                exposure.iso == null -> " · ISO set from the last auto reading"
                else -> ""
            }
            "iso" -> "ISO: ${exposure.iso ?: "Auto"}" + when {
                exposure.iso == null -> ""
                sAuto && exposure.isoPriority -> " · shutter stays automatic"
                sAuto -> " · shutter set from the last auto reading"
                else -> ""
            }
            else -> ""
        }
    }

    private fun styleChip(c: TextView, text: String, manual: Boolean, enabled: Boolean) {
        c.text = text
        val selected = (c === evChip && activeControl == "ev") || (c === shutterChip && activeControl == "s") ||
            (c === isoChip && activeControl == "iso")
        c.setTextColor(if (manual) Color.rgb(255, 196, 0) else Color.WHITE)
        c.alpha = if (enabled) 1f else 0.45f
        c.background = GradientDrawable().apply {
            setColor(if (selected) Color.argb(220, 40, 40, 40) else Color.argb(130, 0, 0, 0))
            cornerRadius = dp(17).toFloat()
            setStroke(dp(1), if (selected) Color.rgb(255, 196, 0) else Color.argb(90, 255, 255, 255))
        }
    }

    // ---------------------------------------------------------------- orientation

    /** Main thread. Turn the controls in place to match how the phone is held (the layout itself stays put). */
    private fun applyUiRotation(o: Int) {
        val target = when (o) {
            90 -> -90f
            270 -> 90f
            180 -> 180f
            else -> 0f
        }
        if (target == uiRotation) return
        uiRotation = target
        for (v in rotatables) v.animate().rotation(target).setDuration(250).start()
        positionStatus()
    }

    /** In landscape, move the info bar to the edge that is now "up" so it reads naturally and isn't clipped. */
    private fun positionStatus() {
        val v = statusText
        val rw = rootView.width.toFloat()
        val rh = rootView.height.toFloat()
        val cx0 = v.left + v.width / 2f
        val cy0 = v.top + v.height / 2f
        val margin = dp(12).toFloat()
        val tx: Float
        val ty: Float
        when (uiRotation) {
            -90f -> { tx = margin + v.height / 2f - cx0; ty = rh / 2f - cy0 }
            90f -> { tx = rw - margin - v.height / 2f - cx0; ty = rh / 2f - cy0 }
            else -> { tx = 0f; ty = 0f }
        }
        v.animate().rotation(uiRotation).translationX(tx).translationY(ty).setDuration(250).start()
    }

    // ---------------------------------------------------------------- camera info

    private fun showCameraInfo() {
        val sb = StringBuilder()
        sb.append("Camera in use: $cameraId (all: ${cameraManager.cameraIdList.joinToString()})\n")
        val physical = try { chars.physicalCameraIds.joinToString() } catch (e: Exception) { "" }
        sb.append("Lenses behind it: ${physical.ifEmpty { "none listed" }}\n")
        val focal = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.joinToString { String.format(Locale.US, "%.1f", it) }
        sb.append("Focal lengths (mm): ${focal ?: "?"}\n")
        sb.append("Zoom range: ${zoomRange.lower}–${zoomRange.upper}\n")
        val fpsRanges = chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.joinToString() ?: "?"
        sb.append("Frame-rate ranges: $fpsRanges\n")
        sb.append("Video modes: ${modes.joinToString { it.label }}\n")
        sb.append("10-bit HLG HDR: ${if (hdrSupported) "yes" else "no"}\n")
        val stabs = chars.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES)?.joinToString { stabName(it) } ?: "?"
        sb.append("Stabilization: $stabs\n")
        sb.append("Shutter priority: ${exposure.shutterPriority}, ISO priority: ${exposure.isoPriority}\n")
        sb.append("ISO ${exposure.isoRange}, shutter ${ExposureControl.formatShutter(exposure.expRange.lower)}–${ExposureControl.formatShutter(exposure.expRange.upper)}\n")
        sb.append("Full-resolution photo mode: ${maxResSize ?: "not offered to apps"}\n")
        if (!photoMode) {
            sb.append("\nAsked for: $wantedLabel\nRunning: ${modeName(modes[modeIndex], sessionHdr)}, ${stabName(sessionStab)}\n")
        }
        val failures = synchronized(failureLog) { failureLog.toList() }
        if (failures.isNotEmpty()) {
            sb.append("\nRecent camera refusals:\n")
            failures.forEach { sb.append("• ").append(it).append("\n") }
        }
        val version = try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (build ${info.longVersionCode})"
        } catch (e: Exception) {
            "?"
        }
        AlertDialog.Builder(this)
            .setTitle("SmoothCam $version · Camera info")
            .setMessage(sb.toString())
            .setPositiveButton("OK", null)
            .show()
    }

    private fun updateStatus() {
        if (photoMode) {
            val size = if (fiftyMpOn) maxResSize ?: photoSize else photoSize
            val mp = size.width * size.height / 1_000_000.0
            val parts = mutableListOf(String.format(Locale.US, "Photo %.0f MP", mp))
            if (measuredFps > 0) parts += String.format(Locale.US, "%.1f fps", measuredFps)
            if (lastIso > 0) parts += "ISO $lastIso"
            statusText.text = parts.joinToString("  ·  ")
            return
        }
        val mode = modes[modeIndex]
        val exp = lastExposureNs
        val parts = mutableListOf(modeName(mode, sessionHdr))
        if (abs(zoomRatio - 1f) > 0.04f) parts += zoomLabel(zoomRatio)
        if (measuredFps > 0) parts += String.format(Locale.US, "%.1f fps", measuredFps)
        if (exp > 0) {
            val denom = (1e9 / exp).roundToInt()
            val angle = (exp.toDouble() * mode.fps * 360.0 / 1e9).roundToInt()
            parts += "1/${denom}s (${angle}°)"
        }
        if (lastIso > 0) parts += "ISO $lastIso"
        statusText.text = parts.joinToString("  ·  ")
    }

    private fun onRecordingChanged() {
        styleRecordButton(recording)
        updateButtons()
        mainHandler.removeCallbacks(timerTick)
        if (recording) {
            recText.visibility = View.VISIBLE
            timerTick.run()
        } else {
            recText.visibility = View.GONE
        }
    }

    private val timerTick = object : Runnable {
        override fun run() {
            val s = (SystemClock.elapsedRealtime() - recordStart) / 1000
            recText.text = String.format(Locale.US, "● %02d:%02d", s / 60, s % 60)
            if (recording) mainHandler.postDelayed(this, 500)
        }
    }

    /** Camera thread. Switch photo mode between ~12 MP (zero lag, multi-frame) and full sensor resolution. */
    private fun toggleMaxRes() {
        if (!photoMode || maxResSize == null || processingPhoto) return
        fiftyMpOn = !fiftyMpOn
        mainHandler.post {
            updateButtons()
            updateStatus()
        }
        startSession()
    }

    /** Camera thread. Switch between 10-bit HLG HDR and SDR (rebuilds the session). */
    private fun toggleHdr() {
        if (recording || !hdrSupported) return
        hdrOn = !hdrOn
        mainHandler.post { updateButtons() }
        restartVideo()
    }

    // ---------------------------------------------------------------- photo mode

    private fun shutterPressed() {
        if (photoMode) cameraHandler.post { takePhoto() } else toggleRecording()
    }

    /** Main thread. Tear down the session, resize the viewfinder, then rebuild once the surface matches. */
    private fun switchCaptureMode(photo: Boolean) {
        if (photo == photoMode || recording || switching) return
        switching = true
        cameraHandler.post {
            try { session?.close() } catch (e: Exception) { /* ignore */ }
            session = null
            closePhotoReader()
            mainHandler.post {
                photoMode = photo
                activeControl = null
                sliderPanel.visibility = View.GONE
                styleRecordButton(false)
                updateButtons()
                statusText.text = if (photo) "Photo" else modeName(modes[modeIndex], hdrOn)
                val size = if (photo) photoPreviewSize else previewSize
                if (surfaceSize == size) {
                    switching = false
                    cameraHandler.post { startSession() }
                } else {
                    awaitingSize = size
                    setPreviewLayout(size)
                }
            }
        }
    }

    private fun setPreviewLayout(size: Size) {
        val w = resources.displayMetrics.widthPixels
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = w * size.width / size.height
        surfaceView.layoutParams = lp
        surfaceView.holder.setFixedSize(size.width, size.height)
    }

    private fun photoRequestBuilder(cam: CameraDevice): CaptureRequest.Builder {
        // The ZSL template keeps every streamed frame at still-photo quality.
        val b = try {
            cam.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
        } catch (e: Exception) {
            cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        }
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, photoFpsRange)
        // No electronic stabilization in photo mode: it would crop the preview differently from the photo.
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        applyOis(b)
        applyZoom(b)
        exposure.apply(b, photoFpsRange.upper, 1_000_000_000L / photoFpsRange.upper, video = false)
        return b
    }

    /**
     * Camera thread. Preview plus a full-resolution YUV stream. The last few YUV frames are kept in a
     * ring buffer, so pressing the shutter uses frames that already exist: no shutter lag.
     */
    private fun startPhotoSession() {
        val cam = device ?: return
        session?.close()
        session = null
        closePhotoReader()
        lastTs = 0L; frameCount = 0; accumNs = 0L

        val maxSize = maxResSize
        val useMax = fiftyMpOn && maxSize != null
        val outputs: List<OutputConfiguration>
        val reader: ImageReader?
        val maxReader: ImageReader?
        if (useMax && maxSize != null) {
            // 50 MP: preview runs in the normal (binned) sensor mode; a separate full-res stream is
            // used only for the shot itself, so there's no zero-lag buffer in this mode.
            reader = null
            maxReader = ImageReader.newInstance(maxSize.width, maxSize.height, ImageFormat.YUV_420_888, 2)
            maxReader.setOnImageAvailableListener({ r -> onMaxResImage(r) }, cameraHandler)
            val previewConfig = OutputConfiguration(surfaceView.holder.surface)
            previewConfig.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_DEFAULT)
            val maxConfig = OutputConfiguration(maxReader.surface)
            maxConfig.addSensorPixelModeUsed(CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            outputs = listOf(previewConfig, maxConfig)
        } else {
            maxReader = null
            reader = ImageReader.newInstance(photoSize.width, photoSize.height, ImageFormat.YUV_420_888, ZSL_MAX_IMAGES)
            reader.setOnImageAvailableListener({ r -> onZslImage(r) }, cameraHandler)
            outputs = listOf(OutputConfiguration(surfaceView.holder.surface), OutputConfiguration(reader.surface))
        }
        photoReader = reader
        maxResReader = maxReader

        val params = photoRequestBuilder(cam)
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            outputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null || photoReader !== reader || maxResReader !== maxReader || !photoMode) {
                        s.close()
                        return
                    }
                    session = s
                    setRepeating(withRecord = false)
                    mainHandler.post {
                        applyFrameRateHint(photoFpsRange.upper)
                        window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                        updateButtons()
                        updateStatus()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (useMax) {
                        fiftyMpOn = false
                        toast("50 MP mode isn't available to apps on this camera")
                        startPhotoSession()
                    } else {
                        showStatus("This camera can't stream full-resolution frames for photo mode")
                    }
                }
            }
        )
        config.setSessionParameters(params.build())
        try {
            cam.createCaptureSession(config)
        } catch (e: Exception) {
            Log.e(TAG, "photo session failed", e)
            showStatus("Photo mode failed to start: ${e.message}")
        }
    }

    /** Camera thread. Keep only the newest frames; fewer while a photo is processing, so the camera never runs out of buffers. */
    private fun onZslImage(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: IllegalStateException) { null } ?: return
        zslRing.addLast(img)
        val cap = if (processingPhoto) 1 else ZSL_RING
        while (zslRing.size > cap) zslRing.removeFirst().close()
    }

    /** Camera thread. Never closes a reader whose frames are still being processed. */
    private fun closePhotoReader() {
        while (zslRing.isNotEmpty()) zslRing.removeFirst().close()
        if (awaitingMaxRes) {
            // A 50 MP shot was in flight and will never arrive now; nothing holds its frames yet.
            awaitingMaxRes = false
            processingPhoto = false
            mainHandler.post { showProcessing(false) }
        }
        for (r in listOfNotNull(photoReader, maxResReader)) {
            r.setOnImageAvailableListener(null, null)
            if (processingPhoto) pendingReaderClose += r else r.close()
        }
        photoReader = null
        maxResReader = null
    }

    private fun closePendingReaders() {
        pendingReaderClose.forEach { it.close() }
        pendingReaderClose.clear()
    }

    /** Camera thread. */
    private fun takePhoto() {
        if (!photoMode || session == null) return
        if (processingPhoto) {
            toast("Still processing the last photo")
            return
        }
        if (maxResReader != null) {
            takeMaxResPhoto()
            return
        }
        if (zslRing.isEmpty()) {
            toast("Camera is still starting")
            return
        }
        val frames = ArrayList<Image>()
        while (zslRing.isNotEmpty() && frames.size < PHOTO_BURST) frames.add(zslRing.removeLast()) // newest first
        while (zslRing.isNotEmpty()) zslRing.removeFirst().close()
        processingPhoto = true
        val iso = lastIso
        val orientation = (sensorOrientation + deviceOrientation) % 360
        mainHandler.post {
            flashPreview()
            showProcessing(true)
        }
        processFrames(frames, iso, orientation)
    }

    /** Camera thread. One full-resolution still; the preview pauses briefly while the sensor switches mode. */
    private fun takeMaxResPhoto() {
        val cam = device ?: return
        val s = session ?: return
        val mr = maxResReader ?: return
        processingPhoto = true
        awaitingMaxRes = true
        maxResIso = lastIso
        maxResOrientation = (sensorOrientation + deviceOrientation) % 360
        mainHandler.post {
            flashPreview()
            showProcessing(true)
        }
        try {
            val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            b.addTarget(mr.surface)
            b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION)
            b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
            b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            applyZoom(b)
            exposure.apply(b, photoFpsRange.upper, 1_000_000_000L / photoFpsRange.upper, video = false)
            s.capture(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    maxResShotFailed("50 MP capture failed (reason ${failure.reason})")
                }
            }, cameraHandler)
            cameraHandler.postDelayed({
                if (awaitingMaxRes) maxResShotFailed("50 MP capture timed out")
            }, 6_000)
        } catch (e: Exception) {
            Log.e(TAG, "max-res capture failed", e)
            maxResShotFailed("50 MP capture failed: ${e.message}")
        }
    }

    /** Camera thread. */
    private fun maxResShotFailed(msg: String) {
        if (!awaitingMaxRes) return
        awaitingMaxRes = false
        processingPhoto = false
        closePendingReaders()
        mainHandler.post { showProcessing(false) }
        toast(msg)
    }

    /** Camera thread. */
    private fun onMaxResImage(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: IllegalStateException) { null } ?: return
        if (!awaitingMaxRes) {
            img.close()
            return
        }
        awaitingMaxRes = false
        processFrames(listOf(img), maxResIso, maxResOrientation)
    }

    /** Hands frames to the photo thread; they are closed when processing is done. */
    private fun processFrames(frames: List<Image>, iso: Int, orientation: Int) {
        photoExecutor.execute {
            val ok = try {
                PhotoProcessor.processAndSave(this, frames, iso, orientation)
            } catch (e: Throwable) {
                Log.e(TAG, "photo processing failed", e)
                false
            } finally {
                frames.forEach { try { it.close() } catch (e: Exception) { /* ignore */ } }
            }
            cameraHandler.post {
                processingPhoto = false
                closePendingReaders()
            }
            mainHandler.post { showProcessing(false) }
            toast(if (ok) "Saved to Pictures/SmoothCam" else "Couldn't save the photo")
        }
    }

    private fun flashPreview() {
        surfaceView.alpha = 0.15f
        surfaceView.animate().alpha(1f).setDuration(180).start()
    }

    private fun showProcessing(on: Boolean) {
        if (recording) return
        if (on) {
            recText.text = "Processing…"
            recText.background = GradientDrawable().apply {
                setColor(Color.argb(170, 0, 0, 0))
                cornerRadius = dp(8).toFloat()
            }
            recText.visibility = View.VISIBLE
        } else {
            recText.visibility = View.GONE
            recText.background = GradientDrawable().apply {
                setColor(Color.rgb(200, 30, 30))
                cornerRadius = dp(8).toFloat()
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private fun showStatus(msg: String) = mainHandler.post { statusText.text = msg }

    private fun toast(msg: String) = mainHandler.post { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    private fun runOnCameraBlocking(block: () -> Unit) {
        if (Looper.myLooper() == cameraThread.looper) {
            block()
            return
        }
        if (!cameraThread.isAlive) return
        val latch = CountDownLatch(1)
        cameraHandler.post {
            try { block() } finally { latch.countDown() }
        }
        latch.await(2, TimeUnit.SECONDS)
    }

    companion object {
        private const val TAG = "SmoothCam"
        private const val REQ_PERMS = 1
        private const val ZSL_MAX_IMAGES = 9  // camera buffers for the full-res stream
        private const val ZSL_RING = 4        // frames kept for the next shot
        private const val PHOTO_BURST = 4     // frames merged per photo
    }
}
