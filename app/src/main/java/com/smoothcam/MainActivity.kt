package com.smoothcam

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.ContentUris
import android.content.ContentValues
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
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
import android.widget.ImageView
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
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.pow
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

    // RAW+ (burst of RAW frames, merged and developed by RawProcessor)
    private var rawSize: Size? = null
    @Volatile private var rawOn = false
    private var rawReader: ImageReader? = null
    private var rawShot: RawShot? = null
    private lateinit var rawButton: Button

    private class RawShot(val n: Int, val boost: Float, val zoom: Float, val orientation: Int, val aspect: Int) {
        val images = HashMap<Long, Image>()
        val results = HashMap<Long, TotalCaptureResult>()
    }

    // Ultrawide fill: the ultrawide lens shown, dimmed, around the photo frame (if the camera allows it)
    // Edge fill: the area around the photo frame shows the best lens that covers it (ultrawide near
    // 1x, the main lens' own full picture once zoomed in), dimmed, if the camera allows extra streams.
    private var lenses: List<LensInfo> = emptyList()
    private var uwLens: LensInfo? = null
    private var mainLens: LensInfo? = null
    private val bgViews = LinkedHashMap<String, SurfaceView>()                      // lens id -> view
    private val bgReady = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    @Volatile private var bgStreams: Set<String> = emptySet() // lenses the running session streams
    @Volatile private var bgSource: String? = null            // the lens filling the edges right now
    @Volatile private var bgTry = 0                            // which set of extra streams to try
    // Measured crop of each edge-fill stream, read back from the camera's per-lens results:
    // [visible fraction along sensor X, along Y, crop-centre offset X, Y (tan units)].
    private val bgCrop = java.util.concurrent.ConcurrentHashMap<String, FloatArray>()
    private val bgRaw = java.util.concurrent.ConcurrentHashMap<String, String>() // raw numbers, for camera info
    private lateinit var scrim: ScrimView

    // Photo aspect ratio: 0 = 4:3, 1 = 16:9, 2 = 1:1
    @Volatile private var photoAspect = 0
    private lateinit var aspectLabel: TextView
    private var zoomAnimator: android.animation.ValueAnimator? = null

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
    @Volatile private var processingPhoto = false  // camera buffers still held for the last shot
    private val photoExecutor = Executors.newSingleThreadExecutor() // look + JPEG, one photo at a time
    private val copyExecutor = Executors.newSingleThreadExecutor()  // copies frames out of camera buffers
    private val shotsInFlight = AtomicInteger(0)                    // taken but not saved yet

    // Gallery button
    @Volatile private var lastMediaUri: Uri? = null
    @Volatile private var lastMediaMime = "image/jpeg"

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
    private lateinit var lensRow: DragRowLayout
    private lateinit var chipRow: LinearLayout
    private lateinit var zoomDial: ZoomDialView
    private lateinit var thumbView: ImageView
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
        if (shotsInFlight.get() == 0) copyExecutor.execute { loadLatestMedia() }
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
        copyExecutor.shutdown()
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
        findLenses()
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

        // RAW+ needs RAW output from the camera.
        rawSize = if (caps.contains(CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW)) {
            map.getOutputSizes(ImageFormat.RAW_SENSOR)?.maxByOrNull { it.width * it.height }
        } else {
            null
        }
        return true
    }

    /** Describe every lens behind the camera (field of view, optical centre) from its calibration. */
    private fun findLenses() {
        try {
            val (list, uw, main) = LensModel.build(cameraManager, chars, zoomRange.lower)
            lenses = list
            uwLens = uw
            mainLens = main
        } catch (e: Exception) {
            Log.w(TAG, "couldn't describe lenses", e)
        }
    }

    /** Lens sets to try streaming for the edge fill, best first; the camera may refuse extra streams. */
    private fun bgCandidates(): List<List<String>> {
        val ids = listOfNotNull(uwLens, mainLens).filter { bgViews.containsKey(it.id) }.map { it.id }.distinct()
        val out = mutableListOf<List<String>>()
        if (ids.size >= 2) out += ids
        ids.forEach { out += listOf(it) }
        return out
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
        if (bgStreams.isNotEmpty()) {
            bgStreams = emptySet()
            bgSource = null
            mainHandler.post { positionBackground() }
        }
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
                val src = bgSource?.takeIf { it in bgStreams && bgReady[it] == true && bgViews.containsKey(it) }
                val b = photoRequestBuilder(cam, setOfNotNull(src))
                b.addTarget(surfaceView.holder.surface)
                photoReader?.let { b.addTarget(it.surface) }
                src?.let { id -> b.addTarget(bgViews.getValue(id).holder.surface) }
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
            bgSource?.let { id -> measureBgCrop(id, result.physicalCameraTotalResults[id]) }

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
                copyExecutor.execute { showMediaThumbnail(uri, "video/mp4") }
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
        // Edge-fill views behind the main viewfinder, one per lens (sized and placed by positionBackground()).
        for (lens in listOfNotNull(uwLens, mainLens).distinctBy { it.id }) {
            val size = lens.bgSize ?: continue
            val v = SurfaceView(this)
            root.addView(v, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.START))
            v.holder.setFixedSize(size.width, size.height)
            val id = lens.id
            v.holder.addCallback(object : SurfaceHolder.Callback {
                override fun surfaceCreated(holder: SurfaceHolder) { bgReady[id] = true }
                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                override fun surfaceDestroyed(holder: SurfaceHolder) { bgReady[id] = false }
            })
            bgViews[id] = v
        }

        surfaceView = SurfaceView(this)
        surfaceView.setZOrderMediaOverlay(true) // main viewfinder above the ultrawide one
        root.addView(surfaceView, FrameLayout.LayoutParams(w, h, Gravity.CENTER))
        surfaceView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> positionBackground() }

        // Dims everything outside the frame that will be captured.
        scrim = ScrimView(this)
        root.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        surfaceView.holder.setFixedSize(previewSize.width, previewSize.height)
        // Pinch to zoom; the camera switches lenses by itself as the zoom ratio crosses them.
        val scaleDetector = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                setZoom(zoomRatio * detector.scaleFactor)
                if (zoomPresets.size > 1) zoomDial.show()
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
        videoSettings.clipChildren = false
        videoSettings.addView(resLabel, LinearLayout.LayoutParams(dp(50), dp(50)))
        videoSettings.addView(fpsLabel, LinearLayout.LayoutParams(dp(50), dp(50)).apply {
            leftMargin = dp(8)
        })
        root.addView(videoSettings, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(92); rightMargin = dp(16) })

        // Lens / zoom presets (0.5x, 1x, 2x, 5x...). Pinch works too.
        lensRow = DragRowLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
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
                setOnClickListener { animateZoomTo(z) }
                setOnLongClickListener {
                    zoomDial.show()
                    true
                }
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
        chipRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            clipChildren = false
        }
        evChip = chip("ev")
        shutterChip = chip("s")
        isoChip = chip("iso")
        for (c in listOf(evChip, shutterChip, isoChip)) {
            chipRow.addView(c, LinearLayout.LayoutParams(dp(60), dp(60)).apply {
                leftMargin = dp(6); rightMargin = dp(6)
            })
        }
        root.addView(chipRow, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        ).apply { bottomMargin = dp(246) })

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
        ).apply { bottomMargin = dp(318); leftMargin = dp(24); rightMargin = dp(24) })

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

        aspectLabel = settingLabel().apply { setOnClickListener { showAspectMenu(it) } }
        root.addView(aspectLabel, FrameLayout.LayoutParams(dp(50), dp(50),
            Gravity.TOP or Gravity.END
        ).apply { topMargin = dp(92); rightMargin = dp(16) })

        rawButton = pillButton().apply {
            text = "RAW+"
            setOnClickListener { cameraHandler.post { toggleRaw() } }
        }
        root.addView(rawButton, FrameLayout.LayoutParams(dp(72), dp(34),
            Gravity.TOP or Gravity.START
        ).apply { topMargin = dp(92); leftMargin = if (maxResSize != null) dp(96) else dp(16) })

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

        // Zoom dial: appears when you drag across or long-press the lens buttons, or pinch.
        zoomDial = ZoomDialView(this).apply {
            minZoom = zoomRange.lower
            maxZoom = zoomRange.upper
            presets = zoomPresets.toList()
            zoom = zoomRatio
            visibility = View.GONE
            onZoomChanged = { z -> setZoom(z) }
            onShown = {
                lensRow.animate().alpha(0f).setDuration(120).start()
                chipRow.animate().alpha(0f).setDuration(120).start()
            }
            onHidden = {
                lensRow.animate().alpha(1f).setDuration(150).start()
                chipRow.animate().alpha(1f).setDuration(150).start()
            }
        }
        root.addView(zoomDial, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, dp(118), Gravity.BOTTOM
        ).apply { bottomMargin = dp(186) })
        lensRow.onDragStart = { x -> zoomDial.beginDrag(x) }
        lensRow.onDragMove = { x -> zoomDial.dragTo(x) }

        // Gallery button: newest photo/video from this app; tap to open it.
        thumbView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                setColor(Color.argb(160, 40, 40, 40))
                cornerRadius = dp(10).toFloat()
                setStroke(dp(2), Color.WHITE)
            }
            clipToOutline = true
            setPadding(dp(2), dp(2), dp(2), dp(2))
            setOnClickListener { openLastMedia() }
        }
        root.addView(thumbView, FrameLayout.LayoutParams(dp(54), dp(54),
            Gravity.BOTTOM or Gravity.START
        ).apply { bottomMargin = dp(67); leftMargin = dp(36) })

        setContentView(root)
        // Only compact (square or round) controls turn with the phone; the PHOTO/VIDEO labels and the
        // slider stay put, as on iPhone.
        rotatables += listOf(recText, hdrButton, maxResButton, rawButton, aspectLabel, resLabel, fpsLabel, evChip, shutterChip, isoChip, thumbView)
        rotatables += lensButtons
        styleRecordButton(false)
        updateButtons()
    }

    private fun settingLabel() = TextView(this).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(Color.WHITE)
        setPadding(0, 0, 0, 0)
        background = GradientDrawable().apply {
            setColor(Color.argb(140, 0, 0, 0))
            cornerRadius = dp(14).toFloat()
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
        rawButton.visibility = if (photoMode && rawSize != null) View.VISIBLE else View.GONE
        aspectLabel.visibility = if (photoMode) View.VISIBLE else View.GONE
        aspectLabel.text = ASPECTS[photoAspect]
        stylePill(rawButton, rawOn)
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

    // ---------------------------------------------------------------- photo aspect ratio

    private fun showAspectMenu(anchor: View) {
        val pm = PopupMenu(this, anchor)
        ASPECTS.forEachIndexed { i, label ->
            pm.menu.add(0, i, i, label).apply {
                isCheckable = true
                isChecked = i == photoAspect
            }
        }
        pm.setOnMenuItemClickListener { item ->
            setAspect(item.itemId)
            true
        }
        pm.show()
    }

    /** Main thread. Only the framing changes; the camera keeps running. */
    private fun setAspect(a: Int) {
        if (a == photoAspect) return
        photoAspect = a
        setPreviewLayout(photoPreviewSize)
        positionBackground()
        updateButtons()
    }

    // ---------------------------------------------------------------- zoom

    /** Main thread. Jump to a zoom (pinch, dial). Cancels a running lens animation. */
    private fun setZoom(z: Float) {
        zoomAnimator?.cancel()
        applyZoomRatio(z)
    }

    private fun applyZoomRatio(z: Float) {
        zoomRatio = z.coerceIn(zoomRange.lower, zoomRange.upper)
        zoomDial.zoom = zoomRatio
        updateLensButtons()
        positionBackground()
        requestRepeatingUpdate()
    }

    /** Main thread. Glide to a lens preset along a smooth zoom curve, like iPhone's lens buttons. */
    private fun animateZoomTo(target: Float) {
        zoomAnimator?.cancel()
        val start = zoomRatio
        val end = target.coerceIn(zoomRange.lower, zoomRange.upper)
        if (abs(end - start) < 0.01f) return
        val ls = ln(start.toDouble())
        val le = ln(end.toDouble())
        zoomAnimator = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener { va ->
                val t = va.animatedValue as Float
                applyZoomRatio(exp(ls + (le - ls) * t).toFloat())
            }
            start()
        }
    }

    /**
     * Camera thread. Read back what part of its sensor an edge-fill lens actually streamed (the
     * camera may crop it, e.g. by applying the zoom to it too) and re-align if that changed.
     */
    private fun measureBgCrop(id: String, pr: CaptureResult?) {
        if (pr == null) return
        val lens = lenses.firstOrNull { it.id == id } ?: return
        val active = lens.chars.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE) ?: return
        val crop = pr.get(CaptureResult.SCALER_CROP_REGION) ?: return
        val zr = pr.get(CaptureResult.CONTROL_ZOOM_RATIO) ?: 1f
        if (active.width() <= 0 || active.height() <= 0 || zr <= 0f) return
        val fx = crop.width().toFloat() / active.width() / zr
        val fy = crop.height().toFloat() / active.height() / zr
        bgRaw[id] = "crop ${crop.width()}×${crop.height()} at (${crop.left},${crop.top}), " +
            String.format(Locale.US, "zoom %.2f, ", zr) + "sensor ${active.width()}×${active.height()}"
        // Crop-region coordinates start at the active array's top-left corner.
        val ox = (crop.exactCenterX() - active.width() / 2f) / (active.width() / 2f) * lens.tanX
        val oy = (crop.exactCenterY() - active.height() / 2f) / (active.height() / 2f) * lens.tanY
        val old = bgCrop[id]
        if (old == null || abs(old[0] - fx) > 0.01f || abs(old[1] - fy) > 0.01f ||
            abs(old[2] - ox) > 0.002f || abs(old[3] - oy) > 0.002f
        ) {
            bgCrop[id] = floatArrayOf(fx, fy, ox, oy)
            Log.i(TAG, String.format(Locale.US, "edge fill %s: camera streams %.0f%% x %.0f%% of the lens", id, fx * 100, fy * 100))
            mainHandler.post { positionBackground() }
        }
    }

    /** The part of the viewfinder that will be captured (photo aspect ratio applied). */
    private fun captureRect(): android.graphics.Rect {
        val l = surfaceView.left
        val t = surfaceView.top
        val r = surfaceView.right
        val b = surfaceView.bottom
        val cx = (l + r) / 2
        val cy = (t + b) / 2
        if (!photoMode) return android.graphics.Rect(l, t, r, b)
        return when (photoAspect) {
            1 -> { // 16:9 photo = 9:16 in portrait: crop the sides of the 3:4 viewfinder
                val half = ((b - t) * 9 / 16) / 2
                android.graphics.Rect(cx - half, t, cx + half, b)
            }
            2 -> { // 1:1
                val half = (r - l) / 2
                android.graphics.Rect(l, cy - half, r, cy + half)
            }
            else -> android.graphics.Rect(l, t, r, b)
        }
    }

    /**
     * Main thread. Choose the lens that fills the edges and line its picture up with the viewfinder.
     *
     * The viewfinder shows the main lens zoomed by z, i.e. a field of view of main.tan / z. A lens
     * can fill the screen around it if its field of view is at least that times how far the screen
     * reaches beyond the viewfinder. Of the lenses that can, the narrowest (sharpest) one is used:
     * the ultrawide near 1x, the main lens' own uncropped picture once zoomed in further.
     * Each lens' picture is scaled by its field of view and shifted by its optical centre.
     */
    private fun positionBackground() {
        if (!::scrim.isInitialized) return
        val hole = captureRect()
        scrim.setHole(hole.left, hole.top, hole.right, hole.bottom)

        val main = mainLens
        val fw = surfaceView.width.toFloat()
        val fh = surfaceView.height.toFloat()
        var chosen: LensInfo? = null
        if (photoMode && main != null && fw > 0f && fh > 0f && bgStreams.isNotEmpty()) {
            val z = zoomRatio
            val cx = surfaceView.left + fw / 2f
            val cy = surfaceView.top + fh / 2f
            // How far the screen reaches beyond the viewfinder, in viewfinder half-sizes.
            // Portrait: the viewfinder's height is the sensor's long side (X), its width the short side (Y).
            val needX = maxOf(cy, rootView.height - cy) / (fh / 2f)
            val needY = maxOf(cx, rootView.width - cx) / (fw / 2f)
            val frameTanX = main.tanX / z
            val frameTanY = main.tanY / z
            // What each lens' stream really shows: its field of view, reduced by any crop the camera applies.
            fun tX(l: LensInfo) = l.tanX * (bgCrop[l.id]?.get(0) ?: 1f)
            fun tY(l: LensInfo) = l.tanY * (bgCrop[l.id]?.get(1) ?: 1f)
            fun oX(l: LensInfo) = l.offX - (bgCrop[l.id]?.get(2) ?: 0f)
            fun oY(l: LensInfo) = l.offY - (bgCrop[l.id]?.get(3) ?: 0f)
            val streamed = lenses.filter { it.id in bgStreams }.sortedBy { tX(it) }
            chosen = streamed.firstOrNull { tX(it) / frameTanX >= needX && tY(it) / frameTanY >= needY }
                ?: streamed.lastOrNull()?.takeIf { tX(it) / frameTanX > 1.03f } // best effort: widest
            if (chosen != null) {
                val sx = tY(chosen) / frameTanY // width scale
                val sy = tX(chosen) / frameTanX // height scale
                val bw = fw * sx
                val bh = fh * sy
                // Optical-centre alignment, converted from sensor axes to portrait screen axes.
                val dBufX = (main.offX - oX(chosen)) * (fh / 2f) / frameTanX
                val dBufY = (main.offY - oY(chosen)) * (fw / 2f) / frameTanY
                val dxs = if (sensorOrientation == 270) dBufY else -dBufY
                val dys = if (sensorOrientation == 270) -dBufX else dBufX
                val v = bgViews[chosen.id]
                if (v != null) {
                    val lp = v.layoutParams as FrameLayout.LayoutParams
                    lp.width = bw.toInt()
                    lp.height = bh.toInt()
                    lp.leftMargin = (cx - bw / 2f + dxs).toInt()
                    lp.topMargin = (cy - bh / 2f + dys).toInt()
                    v.layoutParams = lp
                }
            }
        }
        // Park the views that aren't in use.
        for ((id, v) in bgViews) {
            if (id == chosen?.id) continue
            val lp = v.layoutParams as FrameLayout.LayoutParams
            if (lp.width != 1) {
                lp.width = 1; lp.height = 1; lp.leftMargin = 0; lp.topMargin = 0
                v.layoutParams = lp
            }
        }
        val newSource = chosen?.id
        if (newSource != bgSource) {
            bgSource = newSource
            requestRepeatingUpdate() // stream only the lens in use
        }
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
        val evText = "EV\n${exposure.evLabel(exposure.evIndex)}"
        val sText = if (sAuto) "S\nAuto" else "S\n${exposure.shutterLabel(exposure.shutter, fps).substringBefore(" (")}"
        val isoText = exposure.iso?.let { "ISO\n$it" } ?: "ISO\nAuto"
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
            cornerRadius = dp(14).toFloat()
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
        sb.append("RAW: ${rawSize ?: "not offered to apps"}\n")
        sb.append("Lenses: ${if (lenses.isEmpty()) "none offered to apps" else lenses.joinToString("; ")}\n")
        val physKeys = try { chars.availablePhysicalCameraRequestKeys } catch (e: Exception) { null } ?: emptyList()
        sb.append("Per-lens settings accepted: zoom ${physKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)}, " +
            "crop ${physKeys.contains(CaptureRequest.SCALER_CROP_REGION)}\n")
        bgRaw.forEach { (id, raw) -> sb.append("Lens $id reports: $raw\n") }
        sb.append("Edge fill: " + when {
            bgViews.isEmpty() -> "no extra lens streams offered to apps"
            !photoMode -> "photo mode only"
            bgStreams.isEmpty() -> "camera refused the extra streams"
            else -> "streaming ${bgStreams.joinToString()}, showing ${bgSource ?: "none (frame already covers the screen)"}" +
                bgCrop.entries.joinToString("") { (id, c) ->
                    String.format(Locale.US, "; lens %s streams %.0f%% of its view", id, c[0] * 100)
                }
        } + "\n")
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
            val size = if (fiftyMpOn) maxResSize ?: photoSize else if (rawOn) rawSize ?: photoSize else photoSize
            val mp = size.width * size.height / 1_000_000.0
            val parts = mutableListOf(String.format(Locale.US, if (rawOn) "RAW+ %.0f MP" else "Photo %.0f MP", mp))
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
        if (fiftyMpOn) rawOn = false
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
                    setPreviewLayout(size) // the framing may still differ (photo aspect ratio)
                    cameraHandler.post { startSession() }
                } else {
                    awaitingSize = size
                    setPreviewLayout(size)
                }
            }
        }
    }

    private fun setPreviewLayout(size: Size) {
        // For 16:9 photos the viewfinder is widened so the 9:16 capture area spans the screen width
        // (its sides run off-screen, like on iPhone).
        val screenW = resources.displayMetrics.widthPixels
        val w = if (photoMode && photoAspect == 1) screenW * 4 / 3 else screenW
        val lp = surfaceView.layoutParams as FrameLayout.LayoutParams
        lp.width = w
        lp.height = w * size.width / size.height
        lp.gravity = Gravity.CENTER
        surfaceView.layoutParams = lp
        surfaceView.holder.setFixedSize(size.width, size.height)
    }

    private fun photoRequestBuilder(cam: CameraDevice, physicalIds: Set<String> = emptySet()): CaptureRequest.Builder {
        // The ZSL template keeps every streamed frame at still-photo quality.
        val b = try {
            if (physicalIds.isEmpty()) cam.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG)
            else cam.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG, physicalIds)
        } catch (e: Exception) {
            if (physicalIds.isEmpty()) cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            else cam.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW, physicalIds)
        }
        // The zoom is for the main viewfinder only: ask for the edge-fill lenses' full, uncropped view.
        val physKeys = try { chars.availablePhysicalCameraRequestKeys } catch (e: Exception) { null } ?: emptyList()
        for (id in physicalIds) {
            try {
                if (physKeys.contains(CaptureRequest.CONTROL_ZOOM_RATIO)) {
                    b.setPhysicalCameraKey(CaptureRequest.CONTROL_ZOOM_RATIO, 1f, id)
                }
                val active = lenses.firstOrNull { it.id == id }?.chars?.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
                if (active != null && physKeys.contains(CaptureRequest.SCALER_CROP_REGION)) {
                    b.setPhysicalCameraKey(CaptureRequest.SCALER_CROP_REGION, android.graphics.Rect(0, 0, active.width(), active.height()), id)
                }
            } catch (e: Exception) {
                Log.w(TAG, "per-lens settings not accepted for $id", e)
            }
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
        val rSize = rawSize
        val useRaw = rawOn && rSize != null && !useMax
        val outputs: List<OutputConfiguration>
        val reader: ImageReader?
        val maxReader: ImageReader?
        var newRaw: ImageReader? = null
        if (useRaw && rSize != null) {
            // RAW+: viewfinder plus a RAW stream that only receives the burst when you press the shutter.
            reader = null
            maxReader = null
            val rr = ImageReader.newInstance(rSize.width, rSize.height, ImageFormat.RAW_SENSOR, RAW_BURST_MAX + 2)
            rr.setOnImageAvailableListener({ r -> onRawImage(r) }, cameraHandler)
            newRaw = rr
            outputs = listOf(OutputConfiguration(surfaceView.holder.surface), OutputConfiguration(rr.surface))
        } else if (useMax && maxSize != null) {
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
        // Optional extra streams for the edge fill (one per lens); the camera may refuse them.
        val bgIds = if (useMax) emptyList() else (bgCandidates().getOrNull(bgTry) ?: emptyList()).filter { bgReady[it] == true }
        val useBg = bgIds.isNotEmpty()
        val allOutputs = outputs + bgIds.map { id ->
            OutputConfiguration(bgViews.getValue(id).holder.surface).apply { setPhysicalCameraId(id) }
        }
        fun dropBgAndRetry(why: String) {
            logFailure("Edge fill with lens ${bgIds.joinToString()} not available: $why")
            bgTry++
            reader?.close()
            maxReader?.close()
            newRaw?.close()
            photoReader = null
            maxResReader = null
            rawReader = null
            startPhotoSession()
        }
        photoReader = reader
        maxResReader = maxReader
        val rawR = newRaw
        rawReader = rawR

        val params = photoRequestBuilder(cam)
        val config = SessionConfiguration(
            SessionConfiguration.SESSION_REGULAR,
            allOutputs,
            cameraExecutor,
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    if (device == null || photoReader !== reader || maxResReader !== maxReader || rawReader !== rawR || !photoMode) {
                        s.close()
                        return
                    }
                    session = s
                    bgStreams = bgIds.toSet()
                    bgSource = null
                    mainHandler.post { positionBackground() }
                    setRepeating(withRecord = false)
                    mainHandler.post {
                        positionBackground()
                        applyFrameRateHint(photoFpsRange.upper)
                        window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                        updateButtons()
                        updateStatus()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    if (useBg) {
                        dropBgAndRetry("the camera rejected the extra stream")
                    } else if (useRaw) {
                        logFailure("Camera rejected the RAW+ session")
                        rawOn = false
                        toast("RAW+ isn't available on this camera")
                        startPhotoSession()
                    } else if (useMax) {
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
            if (useBg) {
                val supported = try { cam.isSessionConfigurationSupported(config) } catch (e: Exception) { true }
                if (!supported) {
                    dropBgAndRetry("the camera reports the extra stream as unsupported")
                    return
                }
            }
            cam.createCaptureSession(config)
        } catch (e: Exception) {
            if (useBg) {
                dropBgAndRetry("${e.javaClass.simpleName} ${e.message}")
                return
            }
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
            shotsInFlight.decrementAndGet()
            mainHandler.post { showProcessing(false) }
        }
        rawShot?.let { shot ->
            // A RAW burst was in flight; it can't complete now.
            shot.images.values.forEach { it.close() }
            rawShot = null
            processingPhoto = false
            shotsInFlight.decrementAndGet()
            mainHandler.post { showProcessing(false) }
        }
        for (r in listOfNotNull(photoReader, maxResReader, rawReader)) {
            r.setOnImageAvailableListener(null, null)
            if (processingPhoto) pendingReaderClose += r else r.close()
        }
        photoReader = null
        maxResReader = null
        rawReader = null
    }

    private fun closePendingReaders() {
        pendingReaderClose.forEach { it.close() }
        pendingReaderClose.clear()
    }

    /**
     * Camera thread. Shooting is instant: the frames are copied out of the camera's buffers right
     * away (about a tenth of a second) and processed in the background, so you can keep shooting.
     */
    private fun takePhoto() {
        if (!photoMode || session == null) return
        if (processingPhoto) {
            toast("One moment…")
            return
        }
        if (rawReader != null) {
            takeRawPhoto()
            return
        }
        if (maxResReader != null) {
            if (shotsInFlight.get() > 0) {
                toast("Wait for the last photo to finish saving")
                return
            }
            takeMaxResPhoto()
            return
        }
        if (shotsInFlight.get() >= MAX_QUEUED_SHOTS) {
            toast("Still saving the last photos…")
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
        shotsInFlight.incrementAndGet()
        val iso = lastIso
        val orientation = (sensorOrientation + deviceOrientation) % 360
        mainHandler.post {
            flashPreview()
            showProcessing(true)
        }
        processFrames(frames, iso, orientation)
    }

    /**
     * Camera thread. RAW+: shoot a short burst of RAW frames, slightly underexposed so highlights
     * survive (the merge removes the extra noise and the developer restores the brightness).
     */
    private fun takeRawPhoto() {
        val cam = device ?: return
        val s = session ?: return
        val rr = rawReader ?: return
        if (rawShot != null || shotsInFlight.get() > 0) {
            toast("Still saving the last photo")
            return
        }
        val manual = !exposure.isFullyAuto
        val baseExp = if (lastExposureNs > 0) lastExposureNs else 16_666_666L
        val baseIso = if (lastIso > 0) lastIso else 400
        val dark = baseIso >= 1600
        val n = if (dark) RAW_BURST_MAX else 6
        val under = if (manual) 0f else if (dark) 0.5f else 1f
        val factor = 2.0.pow(-under.toDouble())
        var e = (baseExp * factor).toLong()
        var iso = baseIso
        if (e < exposure.expRange.lower) {
            iso = (baseIso * baseExp * factor / exposure.expRange.lower).roundToInt()
                .coerceIn(exposure.isoRange.lower, exposure.isoRange.upper)
            e = exposure.expRange.lower
        }
        val requests = try {
            (0 until n).map {
                val b = cam.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                b.addTarget(rr.surface)
                b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
                b.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                b.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CameraMetadata.STATISTICS_LENS_SHADING_MAP_MODE_ON)
                applyZoom(b)
                if (manual) {
                    exposure.apply(b, photoFpsRange.upper, 33_333_333L, video = false)
                } else {
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF)
                    b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, e)
                    b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
                    b.set(CaptureRequest.SENSOR_FRAME_DURATION, maxOf(e, 33_333_333L))
                }
                b.build()
            }
        } catch (ex: Exception) {
            toast("RAW capture failed: ${ex.message}")
            return
        }
        val shot = RawShot(n, 2.0.pow(under.toDouble()).toFloat(), zoomRatio, (sensorOrientation + deviceOrientation) % 360, photoAspect)
        rawShot = shot
        processingPhoto = true
        shotsInFlight.incrementAndGet()
        mainHandler.post {
            flashPreview()
            showProcessing(true)
        }
        try {
            s.captureBurst(requests, object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
                    val ts = result.get(CaptureResult.SENSOR_TIMESTAMP) ?: return
                    if (rawShot !== shot) return
                    shot.results[ts] = result
                    completeRawShotIfReady()
                }

                override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
                    failRawShot(shot, "RAW capture failed (reason ${failure.reason})")
                }
            }, cameraHandler)
        } catch (ex: Exception) {
            failRawShot(shot, "RAW capture failed: ${ex.message}")
            return
        }
        cameraHandler.postDelayed({ if (rawShot === shot) failRawShot(shot, "RAW capture timed out") }, 6_000)
    }

    /** Camera thread. */
    private fun onRawImage(r: ImageReader) {
        val img = try { r.acquireNextImage() } catch (e: IllegalStateException) { null } ?: return
        val shot = rawShot
        if (shot == null || shot.images.size >= shot.n) {
            img.close()
            return
        }
        shot.images[img.timestamp] = img
        completeRawShotIfReady()
    }

    /** Camera thread. Images and their metadata arrive separately; go on once every frame has both. */
    private fun completeRawShotIfReady() {
        val shot = rawShot ?: return
        if (shot.images.size < shot.n || shot.results.size < shot.n) return
        val keys = shot.images.keys.filter { shot.results.containsKey(it) }.sorted()
        if (keys.size < shot.n) return
        rawShot = null
        val images = keys.map { shot.images.getValue(it) }
        // RAW frames come uncropped from whichever lens took them: crop by the zoom relative to that
        // lens, and develop with that lens' own colour filter layout and levels.
        val result = shot.results.getValue(keys[0])
        val activeId = result.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID)
        val lens = lenses.firstOrNull { it.id == activeId } ?: mainLens
        val crop = shot.zoom / (lens?.base ?: 1f)
        Log.i(TAG, "RAW+ from lens ${activeId ?: "?"} (base ${lens?.base}), crop $crop")
        val meta = RawProcessor.Meta.from(lens?.chars ?: chars, result, shot.boost, crop, shot.aspect)
        copyExecutor.execute {
            val frames = try {
                images.map<Image, RawProcessor.RawFrame?> { RawProcessor.RawFrame.copyOf(it) }.toMutableList()
            } catch (e: Throwable) {
                Log.e(TAG, "copying RAW frames failed", e)
                null
            } finally {
                images.forEach { try { it.close() } catch (e: Exception) { /* ignore */ } }
            }
            cameraHandler.post {
                processingPhoto = false
                closePendingReaders()
            }
            if (frames == null) {
                shotsInFlight.decrementAndGet()
                mainHandler.post { showProcessing(false) }
                toast("Couldn't capture the RAW burst")
                return@execute
            }
            photoExecutor.execute {
                val t0 = SystemClock.elapsedRealtime()
                val uri = try {
                    RawProcessor.processAndSave(this, frames, meta, shot.orientation)
                } catch (e: Throwable) {
                    Log.e(TAG, "RAW processing failed", e)
                    null
                }
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                shotsInFlight.decrementAndGet()
                if (uri != null) showMediaThumbnail(uri, "image/jpeg")
                mainHandler.post { showProcessing(false) }
                toast(if (uri != null) String.format(Locale.US, "RAW+ photo saved (%.1f s)", secs) else "Couldn't develop the RAW photo")
            }
        }
    }

    /** Camera thread. */
    private fun failRawShot(shot: RawShot, msg: String) {
        if (rawShot !== shot) return
        rawShot = null
        shot.images.values.forEach { it.close() }
        processingPhoto = false
        shotsInFlight.decrementAndGet()
        closePendingReaders()
        mainHandler.post { showProcessing(false) }
        logFailure(msg)
        toast(msg)
    }

    /** Camera thread. Switch RAW+ on or off (photo mode). */
    private fun toggleRaw() {
        if (!photoMode || rawSize == null || processingPhoto || shotsInFlight.get() > 0) return
        rawOn = !rawOn
        if (rawOn) fiftyMpOn = false
        mainHandler.post {
            updateButtons()
            updateStatus()
        }
        startSession()
    }

    /** Camera thread. One full-resolution still; the preview pauses briefly while the sensor switches mode. */
    private fun takeMaxResPhoto() {
        val cam = device ?: return
        val s = session ?: return
        val mr = maxResReader ?: return
        processingPhoto = true
        shotsInFlight.incrementAndGet()
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
        shotsInFlight.decrementAndGet()
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

    /**
     * Copies the frames out of the camera's buffers (and returns the buffers), shows a quick
     * thumbnail, then queues the real processing. Takes ownership of the Images.
     */
    private fun processFrames(frames: List<Image>, iso: Int, orientation: Int, aspect: Int = photoAspect) {
        val t0 = SystemClock.elapsedRealtime()
        copyExecutor.execute {
            val copies = try {
                frames.map { PhotoProcessor.YuvFrame.copyOf(it) }
            } catch (e: Throwable) {
                Log.e(TAG, "copying frames failed", e)
                null
            } finally {
                frames.forEach { try { it.close() } catch (e: Exception) { /* ignore */ } }
            }
            cameraHandler.post {
                processingPhoto = false
                closePendingReaders()
            }
            if (copies == null) {
                shotsInFlight.decrementAndGet()
                mainHandler.post { showProcessing(false) }
                toast("Couldn't capture the photo")
                return@execute
            }
            try {
                val thumb = PhotoProcessor.quickThumbnail(copies[0], orientation)
                mainHandler.post { setThumbnail(thumb) }
            } catch (e: Throwable) {
                Log.w(TAG, "quick thumbnail failed", e)
            }
            photoExecutor.execute {
                val uri = try {
                    PhotoProcessor.processAndSave(this, copies, iso, orientation, aspect)
                } catch (e: Throwable) {
                    Log.e(TAG, "photo processing failed", e)
                    null
                }
                val secs = (SystemClock.elapsedRealtime() - t0) / 1000.0
                Log.i(TAG, String.format(Locale.US, "photo saved in %.2f s", secs))
                shotsInFlight.decrementAndGet()
                if (uri != null) {
                    lastMediaUri = uri
                    lastMediaMime = "image/jpeg"
                }
                mainHandler.post { showProcessing(false) }
                if (uri == null) toast("Couldn't save the photo")
            }
        }
    }

    private fun flashPreview() {
        surfaceView.alpha = 0.15f
        surfaceView.animate().alpha(1f).setDuration(180).start()
    }

    /** Main thread. Small "Saving…" badge while photos are being processed in the background. */
    @Suppress("UNUSED_PARAMETER")
    private fun showProcessing(on: Boolean) {
        if (recording) return
        val n = shotsInFlight.get()
        if (n > 0) {
            recText.text = if (n > 1) "Saving $n photos…" else "Saving…"
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

    // ---------------------------------------------------------------- gallery button

    private fun setThumbnail(bmp: Bitmap?) {
        if (bmp == null) {
            thumbView.setImageDrawable(null)
            return
        }
        thumbView.setImageBitmap(bmp)
        thumbView.scaleX = 0.85f
        thumbView.scaleY = 0.85f
        thumbView.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    /** Background thread. Find the newest photo or video this app saved and show it on the gallery button. */
    private fun loadLatestMedia() {
        try {
            fun newest(collection: Uri, dateCol: String, pathCol: String): Pair<Uri, Long>? {
                contentResolver.query(
                    collection, arrayOf(MediaStore.MediaColumns._ID, dateCol),
                    "$pathCol LIKE ?", arrayOf("%SmoothCam%"), "$dateCol DESC"
                )?.use { c ->
                    if (c.moveToFirst()) return ContentUris.withAppendedId(collection, c.getLong(0)) to c.getLong(1)
                }
                return null
            }
            val img = newest(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.RELATIVE_PATH)
            val vid = newest(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.DATE_ADDED,
                MediaStore.Video.Media.RELATIVE_PATH)
            val pick = listOfNotNull(img?.let { Triple(it.first, it.second, "image/jpeg") },
                vid?.let { Triple(it.first, it.second, "video/mp4") }).maxByOrNull { it.second } ?: return
            showMediaThumbnail(pick.first, pick.third)
        } catch (e: Exception) {
            Log.w(TAG, "couldn't load latest media", e)
        }
    }

    /** Background thread. */
    private fun showMediaThumbnail(uri: Uri, mime: String) {
        lastMediaUri = uri
        lastMediaMime = mime
        val bmp = try { contentResolver.loadThumbnail(uri, Size(160, 160), null) } catch (e: Exception) { null }
        mainHandler.post { setThumbnail(bmp) }
    }

    private fun openLastMedia() {
        val uri = lastMediaUri
        if (uri == null) {
            toast(if (shotsInFlight.get() > 0) "Still saving…" else "No photos or videos yet")
            return
        }
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, lastMediaMime)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            startActivity(intent)
        } catch (e: Exception) {
            toast("No gallery app found to open it")
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
        private val ASPECTS = arrayOf("4:3", "16:9", "1:1")
        private const val RAW_BURST_MAX = 8   // RAW frames in dark scenes (6 otherwise)
        private const val MAX_QUEUED_SHOTS = 3 // photos waiting to be saved (each holds ~75 MB)
    }
}
