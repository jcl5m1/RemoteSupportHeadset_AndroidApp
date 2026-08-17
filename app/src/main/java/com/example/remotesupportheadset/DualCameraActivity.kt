package com.example.remotesupportheadset

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.RectF
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.location.Location
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.MediaScannerConnection
import android.media.audiofx.Visualizer
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.WindowManager
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.View
import android.widget.Button
import androidx.appcompat.widget.PopupMenu
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.ICameraStateCallBack
import com.jiangdg.ausbc.callback.ICaptureCallBack
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.callback.IPreviewDataCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.camera.bean.PreviewSize
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView
import com.serenegiant.usb.USBMonitor
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import java.util.zip.ZipInputStream

class DualCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DualCameraActivity"
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val REQUEST_RECORD_PERMISSION = 1002
        private const val CDC_TIMEOUT_MS = 5000
        private const val CAPTURE_TIMEOUT_MS = 120000
        private const val FPS_UPDATE_INTERVAL_MS = 1000L
        private const val DIAGNOSTICS_UPDATE_INTERVAL_MS = 2000L
        private const val CAPTURE_DEBOUNCE_MS = 1000L
        private const val LIFECYCLE_CAPTURE_INTERVAL_MS = 3000L
        private const val LIFECYCLE_CAPTURE_COUNT = 20
        private const val DEVICE_POLL_INTERVAL_MS = 1500L
        private const val CAMERA_HEALTH_CHECK_INTERVAL_MS = 1000L
        private const val CAMERA_FRAME_TIMEOUT_MS = 4000L
        private const val CAPTURE_GRACE_PERIOD_MS = 15000L
        private const val FIRMWARE_VERSION_INTERVAL_MS = 5000L

        private const val ACTION_USB_FLASH_PERMISSION = "com.example.remotesupportheadset.USB_FLASH_PERMISSION"
        private const val ESPRESSIF_VID = 0x303A
        private const val ESPRESSIF_UVC_CDC_PID = 0x4022
        private const val ESPRESSIF_DOWNLOAD_PID = 0x0012

        /** Intent extra that starts the ESP32 flash flow without showing the confirmation dialog. */
        const val EXTRA_FLASH_NOW = "flash_now"
        /** Intent extra that provides a firmware .zip URL to download before flashing. */
        const val EXTRA_FIRMWARE_URL = "firmware_url"
        /** Intent extra that triggers a single still capture immediately. */
        const val EXTRA_CAPTURE_NOW = "capture_now"
        /** Intent extra that tags a simulated capture event with an index for timing stats. */
        const val EXTRA_SIMULATED_CAPTURE_INDEX = "simulated_capture_index"
        /** Intent extra that runs the still-capture lifecycle test with the given count. */
        const val EXTRA_LIFECYCLE_TEST_COUNT = "lifecycle_test_count"
        /** Intent extra that runs capture→zoom→close cycles instead of plain captures. */
        const val EXTRA_LIFECYCLE_ZOOM_TEST = "lifecycle_zoom_test"
        /** Intent extra that opens the zoom overlay for the last captured image. */
        const val EXTRA_ZOOM_OPEN = "zoom_open"
        /** Intent extra that closes the zoom overlay. */
        const val EXTRA_ZOOM_CLOSE = "zoom_close"
    }

    private lateinit var surfaceCamera: AspectRatioSurfaceView
    private lateinit var aprilTagOverlay: AprilTagOverlayView
    private lateinit var statusCamera: TextView
    private lateinit var labelCamera: TextView
    private lateinit var tapHint: TextView
    private lateinit var diagnosticsPanel: ScrollView
    private lateinit var diagnosticsText: TextView
    private lateinit var settingsButton: Button
    private lateinit var recordToggle: Button
    private lateinit var thumbnailLastCapture: ImageView
    private lateinit var thumbnailLabel: TextView
    private lateinit var zoomOverlay: View
    private lateinit var zoomImage: PinchZoomPanImageView
    private lateinit var micLevelMeter: ProgressBar
    private lateinit var micLevelLabel: TextView
    private lateinit var spkLevelMeter: ProgressBar
    private lateinit var spkLevelLabel: TextView
    private lateinit var firmwareVersionLabel: TextView

    private var cameraClient: MultiCameraClient? = null
    private var currentCamera: MultiCameraClient.Camera? = null
    private var currentDevice: UsbDevice? = null
    private var currentCtrlBlock: USBMonitor.UsbControlBlock? = null

    private val pendingPermissionDevices = mutableListOf<UsbDevice>()
    private var isRequestingPermission = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideHintRunnable = Runnable { tapHint.visibility = View.GONE }

    // CDC state for still capture
    private var cdcConnection: UsbDeviceConnection? = null
    private var cdcDataInterface: UsbInterface? = null
    private var cdcControlInterface: UsbInterface? = null
    private var cdcOutEndpoint: UsbEndpoint? = null
    private var cdcInEndpoint: UsbEndpoint? = null
    private val captureLock = ReentrantLock()
    private var isCapturing = false
    private var lastCaptureEndTime = 0L

    // Lifecycle / stress-test state
    private var lifecycleTestRunning = false
    private var lifecycleSuccess = 0
    private var lifecycleFail = 0
    private var lifecycleTestThread: Thread? = null

    // Simulated capture timing instrumentation.
    // When the host drives captures via ADB intents, these track the index and
    // timestamps so the host can histogram tap→complete and tap→resume times.
    private var simulatedCaptureIndex = -1
    private var simulatedCaptureStartTime = 0L
    private var waitingForStreamResume = false

    private var lastCaptureAttemptTime = 0L

    private var wakeLock: PowerManager.WakeLock? = null

    // FPS / diagnostics counters
    private val frameCount = AtomicLong(0)
    private val lastFpsReset = AtomicLong(SystemClock.elapsedRealtime())
    private var currentFps = 0
    private var diagnosticsVisible = false
    private var lastFrameTime = 0L
    private var cameraOpenedTime = 0L
    private var lastRecoveryTime = 0L
    private var recoveryAttempts = 0
    private var permissionRequestStartTime = 0L

    // Software-defined camera preview rotation. Default to 0°; use the Rotate button if the
    // physical module is mounted differently.
    private var cameraPreviewRotation = 0f

    // Horizontal mirror of the live preview. Some UVC modules deliver a left-right mirrored
    // stream; toggling this mirrors the preview surface so the on-screen preview looks correct.
    private var cameraPreviewMirrorH = false

    private var lastCapturedFile: File? = null
    private var lastCapturedThumbnail: android.graphics.Bitmap? = null
    private var lastAnnotatedFull: android.graphics.Bitmap? = null
    private var lastDebugPreviewSaveTime = 0L
    private val aprilTagDetector by lazy { AprilTagDetector() }
    private val aprilTagTracker = AprilTagTracker()

    // Latest NV21 preview frame from the camera callback. We keep a small queue
    // so the detection thread can consume frames at its own rate without blocking
    // the camera callback thread.
    private val previewFrameQueue = ArrayBlockingQueue<PreviewFrame>(2)
    private var previewFrameWidth = 0
    private var previewFrameHeight = 0
    private data class PreviewFrame(val data: ByteArray, val width: Int, val height: Int)

    // 3x3 colour-correction matrix computed from a detected Macbeth chart.
    private var colorCorrectionMatrix: FloatArray? = null
    private var colorCorrectionEnabled = false

    // Runtime CDC command helper (used by analysis tools and still-capture helpers).
    private val cdcCommandHelper by lazy { CdcCommandHelper(this) }

    // Valid AprilTag IDs for the corner markers of the Macbeth chart layouts
    // (DICT_APRILTAG_16H5, chart sizes 3x3 through 4x6).
    private val MACBETH_CORNER_IDS = setOf(
        0, 1, 2, 3, 4, 5, 6, 7, 8, 9,
        10, 11, 12, 13, 14, 15, 16, 17, 18, 19
    )

    private var aprilTagThread: android.os.HandlerThread? = null
    private var aprilTagHandler: Handler? = null
    private var aprilTagCycleCount = 0L
    private var aprilTagLastSummaryTime = 0L

    private val aprilTagRunnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val cycleStart = SystemClock.elapsedRealtime()
            updateLiveAprilTagOverlay()
            val elapsed = SystemClock.elapsedRealtime() - cycleStart
            aprilTagCycleCount++
            val now = SystemClock.elapsedRealtime()
            if (now - aprilTagLastSummaryTime >= 1000L) {
                Log.d(TAG, "AprilTag rate: $aprilTagCycleCount cycles/s, last cycle=${elapsed}ms")
                aprilTagCycleCount = 0
                aprilTagLastSummaryTime = now
            }
            // The old 200 ms delay artificially limited live detection to 5 Hz.
            // Now schedule the next frame as soon as possible, capped at ~30 Hz
            // so we do not waste CPU redetecting the same preview frame.
            aprilTagHandler?.postDelayed(this, maxOf(0L, 33L - elapsed))
        }
    }

    // Video recording state
    private enum class RecordingState { IDLE, RECORDING, PAUSED }
    private var recordingState = RecordingState.IDLE
    private val recordedSegments = mutableListOf<File>()
    private var currentSegmentIndex = 0
    private var currentRecordingFile: File? = null
    private var recordingStartTime = 0L
    private var pendingStartRecording = false

    // Firmware flash state
    private var flashInProgress = false
    private var flashDialog: AlertDialog? = null
    private var flashProgressBar: ProgressBar? = null
    private var flashProgressMessage: TextView? = null
    private var flashProgressBytes: TextView? = null
    private var flashReceiver: BroadcastReceiver? = null
    private val pendingFlashFiles = mutableListOf<Pair<String, Int>>()
    private var flashTotalBytes = 0L
    private var flashProgressBytesWritten = 0L

    // Cached firmware build version reported by the ESP32 over CDC.
    @Volatile
    private var firmwareBuildVersion: String? = null
    private val firmwareVersionRunnable = object : Runnable {
        override fun run() {
            queryFirmwareBuildVersion()
            mainHandler.postDelayed(this, FIRMWARE_VERSION_INTERVAL_MS)
        }
    }

    // Cached location for geotagging photos/videos. Updated whenever getCurrentLocation()
    // successfully reads a fresh last-known location. Volatile so background capture
    // threads and the main/UI thread see the same value.
    @Volatile
    private var cachedLocation: Location? = null
    @Volatile
    private var cachedLocationTime = 0L

    // Live-view thumbnail update (shown when the still-image zoom overlay is open)
    private var isZoomOpen = false
    private val thumbnailUpdateHandler = Handler(Looper.getMainLooper())
    private val thumbnailUpdateRunnable = object : Runnable {
        override fun run() {
            if (isZoomOpen && !isFinishing) {
                updateThumbnailWithLiveFrame()
                thumbnailUpdateHandler.postDelayed(this, 150)
            }
        }
    }

    // Microphone VU meter
    private var audioRecord: AudioRecord? = null
    private var micMeterThread: Thread? = null
    @Volatile
    private var micMeterRunning = false
    private val audioBufferLock = Object()
    private var audioBuffer = ShortArray(0)

    // Speaker / global output VU meter (Visualizer on audio session 0)
    private var visualizer: Visualizer? = null
    private var spkMeterThread: Thread? = null
    @Volatile
    private var spkMeterRunning = false

    // Cached diagnostics data (populated when a camera connects)
    private var cachedSupportedSizes = ""
    private var cachedUacChannels = -1
    private var cachedUsbDeviceInfo = ""
    private var cachedUsbInterfaces = ""
    private val fpsRunnable = object : Runnable {
        override fun run() {
            computeFps()
            mainHandler.postDelayed(this, FPS_UPDATE_INTERVAL_MS)
        }
    }
    private val diagnosticsRunnable = object : Runnable {
        override fun run() {
            updateDiagnostics()
            mainHandler.postDelayed(this, DIAGNOSTICS_UPDATE_INTERVAL_MS)
        }
    }

    /**
     * Fallback polling runnable. AndroidUSBCamera's dynamic attach receiver does
     * not always fire for devices that are already connected when the app starts
     * (or for rapid reconnections). Poll getDeviceList() while no camera is open
     * so the permission request is issued as soon as the device appears.
     */
    private val devicePollRunnable = object : Runnable {
        override fun run() {
            if (!isFinishing) {
                if (currentCamera == null) {
                    if (currentDevice != null) {
                        Log.w(TAG, "Polling: USB device present but camera not open: ${currentDevice?.deviceName}")
                    }
                    // If a permission request has been outstanding for too long, the
                    // library may have dropped it. Reset the flag so the next poll
                    // can retry.
                    if (isRequestingPermission &&
                        SystemClock.elapsedRealtime() - permissionRequestStartTime > 10000L) {
                        Log.w(TAG, "Permission request timed out; resetting to retry")
                        isRequestingPermission = false
                    }
                    updateDeviceList()
                }
            }
            mainHandler.postDelayed(this, DEVICE_POLL_INTERVAL_MS)
        }
    }

    /**
     * Watchdog that verifies preview frames keep arriving. If frames stop for
     * longer than [CAMERA_FRAME_TIMEOUT_MS] the camera stack is considered dead
     * and [recoverCamera] is triggered.
     *
     * The check is deferred during a still capture and for [CAPTURE_GRACE_PERIOD_MS]
     * afterwards, because the firmware pauses the UVC preview stream while it
     * captures and transfers the full-resolution JPEG. Triggering recovery during
     * that window aborts the capture and can leave the USB stack in a bad state.
     */
    private val cameraHealthCheckRunnable = object : Runnable {
        override fun run() {
            if (isFinishing) return
            val camera = currentCamera
            val now = SystemClock.elapsedRealtime()
            if (camera != null) {
                val sinceLastCapture = now - lastCaptureEndTime
                val inCaptureGracePeriod = isCapturing || (lastCaptureEndTime > 0 && sinceLastCapture < CAPTURE_GRACE_PERIOD_MS)
                if (inCaptureGracePeriod) {
                    Log.v(TAG, "Camera health check deferred: isCapturing=$isCapturing, sinceLastCapture=${sinceLastCapture}ms")
                } else {
                    val sinceLastFrame = now - lastFrameTime
                    val sinceOpen = now - cameraOpenedTime
                    val framesStopped = lastFrameTime > 0 && sinceLastFrame > CAMERA_FRAME_TIMEOUT_MS
                    val neverDeliveredFrames = lastFrameTime == 0L && cameraOpenedTime > 0 && sinceOpen > CAMERA_FRAME_TIMEOUT_MS
                    if (framesStopped || neverDeliveredFrames) {
                        Log.w(TAG, "Camera health check FAILED: framesStopped=$framesStopped, neverDeliveredFrames=$neverDeliveredFrames, sinceLastFrame=$sinceLastFrame, sinceOpen=$sinceOpen")
                        recoverCamera()
                    } else {
                        Log.v(TAG, "Camera health check OK: sinceLastFrame=$sinceLastFrame, sinceOpen=$sinceOpen")
                    }
                }
            } else {
                Log.v(TAG, "Camera health check: no camera open")
            }
            mainHandler.postDelayed(this, CAMERA_HEALTH_CHECK_INTERVAL_MS)
        }
    }

    private val previewDataCallback = object : IPreviewDataCallBack {
        override fun onPreviewData(data: ByteArray?, format: IPreviewDataCallBack.DataFormat) {
            frameCount.incrementAndGet()
            lastFrameTime = SystemClock.elapsedRealtime()

            // If we are waiting for the first preview frame after a simulated
            // capture, log the stream-resume time.
            if (waitingForStreamResume) {
                val idx = simulatedCaptureIndex
                if (idx >= 0 && simulatedCaptureStartTime > 0L) {
                    val elapsed = SystemClock.elapsedRealtime() - simulatedCaptureStartTime
                    Log.i(TAG, "SIMCAP resumed i=$idx t=${SystemClock.elapsedRealtime()} dt_resume=${elapsed}ms")
                }
                waitingForStreamResume = false
            }

            val previewSize = currentCamera?.getPreviewSize()
            if (data != null && previewSize != null) {
                previewFrameWidth = previewSize.width
                previewFrameHeight = previewSize.height
                // Keep only the latest frame for detection. Copy the data because the
                // library may reuse the underlying buffer.
                val copy = data.copyOf()
                previewFrameQueue.poll()?.let { }
                previewFrameQueue.offer(PreviewFrame(copy, previewSize.width, previewSize.height))
                // Log a sample frame every ~60 frames (about once per second).
                val count = frameCount.get()
                if (count % 60L == 0L) {
                    Log.v(TAG, "Preview frame sample [$count]: ${previewSize.width}x${previewSize.height}, " +
                            "data=${data.size}, format=$format")
                }
            }
        }
    }

    private var pendingCameraSetup: PendingCameraSetup? = null
    private data class PendingCameraSetup(
        val camera: MultiCameraClient.Camera,
        val request: CameraRequest,
        val ctrlBlock: USBMonitor.UsbControlBlock?
    )

    private val surfaceCallback = object : SurfaceHolder.Callback {
        override fun surfaceCreated(holder: SurfaceHolder) {
            Log.d(TAG, "Preview surface created")
            pendingCameraSetup?.let { setup ->
                openCameraWithSetup(setup)
                pendingCameraSetup = null
            }
        }

        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            Log.d(TAG, "Preview surface changed: ${width}x${height}")
        }

        override fun surfaceDestroyed(holder: SurfaceHolder) {
            Log.d(TAG, "Preview surface destroyed")
            currentCamera?.closeCamera()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_camera)

        hideSystemUI()
        keepScreenOn()

        surfaceCamera = findViewById(R.id.surface_camera)
        statusCamera = findViewById(R.id.status_camera)
        labelCamera = findViewById(R.id.label_camera)
        tapHint = findViewById(R.id.tap_hint)
        diagnosticsPanel = findViewById(R.id.diagnostics_panel)
        diagnosticsText = findViewById(R.id.diagnostics_text)
        settingsButton = findViewById(R.id.settings_button)
        recordToggle = findViewById(R.id.record_toggle)
        thumbnailLastCapture = findViewById(R.id.thumbnail_last_capture)
        thumbnailLabel = findViewById(R.id.thumbnail_label)
        zoomOverlay = findViewById(R.id.zoom_overlay)
        zoomImage = findViewById(R.id.zoom_image)
        micLevelMeter = findViewById(R.id.mic_level_meter)
        micLevelLabel = findViewById(R.id.mic_level_label)
        spkLevelMeter = findViewById(R.id.spk_level_meter)
        spkLevelLabel = findViewById(R.id.spk_level_label)
        firmwareVersionLabel = findViewById(R.id.firmware_version_label)
        aprilTagOverlay = findViewById(R.id.apriltag_overlay)

        // SurfaceView renders the camera stream directly; the overlay View sits above it.
        surfaceCamera.setZOrderMediaOverlay(true)
        surfaceCamera.holder.addCallback(surfaceCallback)

        applyPreviewRotation()
        startLiveAprilTagDetection()

        // Tap anywhere on the preview to capture a still image (debounced)
        surfaceCamera.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                captureStillImage()
            }
            true
        }

        settingsButton.setOnClickListener {
            showSettingsMenu()
        }

        recordToggle.setOnClickListener {
            when (recordingState) {
                RecordingState.IDLE -> startRecording()
                else -> stopRecording()
            }
        }

        thumbnailLastCapture.setOnClickListener {
            if (isZoomOpen) {
                hideZoomOverlay()
            } else {
                lastCapturedFile?.let { showZoomOverlay(it) }
            }
        }

        // Show the tap hint briefly, then fade it
        mainHandler.postDelayed(hideHintRunnable, 8000L)

        checkAndRequestPermissions()

        // Allow an external caller (e.g. adb from a MacBook) to start flashing,
        // capture, zoom, or lifecycle tests via intent extras.  [onNewIntent]
        // handles the same extras when the activity is already running.
        handleIntentActions(intent)

    }

    private fun initCameraClient() {
        if (cameraClient != null) return

        // Wrap the Activity Context so AndroidUSBCamera 3.2.7's USBMonitor can call
        // registerReceiver() on Android 14+ without crashing.
        val wrappedContext = ReceiverExportWorkaroundContext(this)

        cameraClient = MultiCameraClient(wrappedContext, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                Log.d(TAG, "USB device attached: ${device.deviceName} vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)}")
                if (isEspDownloadModeDevice(device)) {
                    Log.d(TAG, "Ignoring attached ESP32 download-mode device; flash flow handles it separately")
                    return
                }
                runOnUiThread {
                    Toast.makeText(this@DualCameraActivity, "Attached: ${device.deviceName}", Toast.LENGTH_SHORT).show()
                }
                queuePermissionRequest(device)
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                isRequestingPermission = false

                if (currentCamera != null) {
                    Log.d(TAG, "Camera already open; ignoring ${device.deviceName}")
                    processNextPermission()
                    return
                }

                runOnUiThread {
                    currentDevice = device
                    currentCtrlBlock = ctrlBlock

                    applyPreviewRotation()

                    val camera = MultiCameraClient.Camera(this@DualCameraActivity, device)
                    camera.setUsbControlBlock(ctrlBlock)
                    camera.setCameraStateCallBack(object : ICameraStateCallBack {
                        override fun onCameraState(
                            self: MultiCameraClient.Camera,
                            code: ICameraStateCallBack.State,
                            msg: String?
                        ) {
                            Log.d(TAG, "Camera state: $code, msg=$msg")
                            if (code == ICameraStateCallBack.State.OPENED) {
                                cameraOpenedTime = SystemClock.elapsedRealtime()
                                lastFrameTime = 0L
                                Thread { populateDiagnosticsCache(device, ctrlBlock) }.start()
                                runOnUiThread { applyPreviewRotation() }

                            } else if (code == ICameraStateCallBack.State.ERROR || code == ICameraStateCallBack.State.CLOSED) {
                                Log.e(TAG, "Camera state error/closed: $code, msg=$msg")
                                recoverCamera()
                            }
                        }
                    })
                    camera.addPreviewDataCallBack(previewDataCallback)

                    val request = CameraRequest.Builder()
                        .setPreviewWidth(PREVIEW_WIDTH)
                        .setPreviewHeight(PREVIEW_HEIGHT)
                        .create()

                    val setup = PendingCameraSetup(camera, request, ctrlBlock)
                    if (surfaceCamera.holder.surface?.isValid == true) {
                        openCameraWithSetup(setup)
                    } else {
                        pendingCameraSetup = setup
                        Log.d(TAG, "Camera setup pending until SurfaceView is ready")
                    }

                    statusCamera.visibility = View.GONE
                    labelCamera.text = "${PREVIEW_WIDTH}x${PREVIEW_HEIGHT} @ -- FPS"

                    // Try to claim the CDC interface on the same composite device
                    ctrlBlock?.let { setupCdc(device, it) }

                    processNextPermission()
                }
            }

            override fun onCancelDev(device: UsbDevice?) {
                Log.w(TAG, "USB permission cancelled: ${device?.deviceName} vid=0x${device?.vendorId?.toString(16)} pid=0x${device?.productId?.toString(16)}")
                isRequestingPermission = false
                runOnUiThread {
                    statusCamera.text = "Permission denied\nTap to try again..."
                    statusCamera.setOnClickListener {
                        device?.let { queuePermissionRequest(it) }
                    }
                    processNextPermission()
                }
            }

            override fun onDetachDec(device: UsbDevice?) {
                device ?: return
                Log.w(TAG, "USB device detached: ${device.deviceName} vid=0x${device.vendorId.toString(16)} pid=0x${device.productId.toString(16)}")
                pendingPermissionDevices.removeAll { it.deviceId == device.deviceId }
                runOnUiThread {
                    if (currentDevice?.deviceId == device.deviceId) {
                        currentCamera?.closeCamera()
                        currentCamera = null
                        currentDevice = null
                        currentCtrlBlock = null
                        cameraOpenedTime = 0L
                        releaseCdc()

                        // If a capture was in progress it is now aborted.
                        isCapturing = false

                        // Detached camera means no AprilTag history is valid anymore.
                        aprilTagTracker.reset()

                        statusCamera.visibility = View.VISIBLE
                        statusCamera.text = "Waiting for camera..."
                        labelCamera.text = "Waiting..."
                        tapHint.visibility = View.VISIBLE
                        mainHandler.removeCallbacks(hideHintRunnable)
                        mainHandler.postDelayed(hideHintRunnable, 8000L)
                    }
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                Log.w(TAG, "Camera disconnected: ${device?.deviceName} vid=0x${device?.vendorId?.toString(16)} pid=0x${device?.productId?.toString(16)}")
                if (currentDevice?.deviceId == device?.deviceId) {
                    aprilTagTracker.reset()
                    recoverCamera()
                }
            }
        })

        cameraClient?.register()
        updateDeviceList()
    }

    private fun setupCdc(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock) {
        try {
            val connection = ctrlBlock.connection ?: return
            cdcConnection = connection

            // Find CDC control and data interfaces
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                when (iface.interfaceClass) {
                    UsbConstants.USB_CLASS_COMM -> cdcControlInterface = iface
                    UsbConstants.USB_CLASS_CDC_DATA -> cdcDataInterface = iface
                }
            }

            val controlIface = cdcControlInterface
            val dataIface = cdcDataInterface

            if (controlIface == null || dataIface == null) {
                Log.w(TAG, "CDC interfaces not found on ${device.deviceName}")
                return
            }

            // Claim the data interface through the shared control block
            ctrlBlock.claimInterface(dataIface, true)

            // Locate bulk endpoints on the data interface
            for (i in 0 until dataIface.endpointCount) {
                val ep = dataIface.getEndpoint(i)
                if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                    when (ep.direction) {
                        UsbConstants.USB_DIR_OUT -> cdcOutEndpoint = ep
                        UsbConstants.USB_DIR_IN -> cdcInEndpoint = ep
                    }
                }
            }

            if (cdcOutEndpoint == null || cdcInEndpoint == null) {
                Log.w(TAG, "CDC bulk endpoints not found")
                return
            }

            // Standard CDC ACM init: 115200 8N1, DTR/RTS asserted
            setCdcLineCoding(controlIface, 115200, 0, 0, 8)
            setCdcControlLineState(controlIface, dtr = true, rts = true)

            Log.d(TAG, "CDC interface ready on ${device.deviceName}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set up CDC", e)
        }
    }

    /**
     * Re-claim CDC interfaces and refresh endpoint state. Call this when a
     * capture transfer fails because the device may have reset its interface
     * state while keeping the same USB connection.
     */
    private fun refreshCdcState() {
        val device = currentDevice ?: return
        val ctrlBlock = currentCtrlBlock ?: return
        Log.d(TAG, "Refreshing CDC state...")
        releaseCdc()
        setupCdc(device, ctrlBlock)
        Log.d(TAG, "CDC refresh: control=${cdcControlInterface != null}, data=${cdcDataInterface != null}, out=${cdcOutEndpoint != null}, in=${cdcInEndpoint != null}")
    }

    private fun setCdcLineCoding(controlInterface: UsbInterface, baud: Int, stopBits: Int, parity: Int, dataBits: Int) {
        val conn = cdcConnection ?: return
        val payload = byteArrayOf(
            (baud and 0xFF).toByte(),
            ((baud shr 8) and 0xFF).toByte(),
            ((baud shr 16) and 0xFF).toByte(),
            ((baud shr 24) and 0xFF).toByte(),
            stopBits.toByte(),
            parity.toByte(),
            dataBits.toByte()
        )
        // 0x21 = host-to-device | class | interface recipient
        conn.controlTransfer(0x21, 0x20, 0, controlInterface.id, payload, payload.size, CDC_TIMEOUT_MS)
    }

    private fun setCdcControlLineState(controlInterface: UsbInterface, dtr: Boolean, rts: Boolean) {
        val conn = cdcConnection ?: return
        var value = 0
        if (dtr) value = value or 0x01
        if (rts) value = value or 0x02
        // 0x21 = host-to-device | class | interface recipient
        conn.controlTransfer(0x21, 0x22, value, controlInterface.id, null, 0, CDC_TIMEOUT_MS)
    }

    private fun releaseCdc() {
        try {
            val ctrlBlock = currentCtrlBlock
            val dataIface = cdcDataInterface
            if (ctrlBlock != null && dataIface != null) {
                ctrlBlock.releaseInterface(dataIface)
            }
        } catch (_: Exception) { }
        cdcConnection = null
        cdcControlInterface = null
        cdcDataInterface = null
        cdcOutEndpoint = null
        cdcInEndpoint = null
    }

    /**
     * Attempt to recover a dead or hung camera stack.
     *
     * This closes the current camera, releases the CDC path, clears the cached
     * device state, and re-queues a permission request for the same device so
     * the library re-opens the camera without requiring a manual reconnect.
     * Recovery is throttled to avoid tight restart loops.
     */
    private fun recoverCamera() {
        if (isFinishing) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastRecoveryTime < 15000L) {
            Log.d(TAG, "Recovery throttled, last attempt ${now - lastRecoveryTime}ms ago")
            return
        }
        lastRecoveryTime = now
        recoveryAttempts++

        val camera = currentCamera
        val device = currentDevice
        Log.w(TAG, "RECOVER CAMERA attempt #$recoveryAttempts: camera=${camera != null}, device=${device?.deviceName}, lastFrame=${now - lastFrameTime}ms ago")

        runOnUiThread {
            try {
                camera?.closeCamera()
            } catch (e: Exception) {
                Log.w(TAG, "Exception closing camera during recovery", e)
            }
            currentCamera = null
            currentDevice = null
            currentCtrlBlock = null
            cameraOpenedTime = 0L
            releaseCdc()
            isCapturing = false

            statusCamera.visibility = View.VISIBLE
            statusCamera.text = "Camera stack dead — recovering..."
            labelCamera.text = "Waiting..."
            Log.d(TAG, "Camera state cleared for recovery")
        }

        // Re-trigger device discovery. If the same device is still attached, request
        // permission again so the library re-opens it.
        pendingPermissionDevices.clear()
        isRequestingPermission = false
        val client = cameraClient
        val attachedDevice = client?.getDeviceList()?.firstOrNull()
        if (attachedDevice != null) {
            Log.d(TAG, "Re-queueing permission for ${attachedDevice.deviceName} after recovery")
            queuePermissionRequest(attachedDevice)
        } else {
            Log.w(TAG, "No USB device found during recovery; polling will retry")
        }
    }

    /**
     * User-facing single still capture. Debounced so rapid taps don't queue
     * overlapping captures.
     *
     * @param simulatedIndex If non-negative, the capture is tagged as a simulated
     *   tap from the host stress-test script and timing statistics are emitted.
     */
    private fun captureStillImage(simulatedIndex: Int = -1) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastCaptureAttemptTime < CAPTURE_DEBOUNCE_MS) {
            Log.d(TAG, "Capture debounced")
            return
        }
        lastCaptureAttemptTime = now

        if (lifecycleTestRunning) {
            Toast.makeText(this, "Lifecycle test running; tap Test 20 to stop", Toast.LENGTH_SHORT).show()
            return
        }

        if (simulatedIndex >= 0) {
            simulatedCaptureIndex = simulatedIndex
            simulatedCaptureStartTime = SystemClock.elapsedRealtime()
            waitingForStreamResume = false
            // Do not set waitingForStreamResume=true here; the preview callback may
            // still receive frames for a short time before the firmware pauses
            // the stream for the still capture. We mark the resume-wait only
            // after the capture session ends (see captureStillImageWithRetries).
            Log.i(TAG, "SIMCAP start i=$simulatedIndex t=${simulatedCaptureStartTime}")
        }

        Thread { captureStillImageWithRetries(3, simulatedIndex = simulatedIndex) }
            .apply { name = "StillCaptureThread"; start() }
    }

    /**
     * Run a lifecycle stress test of [count] still captures, spaced
     * [LIFECYCLE_CAPTURE_INTERVAL_MS] apart. Results are toasted and logged.
     *
     * The test waits for the CDC path to be ready before each capture. If the
     * device resets mid-test, the loop pauses and resumes after reconnection.
     *
     * When [zoomMode] is true each iteration performs capture → open zoom → close
     * zoom with human-like delays, matching the manual tap scenario the user is
     * trying to stabilise.
     */
    private fun runLifecycleTest(count: Int, zoomMode: Boolean = false) {
        if (lifecycleTestRunning) return
        lifecycleTestRunning = true
        lifecycleSuccess = 0
        lifecycleFail = 0
        val modeLabel = if (zoomMode) "capture→zoom→close" else "capture"
        Toast.makeText(this, "Starting $count $modeLabel lifecycle test", Toast.LENGTH_SHORT).show()
        Log.i(TAG, "Lifecycle test START: count=$count, zoomMode=$zoomMode")

        lifecycleTestThread = Thread {
            val start = SystemClock.elapsedRealtime()
            for (i in 1..count) {
                try {
                    if (!lifecycleTestRunning) {
                        Log.i(TAG, "Lifecycle test cancelled at $i")
                        break
                    }

                    // Wait for CDC / camera to be ready. This handles device resets
                    // and permission delays that happen right after launch.
                    if (!waitForCdcReady(30000L, exitIfTestStopped = true)) {
                        lifecycleFail++
                        Log.w(TAG, "Lifecycle test $i/$count: CDC not ready after wait")
                        runOnUiThread {
                            Toast.makeText(this, "CDC not ready, skipping $i", Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }

                    // After a device reset the preview may need a moment to resume.
                    // Wait until frames are flowing before triggering a still capture.
                    if (!waitForStablePreview(10000L)) {
                        lifecycleFail++
                        Log.w(TAG, "Lifecycle test $i/$count: preview not stable after wait")
                        runOnUiThread {
                            Toast.makeText(this, "Preview not stable, skipping $i", Toast.LENGTH_SHORT).show()
                        }
                        continue
                    }

                    runOnUiThread {
                        Toast.makeText(this, "Capture $i/$count", Toast.LENGTH_SHORT).show()
                    }
                    val ok = captureStillImageWithRetries(3, cancelCheck = { !lifecycleTestRunning })
                    if (ok) {
                        lifecycleSuccess++
                        if (zoomMode) {
                            // Give the thumbnail/generation thread a moment, then open zoom.
                            Thread.sleep(800)
                            runOnUiThread {
                                Toast.makeText(this, "Zoom $i/$count", Toast.LENGTH_SHORT).show()
                                lastCapturedFile?.let { showZoomOverlay(it) }
                            }
                            // Keep the zoom open briefly, then close it.
                            Thread.sleep(1500)
                            runOnUiThread { hideZoomOverlay() }
                            Thread.sleep(500)
                        }
                    } else {
                        lifecycleFail++
                    }
                    Log.i(TAG, "Lifecycle test progress $i/$count ok=$ok (success=$lifecycleSuccess fail=$lifecycleFail)")
                    if (i < count && lifecycleTestRunning) {
                        Thread.sleep(LIFECYCLE_CAPTURE_INTERVAL_MS)
                    }
                } catch (e: InterruptedException) {
                    Log.i(TAG, "Lifecycle test interrupted at $i")
                    lifecycleTestRunning = false
                    break
                } catch (e: Exception) {
                    lifecycleFail++
                    Log.e(TAG, "Lifecycle test $i/$count unexpected error", e)
                }
            }
            val elapsed = SystemClock.elapsedRealtime() - start
            lifecycleTestRunning = false
            runOnUiThread {
                val summary = "Lifecycle test done: $lifecycleSuccess/$count in ${elapsed}ms (fail=$lifecycleFail)"
                Toast.makeText(this, summary, Toast.LENGTH_LONG).show()
                Log.i(TAG, summary)
            }
        }.apply {
            isDaemon = true
            name = "LifecycleCaptureThread"
            start()
        }
    }

    /**
     * Block until the CDC path is ready, or [timeoutMs] elapses. Returns true
     * if CDC endpoints are available when this returns. If [exitIfTestStopped]
     * is true, returns early when the lifecycle test is no longer running.
     */
    private fun waitForCdcReady(timeoutMs: Long, exitIfTestStopped: Boolean = false): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (cdcOutEndpoint != null && cdcInEndpoint != null && cdcConnection != null) {
                return true
            }
            if (exitIfTestStopped && !lifecycleTestRunning) return false
            Log.d(TAG, "Waiting for CDC ready...")
            refreshCdcState()
            Thread.sleep(500)
        }
        return cdcOutEndpoint != null && cdcInEndpoint != null && cdcConnection != null
    }

    /**
     * Block until the UVC preview is streaming (FPS > 0) for at least 500 ms,
     * or [timeoutMs] elapses. This prevents triggering a still capture while
     * the device is still recovering from a reset.
     */
    private fun waitForStablePreview(timeoutMs: Long): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var firstPositiveFpsTime = 0L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!lifecycleTestRunning) return false
            if (currentFps > 0) {
                if (firstPositiveFpsTime == 0L) {
                    firstPositiveFpsTime = SystemClock.elapsedRealtime()
                } else if (SystemClock.elapsedRealtime() - firstPositiveFpsTime >= 500L) {
                    return true
                }
            } else {
                firstPositiveFpsTime = 0L
            }
            Thread.sleep(200)
        }
        return currentFps > 0
    }

    private fun stopLifecycleTest() {
        lifecycleTestRunning = false
        lifecycleTestThread?.interrupt()
        lifecycleTestThread = null
        Toast.makeText(this, "Lifecycle test stopped", Toast.LENGTH_SHORT).show()
    }

    // -------------------------------------------------------------------------
    // Video recording (segment-based pause/resume)
    // -------------------------------------------------------------------------

    private fun startRecording() {
        if (!ensureRecordingPermissions()) {
            pendingStartRecording = true
            return
        }
        val camera = currentCamera ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (recordingState != RecordingState.IDLE) return

        recordedSegments.clear()
        currentSegmentIndex = 0
        recordingStartTime = System.currentTimeMillis()
        currentRecordingFile = getVideoOutputFile(currentSegmentIndex)

        val callback = object : ICaptureCallBack {
            override fun onBegin() {
                Log.d(TAG, "Recording started: ${currentRecordingFile?.name}")
            }
            override fun onError(error: String?) {
                if (isHarmlessRecordingError(error)) {
                    Log.d(TAG, "Recording non-fatal error: $error")
                    return
                }
                Log.e(TAG, "Recording error: $error")
                runOnUiThread {
                    recordingState = RecordingState.IDLE
                    updateRecordButton()
                    Toast.makeText(this@DualCameraActivity, "Record error: $error", Toast.LENGTH_LONG).show()
                }
            }
            override fun onComplete(path: String?) {
                Log.d(TAG, "Recording segment complete: $path")
                path?.let { recordedSegments.add(File(it)) }
            }
        }

        currentRecordingFile?.absolutePath?.let { path ->
            camera.captureVideoStart(callback, path, 0L)
            recordingState = RecordingState.RECORDING
            updateRecordButton()
            Toast.makeText(this, "Recording started", Toast.LENGTH_SHORT).show()
        }
    }

    private fun isHarmlessRecordingError(error: String?): Boolean {
        return error?.contains("Mutation of _data", ignoreCase = true) == true
    }

    private fun pauseRecording() {
        val camera = currentCamera ?: return
        if (recordingState != RecordingState.RECORDING) return

        camera.captureVideoStop()
        recordingState = RecordingState.PAUSED
        updateRecordButton()
        Toast.makeText(this, "Recording paused", Toast.LENGTH_SHORT).show()
    }

    private fun resumeRecording() {
        val camera = currentCamera ?: run {
            Toast.makeText(this, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }
        if (recordingState != RecordingState.PAUSED) return

        currentSegmentIndex++
        currentRecordingFile = getVideoOutputFile(currentSegmentIndex)

        val callback = object : ICaptureCallBack {
            override fun onBegin() {
                Log.d(TAG, "Recording resumed: ${currentRecordingFile?.name}")
            }
            override fun onError(error: String?) {
                if (isHarmlessRecordingError(error)) {
                    Log.d(TAG, "Resume non-fatal error: $error")
                    return
                }
                Log.e(TAG, "Resume recording error: $error")
                runOnUiThread {
                    recordingState = RecordingState.PAUSED
                    updateRecordButton()
                    Toast.makeText(this@DualCameraActivity, "Resume error: $error", Toast.LENGTH_LONG).show()
                }
            }
            override fun onComplete(path: String?) {
                Log.d(TAG, "Recording segment complete: $path")
                path?.let { recordedSegments.add(File(it)) }
            }
        }

        currentRecordingFile?.absolutePath?.let { path ->
            camera.captureVideoStart(callback, path, 0L)
            recordingState = RecordingState.RECORDING
            updateRecordButton()
            Toast.makeText(this, "Recording resumed", Toast.LENGTH_SHORT).show()
        }
    }

    private fun stopRecording() {
        val camera = currentCamera ?: return
        if (recordingState == RecordingState.IDLE) return

        camera.captureVideoStop()
        recordingState = RecordingState.IDLE
        updateRecordButton()

        // The library's MediaStore insert fails on Android 14, but the MP4 files are still
        // written. Fall back to scanning the output directory for files created during this
        // session so the user gets an accurate summary.
        val appDir = getVideoOutputFile(0).parentFile
        val sessionFiles = appDir?.listFiles()?.filter { file ->
            file.name.startsWith("VID_") && file.extension == "mp4" && file.lastModified() >= recordingStartTime
        }?.sortedBy { it.lastModified() } ?: emptyList()

        if (sessionFiles.isEmpty()) {
            Toast.makeText(this, "Recording stopped (no files found)", Toast.LENGTH_LONG).show()
            return
        }

        val publicUris = mutableListOf<Uri>()
        sessionFiles.forEach { file ->
            copyVideoToMediaStore(file)?.let { uri ->
                publicUris.add(uri)
                Log.i(TAG, "Copied to MediaStore: ${file.name} -> $uri")
            } ?: Log.w(TAG, "Failed to copy ${file.name} to MediaStore")
        }

        val message = if (sessionFiles.size > 1) {
            "Saved ${sessionFiles.size} video segments"
        } else {
            "Saved video: ${sessionFiles[0].name}"
        }
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.i(TAG, message)
        sessionFiles.forEach { Log.i(TAG, "  ${it.name} ${it.length()} bytes") }

        // Open the most recent segment in the default gallery/photos app.
        publicUris.lastOrNull()?.let { openVideoInGallery(it) }
    }

    // -------------------------------------------------------------------------
    // ESP32-P4 firmware flashing over USB-OTG
    // -------------------------------------------------------------------------

    private fun queryFirmwareBuildVersion() {
        Thread {
            val helper = CdcCommandHelper(this)
            val version = try {
                if (helper.open()) {
                    val response = helper.queryBuildVersion()
                    helper.close()
                    Log.d(TAG, "Firmware version raw response: '$response'")
                    parseBuildVersion(response)
                } else {
                    Log.d(TAG, "Could not open CDC port for firmware version query")
                    null
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to query firmware build version", e)
                null
            }
            version?.let { firmwareBuildVersion = it }
            if (!isFinishing) {
                runOnUiThread {
                    firmwareVersionLabel.text = "FW: ${version ?: "--"}"
                }
            }
        }.apply { name = "FirmwareVersionQueryThread"; start() }
    }

    private fun parseBuildVersion(response: String?): String? {
        if (response.isNullOrBlank()) return null
        // Expect "BUILD_VERSION 20260817_123045" or legacy "BUILD 20260817-123045".
        val regex = Regex("""BUILD(?:_VERSION)?\s+(\d{8}[_-]\d{6})""")
        val match = regex.find(response.trim())
        val raw = match?.groupValues?.get(1)
        // Normalize to yyyymmdd_hhmmss.
        return raw?.replace("-", "_")
    }

    private fun checkForLatestFirmware(prefillUrl: String? = null) {
        val current = firmwareBuildVersion
        if (current.isNullOrBlank()) {
            Toast.makeText(this, "Current firmware version not yet known", Toast.LENGTH_LONG).show()
            return
        }

        if (prefillUrl != null) {
            fetchLatestFirmwareVersion(prefillUrl, current)
            return
        }

        val input = EditText(this).apply {
            hint = "https://example.com/firmware/"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Check for latest firmware")
            .setMessage("Enter the directory URL, or a .zip URL whose parent directory contains firmware_latest.txt")
            .setView(input)
            .setPositiveButton("Check") { _, _ ->
                val baseUrl = input.text.toString().trim()
                if (baseUrl.isNotEmpty()) {
                    fetchLatestFirmwareVersion(baseUrl, current)
                } else {
                    Toast.makeText(this, "URL is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun fetchLatestFirmwareVersion(baseUrl: String, currentVersion: String) {
        Thread {
            // Accept either a directory URL or a direct zip URL.
            val directoryUrl = if (baseUrl.endsWith(".zip", ignoreCase = true)) {
                baseUrl.substringBeforeLast('/')
            } else {
                baseUrl.trimEnd('/')
            }
            val latestUrl = "$directoryUrl/firmware_latest.txt"
            val latestText = downloadTextFile(latestUrl)
            val latestZip = latestText?.trim()
            val latestVersion = latestZip?.let { extractVersionFromZipName(it) }

            if (latestVersion == null) {
                runOnUiThread {
                    Toast.makeText(this, "Could not read latest firmware version", Toast.LENGTH_LONG).show()
                }
                return@Thread
            }

            Log.i(TAG, "Current firmware version: $currentVersion, latest: $latestVersion")

            runOnUiThread {
                when {
                    latestVersion == currentVersion -> {
                        Toast.makeText(this, "Firmware is up to date ($currentVersion)", Toast.LENGTH_LONG).show()
                    }
                    isNewerFirmwareVersion(currentVersion, latestVersion) -> {
                        AlertDialog.Builder(this)
                            .setTitle("Firmware update available")
                            .setMessage("Current: $currentVersion\nLatest: $latestVersion\n\nDownload and flash now?")
                            .setPositiveButton("Update") { _, _ ->
                                val zipUrl = "$directoryUrl/$latestZip"
                                downloadFirmwareFromUrl(zipUrl, skipConfirmation = false)
                            }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                    else -> {
                        Toast.makeText(this, "Current firmware ($currentVersion) is newer than server ($latestVersion)", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.apply { name = "FirmwareLatestCheckThread"; start() }
    }

    private fun extractVersionFromZipName(zipName: String): String? {
        val regex = Regex("""firmware_(\d{8}_\d{6})\.zip""")
        return regex.find(zipName)?.groupValues?.get(1)
    }

    private fun isNewerFirmwareVersion(current: String, latest: String): Boolean {
        // Versions are yyyymmdd_hhmmss, so simple string comparison works.
        return latest > current
    }

    private fun downloadTextFile(urlString: String): String? {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Text download failed for $urlString: HTTP $responseCode")
                return null
            }
            input = connection.inputStream
            input.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            Log.e(TAG, "Text download error for $urlString", e)
            null
        } finally {
            input?.close()
            connection?.disconnect()
        }
    }

    private fun promptForFirmwareUrl() {
        val input = EditText(this).apply {
            hint = "https://example.com/firmware_20260817_123045.zip"
            setSingleLine(true)
        }
        AlertDialog.Builder(this)
            .setTitle("Update firmware")
            .setMessage("Enter the URL of a firmware .zip file (e.g. firmware_YYYYMMDD_HHMMSS.zip) containing bootloader.bin, partition-table.bin, and usb_webcam.bin")
            .setView(input)
            .setPositiveButton("Download") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isNotEmpty()) {
                    downloadFirmwareFromUrl(url, skipConfirmation = false)
                } else {
                    Toast.makeText(this, "URL is required", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun downloadFirmwareFromUrl(zipUrl: String, skipConfirmation: Boolean) {
        val firmwareDir = File(getExternalFilesDir(null), "Firmware").apply {
            mkdirs()
        }
        val expectedFiles = listOf(
            "bootloader.bin" to File(firmwareDir, "bootloader.bin"),
            "partition-table.bin" to File(firmwareDir, "partition-table.bin"),
            "usb_webcam.bin" to File(firmwareDir, "usb_webcam.bin")
        )

        val progressView = layoutInflater.inflate(R.layout.dialog_flash_progress, null)
        val titleView = progressView.findViewById<TextView>(R.id.flash_progress_title)
        val messageView = progressView.findViewById<TextView>(R.id.flash_progress_message)
        val progressBar = progressView.findViewById<ProgressBar>(R.id.flash_progress_bar)
        val bytesView = progressView.findViewById<TextView>(R.id.flash_progress_bytes)

        titleView.text = "Downloading firmware"
        messageView.text = "Starting..."
        progressBar.progress = 0
        bytesView.text = ""

        val dialog = AlertDialog.Builder(this)
            .setView(progressView)
            .setCancelable(false)
            .show()

        Thread {
            var failureMessage: String? = null
            try {
                if (!isFinishing) {
                    runOnUiThread { messageView.text = "Downloading firmware.zip..." }
                }

                val zipFile = File(cacheDir, "firmware_download.zip")
                val downloaded = downloadFile(zipUrl, zipFile) { bytesRead, totalBytes ->
                    if (!isFinishing && totalBytes > 0) {
                        runOnUiThread {
                            val pct = ((bytesRead * 1000L) / totalBytes).toInt()
                            progressBar.progress = pct.coerceIn(0, 1000)
                            bytesView.text = "$bytesRead / $totalBytes bytes"
                        }
                    }
                }
                if (!downloaded) {
                    failureMessage = "Failed to download firmware.zip"
                    return@Thread
                }

                if (!isFinishing) {
                    runOnUiThread {
                        messageView.text = "Extracting firmware..."
                        progressBar.progress = 0
                    }
                }
                extractFirmwareZip(zipFile, expectedFiles) { entryIndex, entryCount ->
                    if (!isFinishing) {
                        runOnUiThread {
                            progressBar.progress = ((entryIndex * 1000L) / entryCount).toInt()
                        }
                    }
                }

                if (!isFinishing) {
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this, "Firmware downloaded; starting flash...", Toast.LENGTH_SHORT).show()
                        startFirmwareFlashFlow(skipConfirmation = skipConfirmation)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Firmware download failed", e)
                failureMessage = e.message ?: "Download failed"
            } finally {
                if (failureMessage != null && !isFinishing) {
                    runOnUiThread {
                        dialog.dismiss()
                        Toast.makeText(this, failureMessage, Toast.LENGTH_LONG).show()
                    }
                }
            }
        }.apply { name = "FirmwareDownloadThread"; start() }
    }

    private fun extractFirmwareZip(
        zipFile: File,
        expectedFiles: List<Pair<String, File>>,
        progress: ((Int, Int) -> Unit)? = null
    ) {
        val expectedNames = expectedFiles.map { it.first }.toSet()
        val destByName = expectedFiles.associate { it.first to it.second }
        var extractedCount = 0
        ZipInputStream(FileInputStream(zipFile).buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name
                if (!entry.isDirectory && expectedNames.contains(name)) {
                    val dest = destByName[name] ?: continue
                    FileOutputStream(dest).use { output ->
                        zis.copyTo(output)
                    }
                    extractedCount++
                    Log.i(TAG, "Extracted $name -> ${dest.absolutePath} (${dest.length()} bytes)")
                }
                zis.closeEntry()
                entry = zis.nextEntry
                progress?.invoke(extractedCount, expectedFiles.size)
            }
        }
        if (extractedCount != expectedFiles.size) {
            val found = expectedFiles.filter { it.second.exists() }.map { it.first }
            val missing = expectedFiles.map { it.first } - found.toSet()
            throw IllegalStateException("Firmware zip missing files: ${missing.joinToString()}")
        }
    }

    private fun downloadFile(
        urlString: String,
        dest: File,
        progress: ((Long, Long) -> Unit)? = null
    ): Boolean {
        var connection: HttpURLConnection? = null
        var input: InputStream? = null
        var output: FileOutputStream? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "*/*")
            val responseCode = connection.responseCode
            if (responseCode != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "Download failed for $urlString: HTTP $responseCode")
                return false
            }
            val totalBytes = connection.contentLength.toLong().coerceAtLeast(0L)
            input = connection.inputStream
            output = FileOutputStream(dest)
            val buffer = ByteArray(8192)
            var bytesRead = 0L
            var count: Int
            while (input.read(buffer).also { count = it } != -1) {
                output.write(buffer, 0, count)
                bytesRead += count
                progress?.invoke(bytesRead, totalBytes)
            }
            output.flush()
            Log.i(TAG, "Downloaded $urlString -> ${dest.absolutePath} (${dest.length()} bytes)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Download error for $urlString", e)
            false
        } finally {
            input?.close()
            output?.close()
            connection?.disconnect()
        }
    }

    private fun startFirmwareFlashFlow(skipConfirmation: Boolean = false) {
        Log.d(TAG, "startFirmwareFlashFlow called, flashInProgress=$flashInProgress, skipConfirmation=$skipConfirmation")
        if (flashInProgress) {
            Toast.makeText(this, "Flash already in progress", Toast.LENGTH_SHORT).show()
            return
        }

        val firmwareDir = File(getExternalFilesDir(null), "Firmware")
        val files = listOf(
            Triple("bootloader.bin", 0x2000, File(firmwareDir, "bootloader.bin")),
            Triple("partition-table.bin", 0x8000, File(firmwareDir, "partition-table.bin")),
            Triple("usb_webcam.bin", 0x10000, File(firmwareDir, "usb_webcam.bin"))
        )

        val missing = files.filter { !it.third.exists() || it.third.length() == 0L }
        Log.d(TAG, "Firmware files check: ${files.size} total, ${missing.size} missing")
        if (missing.isNotEmpty()) {
            val names = missing.joinToString { it.first }
            Log.d(TAG, "Showing missing firmware dialog for: $names")
            AlertDialog.Builder(this)
                .setTitle("Firmware files missing")
                .setMessage("Push the following files to\n${firmwareDir.absolutePath}:\n$names")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        pendingFlashFiles.clear()
        pendingFlashFiles.addAll(files.map { it.third.absolutePath to it.second })

        if (skipConfirmation) {
            Log.d(TAG, "Skipping flash confirmation dialog (EXTRA_FLASH_NOW)")
            beginFlashAfterConfirmation()
            return
        }

        Log.d(TAG, "Showing flash confirmation dialog")
        AlertDialog.Builder(this)
            .setTitle("Flash ESP32-P4 firmware?")
            .setMessage("This will reboot the ESP32 into download mode and reflash ${files.size} images. Do not disconnect the USB cable.")
            .setPositiveButton("Flash") { _, _ ->
                Log.d(TAG, "Flash confirmation accepted")
                beginFlashAfterConfirmation()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun isEspDownloadModeDevice(device: UsbDevice): Boolean {
        return device.vendorId == ESPRESSIF_VID && device.productId == ESPRESSIF_DOWNLOAD_PID
    }

    private fun beginFlashAfterConfirmation() {
        Log.d(TAG, "beginFlashAfterConfirmation: starting flash flow")
        flashInProgress = true
        flashTotalBytes = pendingFlashFiles.sumOf { File(it.first).length() }
        flashProgressBytesWritten = 0L

        // Check whether the ESP32 is already in ROM download mode (e.g. after a
        // previous flash attempt or a manual BOOT+RESET). If so, skip the
        // bootloader command and flash directly.
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val alreadyDownload = usbManager.deviceList.values.firstOrNull { isEspDownloadModeDevice(it) }
        if (alreadyDownload != null) {
            Log.d(TAG, "ESP32 already in download mode (${alreadyDownload.deviceName}); skipping bootloader command")
            showFlashProgress("ESP32 already in download mode...")
            if (usbManager.hasPermission(alreadyDownload)) {
                openDownloadDeviceAndFlash(alreadyDownload)
            } else {
                registerFlashReceiver()
                requestFlashPermission(alreadyDownload)
            }
            return
        }

        showFlashProgress("Sending bootloader command...")

        Thread {
            try {
                if (!waitForCdcReady(30000L)) {
                    throw RuntimeException("CDC not connected")
                }
                sendBootloaderCommand()
                runOnUiThread {
                    updateFlashProgress("Waiting for download mode...")
                    registerFlashReceiver()
                    mainHandler.postDelayed(findDownloadDeviceRunnable, 500)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send bootloader command", e)
                runOnUiThread {
                    finishFlashFlow(false, "Bootloader command failed: ${e.message}")
                }
            }
        }.apply { name = "FlashBootloaderThread"; start() }
    }

    private fun sendBootloaderCommand() {
        val conn = cdcConnection ?: throw RuntimeException("CDC not connected")
        val outEp = cdcOutEndpoint ?: throw RuntimeException("CDC OUT endpoint missing")
        val inEp = cdcInEndpoint ?: throw RuntimeException("CDC IN endpoint missing")
        val cmd = "bootloader\r\n".toByteArray(Charsets.UTF_8)
        Log.d(TAG, "Sending bootloader command (${cmd.size} bytes) over CDC OUT ep=${outEp.address}")
        // Use a short timeout; the device may disconnect as soon as it parses the command.
        val written = conn.bulkTransfer(outEp, cmd, cmd.size, 1000)
        Log.d(TAG, "Bootloader command bulkTransfer returned $written")
        // Drain any response (REBOOT_BOOTLOADER). Device may disconnect, so ignore errors.
        val drain = ByteArray(128)
        try {
            val drained = conn.bulkTransfer(inEp, drain, drain.size, 500)
            Log.d(TAG, "Drained $drained bytes after bootloader command")
        } catch (e: Exception) {
            Log.d(TAG, "Drain after bootloader command failed (expected if device reset): ${e.message}")
        }
        // A negative write usually means the device disconnected; treat that as expected.
        if (written < 0) {
            Log.w(TAG, "Bootloader command transfer returned $written; assuming device reset")
        }
    }

    private val findDownloadDeviceRunnable = object : Runnable {
        override fun run() {
            if (!flashInProgress) return

            val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
            val candidates = usbManager.deviceList.values.filter {
                it.vendorId == ESPRESSIF_VID
            }
            if (candidates.isNotEmpty()) {
                for (d in candidates) {
                    Log.d(TAG, "Flash poll found Espressif device: ${d.deviceName} " +
                            "VID=${d.vendorId} PID=${d.productId} " +
                            "class=${d.deviceClass}/${d.deviceSubclass}/${d.deviceProtocol} " +
                            "interfaces=${d.interfaceCount}")
                    for (i in 0 until d.interfaceCount) {
                        val iface = d.getInterface(i)
                        Log.d(TAG, "  iface[$i] class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol} " +
                                "endpoints=${iface.endpointCount}")
                        for (j in 0 until iface.endpointCount) {
                            val ep = iface.getEndpoint(j)
                            Log.d(TAG, "    ep[$j] addr=${ep.address} type=${ep.type} dir=${ep.direction} maxPkt=${ep.maxPacketSize}")
                        }
                    }
                }
            }

            val device = candidates.firstOrNull()
            if (device != null) {
                mainHandler.removeCallbacks(this)
                updateFlashProgress("Found download-mode device ${device.productId}")
                if (usbManager.hasPermission(device)) {
                    openDownloadDeviceAndFlash(device)
                } else {
                    requestFlashPermission(device)
                }
            } else {
                updateFlashProgress("Waiting for download mode...")
                mainHandler.postDelayed(this, 500)
            }
        }
    }

    private fun requestFlashPermission(device: UsbDevice) {
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val intent = Intent(ACTION_USB_FLASH_PERMISSION)
        intent.setPackage(packageName)
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(this, 0, intent, flags)
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun registerFlashReceiver() {
        unregisterFlashReceiver()
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action != ACTION_USB_FLASH_PERMISSION) return
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                }
                val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                if (device == null || !granted) {
                    finishFlashFlow(false, "USB permission denied for flash mode")
                    return
                }
                openDownloadDeviceAndFlash(device)
            }
        }
        flashReceiver = receiver
        val filter = IntentFilter(ACTION_USB_FLASH_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(receiver, filter)
        }
    }

    private fun unregisterFlashReceiver() {
        mainHandler.removeCallbacks(findDownloadDeviceRunnable)
        flashReceiver?.let {
            try {
                unregisterReceiver(it)
            } catch (_: Exception) { }
        }
        flashReceiver = null
    }

    private fun openDownloadDeviceAndFlash(device: UsbDevice) {
        Log.d(TAG, "Opening download-mode device ${device.deviceName} PID=${device.productId}")
        val usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        val connection = usbManager.openDevice(device) ?: run {
            finishFlashFlow(false, "Failed to open download-mode device")
            return
        }

        // Log every interface so we can diagnose the ROM download-mode layout.
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            Log.d(TAG, "Download device iface[$i] class=${iface.interfaceClass}/${iface.interfaceSubclass}/${iface.interfaceProtocol} endpoints=${iface.endpointCount}")
            for (j in 0 until iface.endpointCount) {
                val ep = iface.getEndpoint(j)
                Log.d(TAG, "  ep[$j] addr=${ep.address} type=${ep.type} dir=${ep.direction} maxPkt=${ep.maxPacketSize}")
            }
        }

        // Locate a suitable interface: prefer CDC data, then vendor-specific, finally any
        // interface that has one bulk IN and one bulk OUT endpoint. ESP32-P4 ROM download
        // mode exposes a vendor-specific interface with bulk endpoints.
        var dataInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_CDC_DATA) {
                dataInterface = iface
                Log.d(TAG, "Selected CDC data interface ${iface.id}")
                break
            }
        }
        if (dataInterface == null) {
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                if (iface.interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                    dataInterface = iface
                    Log.d(TAG, "Selected vendor-specific interface ${iface.id}")
                    break
                }
            }
        }
        if (dataInterface == null) {
            // Fallback: pick the first interface with one bulk IN and one bulk OUT endpoint.
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                var hasIn = false
                var hasOut = false
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        when (ep.direction) {
                            UsbConstants.USB_DIR_IN -> hasIn = true
                            UsbConstants.USB_DIR_OUT -> hasOut = true
                        }
                    }
                }
                if (hasIn && hasOut) {
                    dataInterface = iface
                    Log.d(TAG, "Selected fallback interface ${iface.id} with bulk IN+OUT")
                    break
                }
            }
        }
        if (dataInterface == null) {
            connection.close()
            finishFlashFlow(false, "No suitable interface on download-mode device")
            return
        }

        val claimed = connection.claimInterface(dataInterface, true)
        if (!claimed) {
            connection.close()
            finishFlashFlow(false, "Failed to claim download-mode interface")
            return
        }

        var inEp: UsbEndpoint? = null
        var outEp: UsbEndpoint? = null
        for (i in 0 until dataInterface.endpointCount) {
            val ep = dataInterface.getEndpoint(i)
            if (ep.type == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> inEp = ep
                    UsbConstants.USB_DIR_OUT -> outEp = ep
                }
            }
        }
        if (inEp == null || outEp == null) {
            connection.releaseInterface(dataInterface)
            connection.close()
            finishFlashFlow(false, "Download-mode bulk endpoints not found")
            return
        }
        Log.d(TAG, "Download-mode endpoints: IN=${inEp.address} OUT=${outEp.address} maxPkt=${inEp.maxPacketSize}")

        // The ROM download-mode device exposes CDC ACM descriptors. Assert DTR/RTS and
        // set 115200 8N1 on the control interface so the data interface is fully enabled.
        var controlInterface: UsbInterface? = null
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_COMM) {
                controlInterface = iface
                break
            }
        }
        controlInterface?.let { ctrl ->
            try {
                connection.claimInterface(ctrl, true)
                val lineCoding = byteArrayOf(
                    0x00.toByte(), 0xC2.toByte(), 0x01.toByte(), 0x00.toByte(), // 115200 baud (little-endian)
                    0x00.toByte(), // stop bits
                    0x00.toByte(), // parity
                    0x08.toByte()  // data bits
                )
                // SET_LINE_CODING: 0x21 = host-to-device | class | interface
                connection.controlTransfer(0x21, 0x20, 0, ctrl.id, lineCoding, lineCoding.size, 1000)
                // SET_CONTROL_LINE_STATE: DTR=1, RTS=1
                connection.controlTransfer(0x21, 0x22, 0x03, ctrl.id, null, 0, 1000)
                Log.d(TAG, "CDC control interface initialized for download mode")
            } catch (e: Exception) {
                Log.w(TAG, "Failed to initialize CDC control interface: ${e.message}")
            }
        }

        updateFlashProgress("Flashing ${pendingFlashFiles.size} images...")
        Thread {
            var success = true
            var message = "Firmware updated successfully"
            try {
                val flasher = Esp32Flasher(connection, inEp, outEp)
                for ((index, pair) in pendingFlashFiles.withIndex()) {
                    val (path, offset) = pair
                    val file = File(path)
                    val fileSize = file.length()
                    runOnUiThread {
                        updateFlashProgress("Flashing ${file.name} (${index + 1}/${pendingFlashFiles.size})...")
                    }
                    val data = file.readBytes()
                    var lastUiUpdateMs = 0L
                    if (!flasher.flashImage(offset, data) { transferred, total ->
                            val now = SystemClock.elapsedRealtime()
                            val overall = flashProgressBytesWritten + transferred
                            val isLast = transferred >= total
                            if (isLast || now - lastUiUpdateMs > 100) {
                                lastUiUpdateMs = now
                                runOnUiThread {
                                    updateFlashProgressBytes(overall, flashTotalBytes,
                                        "${file.name}: ${formatBytes(transferred)} / ${formatBytes(total)}")
                                }
                            }
                        }) {
                        success = false
                        message = "Failed to flash ${file.name}"
                        break
                    }
                    flashProgressBytesWritten += fileSize
                }
            } catch (e: Exception) {
                Log.e(TAG, "Flash sequence failed", e)
                success = false
                message = "Flash error: ${e.message}"
            }

            if (success) {
                // Reset the ESP32 so it boots from the newly flashed firmware.
                controlInterface?.let { ctrl ->
                    try {
                        Log.d(TAG, "Resetting ESP32 via CDC control line state...")
                        // Standard esptool reset sequence over CDC ACM: pulse RTS to reset.
                        connection.controlTransfer(0x21, 0x22, 0x02, ctrl.id, null, 0, 1000) // RTS=1
                        Thread.sleep(100)
                        connection.controlTransfer(0x21, 0x22, 0x00, ctrl.id, null, 0, 1000) // RTS=0
                        Thread.sleep(100)
                        connection.controlTransfer(0x21, 0x22, 0x02, ctrl.id, null, 0, 1000) // RTS=1
                        Thread.sleep(100)
                        connection.controlTransfer(0x21, 0x22, 0x00, ctrl.id, null, 0, 1000) // RTS=0
                        Thread.sleep(200)
                        Log.d(TAG, "ESP32 reset sequence complete")
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to reset ESP32: ${e.message}")
                    }
                }
            }

            try {
                connection.releaseInterface(dataInterface)
                controlInterface?.let { connection.releaseInterface(it) }
            } catch (_: Exception) { }
            connection.close()

            runOnUiThread {
                finishFlashFlow(success, message)
            }
        }.apply { name = "FlashSequenceThread"; start() }
    }

    private fun showFlashProgress(message: String) {
        runOnUiThread {
            flashDialog?.dismiss()
            val view = layoutInflater.inflate(R.layout.dialog_flash_progress, null)
            flashProgressBar = view.findViewById(R.id.flash_progress_bar)
            flashProgressMessage = view.findViewById(R.id.flash_progress_message)
            flashProgressBytes = view.findViewById(R.id.flash_progress_bytes)
            flashProgressBar?.max = 1000
            flashProgressBar?.progress = 0
            flashProgressMessage?.text = message
            flashProgressBytes?.text = "0 / ${formatBytes(flashTotalBytes)}"
            flashDialog = AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .show()
        }
    }

    private fun updateFlashProgress(message: String) {
        runOnUiThread {
            flashProgressMessage?.text = message
        }
    }

    private fun updateFlashProgressBytes(transferred: Long, total: Long, detail: String) {
        flashProgressMessage?.text = detail
        flashProgressBytes?.text = "${formatBytes(transferred)} / ${formatBytes(total)}"
        if (total > 0) {
            val progress = ((transferred * 1000) / total).toInt()
            flashProgressBar?.progress = progress.coerceIn(0, 1000)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> "%.2f MB".format(bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> "%.1f KB".format(bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun finishFlashFlow(success: Boolean, message: String) {
        flashInProgress = false
        unregisterFlashReceiver()
        flashDialog?.dismiss()
        flashDialog = null
        flashProgressBar = null
        flashProgressMessage = null
        flashProgressBytes = null
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        Log.i(TAG, "Flash flow finished: success=$success, $message")
    }

    private fun ensureRecordingPermissions(): Boolean {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        return if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_RECORD_PERMISSION)
            false
        } else {
            true
        }
    }

    private fun copyVideoToMediaStore(file: File): Uri? {
        val nowMs = System.currentTimeMillis()
        val loc = getCurrentLocation()
        if (loc != null) {
            Log.d(TAG, "Geotagging video ${file.name}: ${loc.latitude}, ${loc.longitude}")
        } else {
            Log.d(TAG, "No location available for video ${file.name}")
        }
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, file.name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.DATE_ADDED, nowMs / 1000)
            put(MediaStore.Video.Media.DATE_TAKEN, nowMs)
            loc?.let {
                put(MediaStore.Video.Media.LATITUDE, it.latitude)
                put(MediaStore.Video.Media.LONGITUDE, it.longitude)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MOVIES}/RemoteSupportHeadset")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            } else {
                val dest = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),
                    "RemoteSupportHeadset/${file.name}"
                )
                dest.parentFile?.mkdirs()
                put(MediaStore.Video.Media.DATA, dest.absolutePath)
            }
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        return try {
            contentResolver.openOutputStream(uri)?.use { out ->
                FileInputStream(file).use { input -> input.copyTo(out) }
            } ?: throw IllegalStateException("openOutputStream returned null")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) },
                    null, null
                )
            }
            uri
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy video to MediaStore", e)
            contentResolver.delete(uri, null, null)
            null
        }
    }

    private fun openVideoInGallery(uri: Uri) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "video/mp4")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "No app available to play video", e)
            Toast.makeText(this, "Video saved to gallery", Toast.LENGTH_SHORT).show()
        }
    }

    private fun updateRecordButton() {
        when (recordingState) {
            RecordingState.IDLE -> {
                recordToggle.text = "Record"
                recordToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFAA3333.toInt())
            }
            else -> {
                recordToggle.text = "Stop"
                recordToggle.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFAA3333.toInt())
            }
        }
    }

    private fun showSettingsMenu() {
        PopupMenu(this, settingsButton).apply {
            menuInflater.inflate(R.menu.menu_settings, menu)
            menu.findItem(R.id.action_diagnostics)?.title =
                if (diagnosticsVisible) "Hide diagnostics" else "Show diagnostics"
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    R.id.action_update_firmware -> {
                        Log.d(TAG, "Settings: update firmware selected")
                        promptForFirmwareUrl()
                        true
                    }
                    R.id.action_check_latest_firmware -> {
                        Log.d(TAG, "Settings: check latest firmware selected")
                        checkForLatestFirmware()
                        true
                    }
                    R.id.action_diagnostics -> {
                        Log.d(TAG, "Settings: diagnostics selected")
                        diagnosticsVisible = !diagnosticsVisible
                        diagnosticsPanel.visibility = if (diagnosticsVisible) View.VISIBLE else View.GONE
                        if (diagnosticsVisible) updateDiagnostics()
                        true
                    }
                    else -> false
                }
            }
            show()
        }
    }

    private fun getVideoOutputFile(segmentIndex: Int): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        // App-specific external directory: no WRITE_EXTERNAL_STORAGE required on Android 10+
        // and the files are accessible over ADB for verification.
        val moviesDir = getExternalFilesDir(Environment.DIRECTORY_MOVIES)
            ?: File(filesDir, "Movies")
        val appDir = File(moviesDir, "RemoteSupportHeadset")
        if (!appDir.exists()) appDir.mkdirs()
        val suffix = if (segmentIndex == 0) "" else "_part${segmentIndex + 1}"
        // The library appends ".mp4" itself, so omit the extension here.
        return File(appDir, "VID_${timeStamp}$suffix")
    }

    /**
     * Capture one still image, retrying up to [maxRetries] times if the CDC
     * path returns an error. Returns true if a JPEG was saved.
     *
     * @param simulatedIndex If non-negative, timing statistics for the host
     *   stress-test script are emitted when the capture completes or fails.
     */
    private fun captureStillImageWithRetries(
        maxRetries: Int,
        cancelCheck: () -> Boolean = { false },
        simulatedIndex: Int = -1
    ): Boolean {
        captureLock.lock()
        try {
            if (isCapturing) {
                Log.d(TAG, "Capture already in progress")
                runOnUiThread { Toast.makeText(this, "Capture already in progress", Toast.LENGTH_SHORT).show() }
                return false
            }
            isCapturing = true
        } finally {
            captureLock.unlock()
        }

        runOnUiThread { Toast.makeText(this, "Capturing...", Toast.LENGTH_SHORT).show() }

        var lastException: Exception? = null
        var succeeded = false
        try {
            for (attempt in 1..maxRetries) {
                if (cancelCheck()) {
                    Log.d(TAG, "Capture cancelled mid-retry")
                    break
                }
                try {
                    if (cdcOutEndpoint == null || cdcInEndpoint == null || cdcConnection == null) {
                        Log.w(TAG, "CDC not ready on attempt $attempt; refreshing state")
                        refreshCdcState()
                        if (cdcOutEndpoint == null || cdcInEndpoint == null || cdcConnection == null) {
                            throw RuntimeException("CDC not available")
                        }
                    }

                    doSingleCapture()
                    succeeded = true
                    return true
                } catch (e: Exception) {
                    lastException = e
                    Log.w(TAG, "Capture attempt $attempt/$maxRetries failed: ${e.message}", e)
                    if (attempt < maxRetries) {
                        refreshCdcState()
                        Thread.sleep(500)
                    }
                }
            }
        } finally {
            captureLock.lock()
            isCapturing = false
            lastCaptureEndTime = SystemClock.elapsedRealtime()
            captureLock.unlock()
            val idx = simulatedIndex
            if (idx >= 0) {
                val elapsed = SystemClock.elapsedRealtime() - simulatedCaptureStartTime
                Log.i(TAG, "SIMCAP complete i=$idx t=${SystemClock.elapsedRealtime()} dt_complete=${elapsed}ms success=$succeeded")
                // The firmware pauses the UVC preview during the still capture.
                // Start watching for the first preview frame after this point so
                // we can measure tap-to-stream-resume time accurately.
                waitingForStreamResume = true
            }
            Log.d(TAG, "Capture session ended, success=$succeeded")
        }

        if (!succeeded) {
            Log.e(TAG, "Capture failed after $maxRetries attempts", lastException)
            runOnUiThread {
                Toast.makeText(this, "Capture failed: ${lastException?.message}", Toast.LENGTH_LONG).show()
            }
        }
        return succeeded
    }

    private fun doSingleCapture() {
        val conn = cdcConnection ?: throw RuntimeException("CDC connection lost")
        val outEp = cdcOutEndpoint ?: throw RuntimeException("CDC OUT endpoint lost")
        val inEp = cdcInEndpoint ?: throw RuntimeException("CDC IN endpoint lost")

        // Drain stale input
        drainStaleInput(conn, inEp)

        // Send capture command
        val cmd = "s\r\n".toByteArray(Charsets.UTF_8)
        val written = conn.bulkTransfer(outEp, cmd, cmd.size, CDC_TIMEOUT_MS)
        if (written < 0) {
            throw RuntimeException("Failed to send capture command (bulkTransfer returned $written)")
        }

        // Read response (STILL_LEN etc.) with a short deadline so a device reset
        // does not leave us blocked for the full payload timeout.
        val buffer = ByteArrayOutputStream()
        val commandDeadline = System.currentTimeMillis() + 10000L
        var stillLen = -1
        var progressShown = false

        while (System.currentTimeMillis() < commandDeadline) {
            val line = readLine(conn, inEp, buffer, commandDeadline) ?: continue
            Log.d(TAG, "CDC: $line")

            when {
                line.startsWith("STILL_LEN ") -> {
                    stillLen = line.substring("STILL_LEN ".length).trim().toIntOrNull()
                        ?: throw RuntimeException("Invalid STILL_LEN")
                    break
                }
                line.startsWith("STILL_FAIL") -> {
                    throw RuntimeException("Device reported failure: $line")
                }
                line.startsWith("STILL_BUSY") -> {
                    throw RuntimeException("Device busy")
                }
                line.startsWith("STILL_PENDING") && !progressShown -> {
                    progressShown = true
                    runOnUiThread { Toast.makeText(this, "Still pending...", Toast.LENGTH_SHORT).show() }
                }
            }
        }

        if (stillLen <= 0) {
            throw RuntimeException("Did not receive STILL_LEN")
        }

        if (stillLen > 50 * 1024 * 1024) {
            throw RuntimeException("Still size unreasonable: $stillLen")
        }

        // Read the JPEG payload with the full capture timeout.
        val payloadDeadline = System.currentTimeMillis() + CAPTURE_TIMEOUT_MS
        val jpegData = readExactly(conn, inEp, stillLen, buffer, payloadDeadline)
        if (jpegData.size != stillLen) {
            throw RuntimeException("Incomplete JPEG: got ${jpegData.size}/$stillLen")
        }
        if (jpegData.size < 2 || jpegData[0] != 0xFF.toByte() || jpegData[1] != 0xD8.toByte()) {
            throw RuntimeException("Invalid JPEG magic: ${jpegData.take(2).map { "%02X".format(it) }}")
        }

        // Consume the trailing \r\nSTILL_END\r\n marker (skip any empty lines
        // that may have arrived with the final payload chunk).
        val trailerDeadline = System.currentTimeMillis() + 5000L
        while (System.currentTimeMillis() < trailerDeadline) {
            val trailer = readLine(conn, inEp, buffer, trailerDeadline) ?: break
            if (trailer.isEmpty()) continue
            if (trailer != "STILL_END") {
                Log.w(TAG, "Unexpected trailer line: $trailer")
            }
            break
        }

        // Save to external app pictures directory
        val file = saveJpeg(jpegData)
        runOnUiThread {
            Toast.makeText(this, "Saved: ${file.name}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun drainStaleInput(conn: UsbDeviceConnection, inEp: UsbEndpoint) {
        val chunk = ByteArray(1024)
        while (true) {
            val len = conn.bulkTransfer(inEp, chunk, chunk.size, 200)
            if (len <= 0) break
        }
    }

    private fun readLine(conn: UsbDeviceConnection, inEp: UsbEndpoint, buffer: ByteArrayOutputStream, deadline: Long): String? {
        while (true) {
            val bytes = buffer.toByteArray()
            val crlf = findCrlf(bytes)
            if (crlf >= 0) {
                val line = String(bytes, 0, crlf, Charsets.UTF_8)
                val remaining = bytes.copyOfRange(crlf + 2, bytes.size)
                buffer.reset()
                buffer.write(remaining)
                return line
            }
            if (System.currentTimeMillis() >= deadline) return null
            val chunk = ByteArray(1024)
            val len = conn.bulkTransfer(inEp, chunk, chunk.size, 2000)
            if (len < 0) {
                // Timeout with no data
                if (buffer.size() == 0) continue
                return null
            }
            buffer.write(chunk, 0, len)
        }
    }

    private fun findCrlf(bytes: ByteArray): Int {
        for (i in 0 until bytes.size - 1) {
            if (bytes[i] == 0x0D.toByte() && bytes[i + 1] == 0x0A.toByte()) {
                return i
            }
        }
        return -1
    }

    private fun readExactly(conn: UsbDeviceConnection, inEp: UsbEndpoint, n: Int, buffer: ByteArrayOutputStream, deadline: Long): ByteArray {
        val out = ByteArrayOutputStream(n)
        val bytes = buffer.toByteArray()
        val take = minOf(n, bytes.size)
        out.write(bytes, 0, take)
        buffer.reset()
        if (bytes.size > take) {
            buffer.write(bytes, take, bytes.size - take)
        }
        var remaining = n - take
        var consecutiveTimeouts = 0
        while (remaining > 0) {
            if (System.currentTimeMillis() >= deadline) break
            // Use small 4 KiB chunks and a short per-call timeout.  Tiny requests
            // keep an IN URB pending almost continuously and reduce the chance that
            // a short final packet is hidden behind a large timed-out transfer.
            // Always request at least 4 KiB so the host controller sees short/ZLP
            // termination cleanly, then put any bytes past the payload back into the
            // line buffer for the trailing STILL_END marker.
            // The total deadline still bounds the whole transfer.
            val requestSize = maxOf(4096, remaining)
            val chunk = ByteArray(minOf(requestSize, 4096))
            val len = conn.bulkTransfer(inEp, chunk, chunk.size, 3000)
            if (len < 0) {
                consecutiveTimeouts++
                if (consecutiveTimeouts % 10 == 0) {
                    Log.d(TAG, "readExactly: polling, remaining=$remaining, consecutiveTimeouts=$consecutiveTimeouts")
                }
                if (consecutiveTimeouts > 60) {
                    Log.w(TAG, "readExactly: too many consecutive timeouts, remaining=$remaining")
                    break
                }
                continue
            }
            consecutiveTimeouts = 0
            if (len > 0) {
                val usable = minOf(len, remaining)
                out.write(chunk, 0, usable)
                remaining -= usable
                if (len > usable) {
                    buffer.write(chunk, usable, len - usable)
                }
            }
        }
        return out.toByteArray()
    }

    /**
     * Returns the best available location. If a fresh last-known location is
     * available from the OS it is cached; otherwise a recent cached location
     * (within 5 minutes) is returned so burst captures/videos share the same fix.
     */
    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(): Location? {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            return null
        }

        // Use a recent cached fix if we have one.
        val now = SystemClock.elapsedRealtime()
        val recentCache = cachedLocation?.takeIf { now - cachedLocationTime < 5 * 60 * 1000L }

        val locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return recentCache
        return try {
            val providers = locationManager.getProviders(true)
            var bestLocation: Location? = null
            for (provider in providers) {
                val loc = locationManager.getLastKnownLocation(provider) ?: continue
                if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
                    bestLocation = loc
                }
            }
            if (bestLocation != null) {
                cachedLocation = bestLocation
                cachedLocationTime = now
                bestLocation
            } else {
                recentCache
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to get last known location", e)
            recentCache
        }
    }

    /**
     * Format a latitude/longitude decimal value into EXIF GPS rational seconds.
     */
    private fun locationValueToExifRational(value: Double): String {
        val abs = kotlin.math.abs(value)
        val degrees = abs.toInt()
        val minutesFull = (abs - degrees) * 60.0
        val minutes = minutesFull.toInt()
        val seconds = (minutesFull - minutes) * 60.0
        return "$degrees/1,$minutes/1,$seconds/1"
    }

    /**
     * Write date/time and (if available) GPS metadata into a JPEG file.
     */
    private fun writeJpegMetadata(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val dateTime = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(Date())
            exif.setAttribute(ExifInterface.TAG_DATETIME, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, dateTime)
            exif.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, dateTime)
            // correctFullResJpeg() rotates the still image 90° counter-clockwise
            // and applies AWB from the Macbeth chart, so EXIF orientation is normal.
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())

            getCurrentLocation()?.let { loc ->
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE, locationValueToExifRational(loc.latitude))
                exif.setAttribute(ExifInterface.TAG_GPS_LATITUDE_REF, if (loc.latitude >= 0) "N" else "S")
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE, locationValueToExifRational(loc.longitude))
                exif.setAttribute(ExifInterface.TAG_GPS_LONGITUDE_REF, if (loc.longitude >= 0) "E" else "W")
                if (loc.hasAltitude()) {
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE, "${kotlin.math.abs(loc.altitude)}/1")
                    exif.setAttribute(ExifInterface.TAG_GPS_ALTITUDE_REF, if (loc.altitude >= 0) "0" else "1")
                }
                Log.d(TAG, "Wrote GPS metadata to ${file.name}: ${loc.latitude}, ${loc.longitude}")
            }

            exif.saveAttributes()
            Log.d(TAG, "Wrote EXIF metadata to ${file.name}")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write JPEG metadata", e)
        }
    }

    private fun saveJpeg(data: ByteArray): File {
        // Include millisecond precision so rapid captures (and the lifecycle test)
        // never collide on the same filename.
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            ?: File(filesDir, "Pictures")
        val appDir = File(picturesDir, "RemoteSupportHeadset")
        if (!appDir.exists()) appDir.mkdirs()
        val file = File(appDir, "IMG_$timeStamp.jpg")

        FileOutputStream(file).use { it.write(data) }

        // Rotate the full-resolution still to upright and apply AWB/colour
        // correction derived from the Macbeth chart.
        correctFullResJpeg(file)

        writeJpegMetadata(file)
        MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        lastCapturedFile = file
        generateThumbnailAsync(file)
        return file
    }

    /**
     * Decode the freshly saved full-resolution JPEG, rotate it 90°
     * counter-clockwise, and apply AWB / colour correction derived from the
     * Macbeth chart when all four 4x6 corner tags are detected. The file is
     * overwritten with the processed image. Large images (>24 MP) are skipped to
     * avoid OOM.
     */
    private fun correctFullResJpeg(file: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width <= 0 || height <= 0) {
                Log.w(TAG, "Cannot process ${file.name}: invalid dimensions")
                return
            }

            val megapixels = (width.toLong() * height.toLong()) / 1_000_000.0
            if (megapixels > 24) {
                Log.w(TAG, "Skipping processing for ${file.name}: ${"%.1f".format(megapixels)}MP is too large")
                return
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val original = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                ?: run {
                    Log.w(TAG, "Failed to decode ${file.name} for processing")
                    return
                }

            val rotated = rotateBitmap90Ccw(original)
            original.recycle()

            // Detect the 4x6 Macbeth chart in the upright rotated image and apply
            // a diagonal AWB correction from its white/grey patch.
            var awbApplied = false
            val processed = try {
                val detections = detectMacbeth4x6(rotated)
                val gains = if (detections.size >= 4) {
                    MacbethColorCorrector.estimateAwbGains(rotated, detections)
                } else null
                if (gains != null && gains.all { it in 0.2f..5f }) {
                    val corrected = MacbethColorCorrector.applyAwb(rotated, gains)
                    rotated.recycle()
                    awbApplied = true
                    Log.i(TAG, "Applied AWB to ${file.name}: gains=${gains.contentToString()}")
                    corrected
                } else {
                    if (gains != null) {
                        Log.w(TAG, "AWB gains out of range for ${file.name}: ${gains.contentToString()}")
                    }
                    rotated
                }
            } catch (e: Exception) {
                Log.w(TAG, "AWB processing failed for ${file.name}", e)
                rotated
            }

            // Write to a temporary file first so a crash/OOM mid-compress does not
            // leave the original capture truncated or corrupted.
            val tempFile = File(file.parent, "${file.name}.tmp")
            FileOutputStream(tempFile).use { out ->
                processed.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
            processed.recycle()
            if (!tempFile.renameTo(file)) {
                tempFile.delete()
                throw RuntimeException("Failed to replace ${file.name} with processed version")
            }
            Log.i(TAG, "Processed full-resolution ${file.name}: ${width}x${height} -> ${processed.width}x${processed.height}, awbApplied=$awbApplied")
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Out of memory processing ${file.name}", e)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to process ${file.name}", e)
        }
    }

    /**
     * Detect the 4x6 Macbeth chart corner tags (16-19) in a full-resolution
     * image. Try a fast scaled detection first, then fall back to the native
     * resolution if corners are missing.
     */
    private fun detectMacbeth4x6(bitmap: Bitmap): List<AprilTagDetector.Detection> {
        val chartIds = setOf(16, 17, 18, 19)
        for (maxDim in listOf(1280, 1920, null)) {
            val (detections, _) = aprilTagDetector.detect(bitmap, annotate = false, maxDimension = maxDim)
            val chartDetections = detections.filter { it.id in chartIds }
            val uniqueIds = chartDetections.map { it.id }.distinct()
            Log.d(TAG, "detectMacbeth4x6: maxDim=$maxDim found $uniqueIds")
            if (uniqueIds.size >= 4) return chartDetections
        }
        return emptyList()
    }

    private fun rotateBitmap90Ccw(bitmap: Bitmap): Bitmap {
        val matrix = Matrix().apply { postRotate(-90f) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun generateThumbnailAsync(file: File) {
        Thread {
            try {
                val options = BitmapFactory.Options().apply {
                    inJustDecodeBounds = true
                }
                BitmapFactory.decodeFile(file.absolutePath, options)
                // Preserve the captured image's aspect ratio; the rotated full-res
                // still is portrait (e.g. 1944x2592), so a fixed 4:3 thumbnail was
                // stretching it. Use a fixed max height and a proportional width.
                val maxThumbHeight = 192
                val srcWidth = options.outWidth
                val srcHeight = options.outHeight
                val targetHeight = maxThumbHeight
                val targetWidth = if (srcHeight > 0) {
                    (srcWidth * targetHeight / srcHeight.toFloat()).toInt().coerceAtLeast(1)
                } else {
                    (targetHeight * 4 / 3f).toInt()
                }
                val sampleSize = calculateInSampleSize(srcWidth, srcHeight, targetWidth, targetHeight)
                val decodeOptions = BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                }
                val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
                    ?: return@Thread

                // Detect AprilTags and draw overlays on the decoded image.
                val (detections, annotated) = try {
                    aprilTagDetector.detect(bitmap, annotate = true)
                } catch (e: Exception) {
                    Log.e(TAG, "AprilTag detection failed", e)
                    emptyList<AprilTagDetector.Detection>() to null
                }
                if (detections.isNotEmpty()) {
                    Log.i(TAG, "AprilTag detections: ${detections.map { it.id }}")
                }
                val sourceForThumbnail = annotated ?: bitmap
                val thumbnail = Bitmap.createScaledBitmap(sourceForThumbnail, targetWidth, targetHeight, true)

                // Keep the annotated full image for the zoom overlay. Recycle the
                // previous one first so we don't accumulate full-resolution bitmaps.
                lastAnnotatedFull?.takeIf { !it.isRecycled }?.recycle()
                lastAnnotatedFull = annotated ?: BitmapFactory.decodeFile(file.absolutePath)

                // The original decoded bitmap is no longer needed; ownership of the
                // annotated image has been transferred to lastAnnotatedFull.
                bitmap.recycle()

                lastCapturedThumbnail = thumbnail
                runOnUiThread {
                    if (!isZoomOpen) {
                        thumbnailLastCapture.setImageBitmap(thumbnail)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate thumbnail", e)
            }
        }.start()
    }

    private fun calculateInSampleSize(srcWidth: Int, srcHeight: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (srcHeight > reqHeight || srcWidth > reqWidth) {
            val halfHeight = srcHeight / 2
            val halfWidth = srcWidth / 2
            while (halfWidth / inSampleSize >= reqWidth && halfHeight / inSampleSize >= reqHeight) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    private fun showZoomOverlay(file: File) {
        try {
            // Prefer the annotated full image if AprilTag detection has finished.
            val cached = lastAnnotatedFull
            val bitmap = when {
                cached != null && !cached.isRecycled -> cached
                else -> BitmapFactory.decodeFile(file.absolutePath)
            } ?: return
            zoomImage.setImageBitmap(bitmap)
            zoomOverlay.visibility = View.VISIBLE
            isZoomOpen = true
            thumbnailUpdateHandler.post(thumbnailUpdateRunnable)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load full image for zoom", e)
            Toast.makeText(this, "Failed to open image", Toast.LENGTH_SHORT).show()
        }
    }

    private fun hideZoomOverlay() {
        isZoomOpen = false
        thumbnailUpdateHandler.removeCallbacks(thumbnailUpdateRunnable)
        zoomOverlay.visibility = View.GONE
        lastCapturedThumbnail?.let {
            thumbnailLastCapture.setImageBitmap(it)
        } ?: run {
            thumbnailLastCapture.setImageDrawable(null)
        }
    }

    /**
     * Copy the current live preview frame into the thumbnail ImageView.
     * With SurfaceView preview this would require a full NV21→Bitmap conversion,
     * so we leave the thumbnail showing the last captured still image.
     */
    private fun updateThumbnailWithLiveFrame() {
        // SurfaceView does not expose a getBitmap() API. Live preview in the
        // thumbnail is disabled to avoid GPU/CPU readback.
    }

    private fun queuePermissionRequest(device: UsbDevice) {
        if (isEspDownloadModeDevice(device)) {
            Log.d(TAG, "Ignoring permission request for ESP32 download-mode device")
            return
        }
        if (!pendingPermissionDevices.any { it.deviceId == device.deviceId } && currentDevice?.deviceId != device.deviceId) {
            pendingPermissionDevices.add(device)
            processNextPermission()
        }
    }

    private fun processNextPermission() {
        val client = cameraClient ?: return
        if (isRequestingPermission || pendingPermissionDevices.isEmpty()) return

        val device = pendingPermissionDevices.removeAt(0)
        isRequestingPermission = true
        permissionRequestStartTime = SystemClock.elapsedRealtime()
        runOnUiThread {
            statusCamera.text = "Requesting permission for\n${device.deviceName}..."
        }
        try {
            client.requestPermission(device)
        } catch (e: Exception) {
            Log.e(TAG, "Exception requesting permission", e)
            isRequestingPermission = false
            processNextPermission()
        }
    }

    private fun updateDeviceList() {
        val client = cameraClient ?: return
        val devices = client.getDeviceList()?.filter { !isEspDownloadModeDevice(it) }
        if (devices.isNullOrEmpty()) {
            Log.d(TAG, "No camera devices initially found")
            return
        }
        devices.firstOrNull()?.let { queuePermissionRequest(it) }
    }

    /**
     * Open the camera once both the USB control block and the SurfaceView Surface
     * are ready. Called on the main thread.
     */
    private fun openCameraWithSetup(setup: PendingCameraSetup) {
        try {
            setup.camera.openCamera(surfaceCamera, setup.request)
            currentCamera = setup.camera
            cameraOpenedTime = SystemClock.elapsedRealtime()
            lastFrameTime = 0L
            Log.d(TAG, "Camera opened on SurfaceView")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open camera on SurfaceView", e)
            setup.camera.closeCamera()
        }
    }

    /**
     * Applies the software-defined [cameraPreviewRotation] to the preview surface.
     *
     * The [AspectRatioSurfaceView] public setter swaps width/height based on the
     * device's configuration orientation. We pass the dimensions in the order that
     * makes the final measured aspect ratio match the *displayed* frame (after
     * applying [cameraPreviewRotation]), so the preview is never stretched.
     */
    private fun applyPreviewRotation() {
        val previewSize = currentCamera?.getPreviewSize()
        val frameW = (previewSize?.width ?: PREVIEW_WIDTH).toFloat()
        val frameH = (previewSize?.height ?: PREVIEW_HEIGHT).toFloat()
        val effectiveRotation = cameraPreviewRotation % 360f

        val isSideways = effectiveRotation % 180f != 0f
        val displayedW = if (isSideways) frameH else frameW
        val displayedH = if (isSideways) frameW else frameH

        // The library's public setter computes the ratio differently in portrait
        // and landscape; compensate so the resulting ratio is displayedW/displayedH.
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        if (isPortrait) {
            surfaceCamera.setAspectRatio(displayedH.toInt(), displayedW.toInt())
        } else {
            surfaceCamera.setAspectRatio(displayedW.toInt(), displayedH.toInt())
        }
        Log.d(TAG, "Applied preview rotation: effective=$effectiveRotation, frame=${frameW.toInt()}x${frameH.toInt()}, displayed=${displayedW.toInt()}x${displayedH.toInt()}")
    }

    /**
     * Start the background thread that runs AprilTag detection on the live
     * preview bitmap and updates [aprilTagOverlay].
     */
    private fun startLiveAprilTagDetection() {
        if (aprilTagThread != null) return
        val thread = android.os.HandlerThread("AprilTagLive").apply { start() }
        aprilTagThread = thread
        aprilTagHandler = Handler(thread.looper)
        aprilTagHandler?.post(aprilTagRunnable)
        Log.d(TAG, "Live AprilTag detection started")
    }

    /**
     * Stop the live AprilTag detection thread.
     */
    private fun stopLiveAprilTagDetection() {
        aprilTagHandler?.removeCallbacks(aprilTagRunnable)
        aprilTagThread?.quitSafely()
        aprilTagThread = null
        aprilTagHandler = null
    }

    /**
     * Grab the latest NV21 preview frame from the camera callback and run AprilTag
     * detection on its Y plane. The live stream is already upright thanks to the
     * sensor-level vertical flip in the ESP32 firmware, so no software orientation
     * search is done here.
     *
     * Detections are passed through [aprilTagTracker] so only temporally stable
     * tags are drawn. When a complete Macbeth chart is seen, the full NV21 frame
     * is converted to a colour Bitmap for the colour-correction matrix and debug
     * save; this expensive conversion is only done when needed.
     */
    private fun updateLiveAprilTagOverlay() {
        if (currentCamera == null || isFinishing) return
        val frame = previewFrameQueue.poll() ?: return
        val frameW = frame.width
        val frameH = frame.height

        val detectStart = SystemClock.elapsedRealtime()
        val (detections, detectW, detectH) = try {
            // Use only the Y plane (first width*height bytes) for fast grayscale detection.
            // Subsample to 640x480 so the detector's internal quad_decimate=2 pipeline
            // runs at 320x240, giving the same stable, low-jitter behaviour we had with
            // the TextureView bitmap path while avoiding the GPU readback and RGB conversion.
            val yPlane = frame.data.copyOfRange(0, frameW * frameH)
            val (scaledY, scaledW, scaledH) = subsampleYPlane(yPlane, frameW, frameH, 640, 480)
            aprilTagTracker.maxPositionJumpPx = 24f
            Triple(aprilTagDetector.detectGray(scaledY, scaledW, scaledH), scaledW, scaledH)
        } catch (e: Exception) {
            Log.w(TAG, "AprilTag detection failed", e)
            return
        }
        val detectTime = SystemClock.elapsedRealtime() - detectStart

        val trackStart = SystemClock.elapsedRealtime()
        val stableDetections = aprilTagTracker.update(detections)
        val trackTime = SystemClock.elapsedRealtime() - trackStart

        if (detections.isNotEmpty() || stableDetections.isNotEmpty()) {
            Log.d(TAG, "AprilTag cycle: frame=${frameW}x${frameH} detect=${detectTime}ms track=${trackTime}ms raw=${detections.size} stable=${stableDetections.size}")
        }

        if (stableDetections.isEmpty()) {
            runOnUiThread { aprilTagOverlay.detections = emptyList() }
            return
        }

        // Map detections from detection-frame coordinates to the overlay view's
        // coordinate space. AspectRatioSurfaceView preserves the camera aspect ratio
        // and fits the video inside the container with top|center_horizontal gravity,
        // so the active video area is a centered, aspect-ratio-preserving rectangle.
        val overlayW = aprilTagOverlay.width.toFloat()
        val overlayH = aprilTagOverlay.height.toFloat()
        val cameraAspect = if (detectH > 0) detectW.toFloat() / detectH else 0f
        val overlayAspect = if (overlayH > 0) overlayW / overlayH else 0f

        // Compute the rendered video rectangle within the overlay (uniform scale).
        val renderedW: Float
        val renderedH: Float
        val offsetX: Float
        val offsetY: Float
        if (overlayW <= 0 || overlayH <= 0 || cameraAspect <= 0f) {
            renderedW = overlayW
            renderedH = overlayH
            offsetX = 0f
            offsetY = 0f
        } else if (overlayAspect > cameraAspect) {
            // Container is wider than camera: fit to height, letterbox left/right.
            renderedH = overlayH
            renderedW = renderedH * cameraAspect
            offsetX = (overlayW - renderedW) / 2f
            offsetY = 0f
        } else {
            // Container is taller than camera (or same aspect): fit to width, letterbox top/bottom.
            renderedW = overlayW
            renderedH = renderedW / cameraAspect
            offsetX = (overlayW - renderedW) / 2f
            offsetY = 0f
        }

        val scale = if (detectW > 0) renderedW / detectW else if (detectH > 0) renderedH / detectH else 1f

        val mirrorH = cameraPreviewMirrorH
        val overlayDetections = stableDetections.map { d ->
            val mappedCorners = d.corners.map { (x, y) ->
                var vx = offsetX + x * scale
                val vy = offsetY + y * scale
                if (mirrorH && renderedW > 0f) {
                    vx = offsetX + renderedW - (x * scale)
                }
                vx to vy
            }
            AprilTagOverlayView.Detection(d.id, mappedCorners)
        }

        runOnUiThread { aprilTagOverlay.detections = overlayDetections }

        // Scale stable detections back to the original camera-frame coordinate space
        // for Macbeth chart decoding and debug saves, then convert to colour Bitmap.
        val ccmScaleX = if (detectW > 0) frameW.toFloat() / detectW else 1f
        val ccmScaleY = if (detectH > 0) frameH.toFloat() / detectH else 1f
        val frameStableDetections = stableDetections.map { d ->
            AprilTagDetector.Detection(
                d.id,
                d.corners.map { (x, y) -> (x * ccmScaleX) to (y * ccmScaleY) }
            )
        }

        if (frameStableDetections.count { it.id in MACBETH_CORNER_IDS } >= 4) {
            val bitmap = nv21ToBitmap(frame.data, frameW, frameH)
            if (bitmap != null) {
                val result = MacbethColorCorrector.correctFromAprilTags(bitmap, frameStableDetections)
                if (result != null) {
                    colorCorrectionMatrix = result.ccm
                    colorCorrectionEnabled = true
                    Log.i(TAG, "Updated CCM from ${result.chartName}, mean error=${result.meanError}")
                }
                saveDebugPreview(bitmap, frameStableDetections)
            }
        }
    }

    /**
     * Save the preview bitmap and an annotated copy so the host can verify
     * AprilTag decoding and color accuracy.  Rate-limited to once per five
     * seconds to avoid filling storage.
     *
     * If a colour-correction matrix has been computed from a Macbeth chart, an
     * additional corrected image is saved when colour correction is enabled.
     */
    private fun saveDebugPreview(bitmap: Bitmap, detections: List<AprilTagDetector.Detection>) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastDebugPreviewSaveTime < 5000L) {
            bitmap.recycle()
            return
        }
        lastDebugPreviewSaveTime = now

        Thread {
            try {
                val picturesDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                    ?: File(filesDir, "Pictures")
                val dir = File(picturesDir, "DebugPreview")
                if (!dir.exists()) dir.mkdirs()

                val rawFile = File(dir, "win_raw.jpg")
                FileOutputStream(rawFile).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }

                val annotated = aprilTagDetector.drawDetections(bitmap, detections)
                val annFile = File(dir, "win_annotated.jpg")
                FileOutputStream(annFile).use { annotated.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                annotated.recycle()

                val ccm = colorCorrectionMatrix
                if (colorCorrectionEnabled && ccm != null) {
                    val corrected = MacbethColorCorrector.applyCcm(bitmap, ccm)
                    val corrFile = File(dir, "win_corrected.jpg")
                    FileOutputStream(corrFile).use { corrected.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                    corrected.recycle()
                    Log.i(TAG, "Saved debug preview: raw=${rawFile.absolutePath}, annotated=${annFile.absolutePath}, corrected=${corrFile.absolutePath}")
                } else {
                    Log.i(TAG, "Saved debug preview: raw=${rawFile.absolutePath}, annotated=${annFile.absolutePath}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save debug preview", e)
            } finally {
                bitmap.recycle()
            }
        }.start()
    }

    /**
     * Subsample a Y-plane byte buffer to roughly [targetWidth]x[targetHeight]
     * using nearest-neighbour sampling. This keeps detection fast and stable
     * while the SurfaceView still renders the full-resolution camera stream.
     */
    private fun subsampleYPlane(
        yPlane: ByteArray,
        srcW: Int,
        srcH: Int,
        targetWidth: Int,
        targetHeight: Int
    ): Triple<ByteArray, Int, Int> {
        if (srcW <= targetWidth && srcH <= targetHeight) {
            return Triple(yPlane, srcW, srcH)
        }
        val scaleX = srcW.toFloat() / targetWidth.toFloat()
        val scaleY = srcH.toFloat() / targetHeight.toFloat()
        val scale = maxOf(scaleX, scaleY)
        val dstW = (srcW / scale).toInt().coerceAtLeast(1)
        val dstH = (srcH / scale).toInt().coerceAtLeast(1)
        val dst = ByteArray(dstW * dstH)
        var di = 0
        for (y in 0 until dstH) {
            val sy = (y * scale).toInt().coerceIn(0, srcH - 1)
            val srcRow = sy * srcW
            for (x in 0 until dstW) {
                val sx = (x * scale).toInt().coerceIn(0, srcW - 1)
                dst[di++] = yPlane[srcRow + sx]
            }
        }
        return Triple(dst, dstW, dstH)
    }

    /**
     * Convert an NV21 byte array to an ARGB_8888 Bitmap. This is only used for
     * debug saves and colour-correction matrix computation; detection runs directly
     * on the Y plane to avoid this expensive conversion every frame.
     */
    private fun nv21ToBitmap(nv21: ByteArray, width: Int, height: Int): Bitmap? {
        try {
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(width * height)
            val ySize = width * height
            var i = 0
            for (y in 0 until height) {
                val yRow = y * width
                val uvRow = (y shr 1) * width
                for (x in 0 until width) {
                    val yVal = (nv21[yRow + x].toInt() and 0xFF) - 16
                    val uvIndex = uvRow + (x and -2)
                    val vVal = (nv21[ySize + uvIndex].toInt() and 0xFF) - 128
                    val uVal = (nv21[ySize + uvIndex + 1].toInt() and 0xFF) - 128

                    var r = (298 * yVal + 409 * vVal + 128) shr 8
                    var g = (298 * yVal - 100 * uVal - 208 * vVal + 128) shr 8
                    var b = (298 * yVal + 516 * uVal + 128) shr 8

                    if (r < 0) r = 0 else if (r > 255) r = 255
                    if (g < 0) g = 0 else if (g > 255) g = 255
                    if (b < 0) b = 0 else if (b > 255) b = 255

                    pixels[i++] = -0x1000000 or (r shl 16) or (g shl 8) or b
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            return bitmap
        } catch (e: Exception) {
            Log.e(TAG, "Failed to convert NV21 to bitmap", e)
            return null
        }
    }

    private fun shouldRotateDevice(device: UsbDevice): Boolean {
        // Default to no automatic inversion; use the Rotate button if a feed
        // needs to be turned.
        return false
    }

    private fun checkAndRequestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val permissionsToRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsToRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsToRequest, REQUEST_CAMERA_PERMISSION)
        } else {
            initCameraClient()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            initCameraClient()
        } else if (requestCode == REQUEST_RECORD_PERMISSION) {
            if (pendingStartRecording && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                pendingStartRecording = false
                startRecording()
            } else {
                pendingStartRecording = false
                Toast.makeText(this, "Recording requires camera and microphone permissions", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        Log.d(TAG, "Configuration changed: orientation=${newConfig.orientation}")
        applyPreviewRotation()
    }

    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun keepScreenOn() {
        try {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteSupportHeadset::CaptureWakeLock")
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire(10 * 60 * 1000L) // 10 minute timeout as a safety net
            Log.d(TAG, "Wake lock acquired")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to acquire wake lock", e)
        }
    }

    /**
     * AndroidUSBCamera's video recorder checks for WRITE_EXTERNAL_STORAGE even when
     * writing to the app's private files directory. On Android 10+ that permission can
     * no longer be granted, so report it as granted here to allow recording to proceed.
     */
    override fun checkSelfPermission(permission: String): Int {
        if (permission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
            return PackageManager.PERMISSION_GRANTED
        }
        return super.checkSelfPermission(permission)
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        if (permission == Manifest.permission.WRITE_EXTERNAL_STORAGE) {
            return PackageManager.PERMISSION_GRANTED
        }
        return super.checkPermission(permission, pid, uid)
    }

    // -------------------------------------------------------------------------
    // FPS counting and diagnostics
    // -------------------------------------------------------------------------

    private fun computeFps() {
        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastFpsReset.get()
        val count = frameCount.getAndSet(0)
        lastFpsReset.set(now)
        currentFps = if (elapsed > 0) ((count * 1000) / elapsed).toInt() else 0
        Log.d(TAG, "FPS: $currentFps")

        val request = currentCamera?.getCameraRequest()
        val previewSize = currentCamera?.getPreviewSize()
        if (request != null && previewSize != null) {
            runOnUiThread {
                labelCamera.text = "${previewSize.width}x${previewSize.height} @ $currentFps FPS"
            }
        }
    }

    /**
     * Gathers slow / thread-sensitive diagnostics data once when the camera
     * connects. The values are cached so [updateDiagnostics] can run cheaply
     * on the main thread every refresh interval.
     */
    private fun populateDiagnosticsCache(device: UsbDevice, ctrlBlock: USBMonitor.UsbControlBlock?) {
        try {
            // USB device summary
            val sb = StringBuilder()
            sb.appendLine("  Name:        ${device.deviceName}")
            sb.appendLine("  Vendor ID:   0x%04X".format(device.vendorId))
            sb.appendLine("  Product ID:  0x%04X".format(device.productId))
            sb.appendLine("  Class:       0x%02X / Sub: 0x%02X / Proto: 0x%02X".format(
                device.deviceClass, device.deviceSubclass, device.deviceProtocol))
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                sb.appendLine("  Product:     ${device.productName ?: "n/a"}")
                sb.appendLine("  Manufacturer:${device.manufacturerName ?: "n/a"}")
            }
            val info = try {
                ctrlBlock?.let {
                    USBMonitor.updateDeviceInfo(
                        getSystemService(Context.USB_SERVICE) as android.hardware.usb.UsbManager,
                        device,
                        null
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "updateDeviceInfo failed", e)
                null
            }
            info?.let {
                sb.appendLine("  USB Version: ${it.usb_version}")
                sb.appendLine("  Version:     ${it.version}")
                sb.appendLine("  Serial:      ${it.serial ?: "n/a"}")
            }
            cachedUsbDeviceInfo = sb.toString()

            // USB interfaces
            val ifaceSb = StringBuilder()
            for (i in 0 until device.interfaceCount) {
                val iface = device.getInterface(i)
                ifaceSb.appendLine("  [$i] ${usbClassName(iface.interfaceClass)} (0x%02X) / Sub: 0x%02X / Proto: 0x%02X | EPs: ${iface.endpointCount}".format(
                    iface.interfaceClass, iface.interfaceSubclass, iface.interfaceProtocol))
                for (j in 0 until iface.endpointCount) {
                    val ep = iface.getEndpoint(j)
                    val dir = if (ep.direction == UsbConstants.USB_DIR_IN) "IN" else "OUT"
                    val type = when (ep.type) {
                        UsbConstants.USB_ENDPOINT_XFER_BULK -> "BULK"
                        UsbConstants.USB_ENDPOINT_XFER_INT -> "INT"
                        UsbConstants.USB_ENDPOINT_XFER_ISOC -> "ISOC"
                        else -> "?"
                    }
                    ifaceSb.appendLine("       EP$j $dir $type maxPacket=${ep.maxPacketSize} addr=0x%02X".format(ep.address))
                }
            }
            cachedUsbInterfaces = ifaceSb.toString()

            // Supported preview sizes
            val sizeList = try {
                currentCamera?.getAllPreviewSizes()
            } catch (e: Exception) {
                Log.w(TAG, "getAllPreviewSizes failed", e)
                null
            }
            cachedSupportedSizes = if (!sizeList.isNullOrEmpty()) {
                sizeList.joinToString { "${it.width}x${it.height}" }
            } else {
                "not available"
            }

            // UAC channels
            cachedUacChannels = try {
                parseUacChannels(ctrlBlock)
            } catch (e: Exception) {
                Log.w(TAG, "parseUacChannels failed", e)
                -1
            }

            Log.d(TAG, "Diagnostics cache populated")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to populate diagnostics cache", e)
        }
    }

    private fun updateDiagnostics() {
        if (!diagnosticsVisible) return
        val sb = StringBuilder()
        sb.appendLine("=== RemoteSupportHeadset Diagnostics ===")
        sb.appendLine()

        val device = currentDevice
        if (device == null) {
            sb.appendLine("No USB camera currently connected.")
            diagnosticsText.text = sb.toString()
            return
        }

        // USB device summary (cached)
        sb.appendLine("USB DEVICE")
        sb.appendLine(cachedUsbDeviceInfo.ifEmpty { "  (pending...)" })
        sb.appendLine()

        // USB profiles / interfaces (cached)
        sb.appendLine("USB PROFILES (interfaces)")
        sb.appendLine(cachedUsbInterfaces.ifEmpty { "  (pending...)" })
        var vendorSpecificCount = 0
        for (i in 0 until device.interfaceCount) {
            if (device.getInterface(i).interfaceClass == UsbConstants.USB_CLASS_VENDOR_SPEC) {
                vendorSpecificCount++
            }
        }
        sb.appendLine()

        // Camera resolution / frame rate
        val request = currentCamera?.getCameraRequest()
        val previewSize = currentCamera?.getPreviewSize()
        sb.appendLine("VIDEO STREAM")
        if (previewSize != null) {
            sb.appendLine("  Current resolution: ${previewSize.width}x${previewSize.height}")
        } else {
            sb.appendLine("  Current resolution: --x--")
        }
        sb.appendLine("  Requested resolution: ${request?.previewWidth ?: PREVIEW_WIDTH}x${request?.previewHeight ?: PREVIEW_HEIGHT}")
        sb.appendLine("  Frame rate (measured): $currentFps FPS")
        sb.appendLine("  Supported sizes: ${cachedSupportedSizes.ifEmpty { "pending..." }}")
        sb.appendLine()

        // Audio
        sb.appendLine("AUDIO")
        val hasUacMic = isUacMicPresent(device)
        sb.appendLine("  UAC microphone present: $hasUacMic")
        if (hasUacMic) {
            if (cachedUacChannels > 0) {
                sb.appendLine("  UAC channels: $cachedUacChannels")
            } else {
                sb.appendLine("  UAC channels: unknown (descriptor parse failed or pending)")
            }
        }
        sb.appendLine()

        // I2C
        sb.appendLine("I2C / AUXILIARY BUS")
        sb.appendLine("  Android has no generic USB-I2C API.")
        if (vendorSpecificCount > 0) {
            sb.appendLine("  $vendorSpecificCount vendor-specific interface(s) detected; one may be an I2C/SPI bridge.")
        } else {
            sb.appendLine("  No vendor-specific interfaces detected.")
        }
        sb.appendLine()

        sb.appendLine("CDC STATUS")
        sb.appendLine("  Control IF: ${if (cdcControlInterface != null) "yes" else "no"}")
        sb.appendLine("  Data IF:    ${if (cdcDataInterface != null) "yes" else "no"}")
        sb.appendLine("  Out EP:     ${if (cdcOutEndpoint != null) "yes" else "no"}")
        sb.appendLine("  In EP:      ${if (cdcInEndpoint != null) "yes" else "no"}")

        diagnosticsText.text = sb.toString()
    }

    /**
     * Returns true if the USB device exposes a USB Audio Class microphone.
     * This checks for an Audio Control interface with an input terminal, or
     * an Audio Streaming input interface.
     */
    private fun isUacMicPresent(device: UsbDevice): Boolean {
        for (i in 0 until device.interfaceCount) {
            val iface = device.getInterface(i)
            if (iface.interfaceClass == UsbConstants.USB_CLASS_AUDIO) {
                // AUDIOCONTROL (0x01) or AUDIOSTREAMING (0x02) with input direction.
                if (iface.interfaceSubclass == 0x01 || iface.interfaceSubclass == 0x02) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Attempt to read the number of channels from a USB Audio Class streaming
     * interface descriptor in the device's raw descriptors.
     *
     * Returns -1 if it cannot be determined.
     */
    private fun parseUacChannels(ctrlBlock: USBMonitor.UsbControlBlock?): Int {
        ctrlBlock ?: return -1
        val descriptors = try {
            ctrlBlock.rawDescriptors
        } catch (e: Exception) {
            Log.w(TAG, "Cannot read raw descriptors", e)
            return -1
        }
        if (descriptors == null || descriptors.isEmpty()) return -1

        // Walk configuration descriptors looking for Audio Streaming interfaces,
        // then class-specific AS FORMAT_TYPE descriptors (bDescriptorSubtype 0x02).
        // FORMAT_TYPE descriptor layout:
        //   bLength, bDescriptorType (0x24), bDescriptorSubtype (0x02),
        //   bFormatType, bNrChannels, bSubframeSize, ...
        var i = 0
        var inAudioStreamingInterface = false
        while (i + 2 < descriptors.size) {
            val length = descriptors[i].toInt() and 0xFF
            val descriptorType = descriptors[i + 1].toInt() and 0xFF
            if (length < 2 || i + length > descriptors.size) break

            when (descriptorType) {
                0x04 -> { // Interface descriptor
                    if (i + 8 < descriptors.size) {
                        val ifaceClass = descriptors[i + 5].toInt() and 0xFF
                        val ifaceSubClass = descriptors[i + 6].toInt() and 0xFF
                        inAudioStreamingInterface = (ifaceClass == 0x01 && ifaceSubClass == 0x02)
                    }
                }
                0x24 -> { // Class-specific interface descriptor
                    if (inAudioStreamingInterface && i + 5 < descriptors.size) {
                        val subtype = descriptors[i + 2].toInt() and 0xFF
                        if (subtype == 0x02) {
                            val channels = descriptors[i + 4].toInt() and 0xFF
                            if (channels in 1..32) return channels
                        }
                    }
                }
            }
            i += length
        }
        return -1
    }

    private fun usbClassName(cls: Int): String {
        return when (cls) {
            UsbConstants.USB_CLASS_AUDIO -> "AUDIO"
            UsbConstants.USB_CLASS_CDC_DATA -> "CDC_DATA"
            UsbConstants.USB_CLASS_COMM -> "COMM"
            UsbConstants.USB_CLASS_HID -> "HID"
            UsbConstants.USB_CLASS_MASS_STORAGE -> "MASS_STORAGE"
            UsbConstants.USB_CLASS_MISC -> "MISC"
            UsbConstants.USB_CLASS_PER_INTERFACE -> "PER_INTERFACE"
            UsbConstants.USB_CLASS_PHYSICA -> "PHYSICA"
            UsbConstants.USB_CLASS_PRINTER -> "PRINTER"
            UsbConstants.USB_CLASS_STILL_IMAGE -> "STILL_IMAGE"
            UsbConstants.USB_CLASS_VENDOR_SPEC -> "VENDOR_SPEC"
            UsbConstants.USB_CLASS_VIDEO -> "VIDEO"
            UsbConstants.USB_CLASS_WIRELESS_CONTROLLER -> "WIRELESS"
            else -> "0x%02X".format(cls)
        }
    }

    // -------------------------------------------------------------------------

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        Log.d(TAG, "onNewIntent: $intent")
        handleIntentActions(intent)
    }

    /**
     * Handle intent extras that trigger captures, zoom, or lifecycle tests.
     * Called from both [onCreate] (first launch) and [onNewIntent] (subsequent
     * intents while the activity is already running, e.g. repeated ADB taps).
     */
    private fun handleIntentActions(intent: Intent?) {
        intent ?: return
        val firmwareUrl = intent.getStringExtra(EXTRA_FIRMWARE_URL)
        if (firmwareUrl != null) {
            val skipConfirmation = intent.getBooleanExtra(EXTRA_FLASH_NOW, false)
            Log.d(TAG, "EXTRA_FIRMWARE_URL=$firmwareUrl, flash_now=$skipConfirmation")
            downloadFirmwareFromUrl(firmwareUrl, skipConfirmation)
        } else if (intent.getBooleanExtra(EXTRA_FLASH_NOW, false)) {
            Log.d(TAG, "EXTRA_FLASH_NOW requested, starting flash flow without confirmation")
            startFirmwareFlashFlow(skipConfirmation = true)
        }

        if (intent.getBooleanExtra(EXTRA_CAPTURE_NOW, false)) {
            val idx = intent.getIntExtra(EXTRA_SIMULATED_CAPTURE_INDEX, -1)
            Log.d(TAG, "EXTRA_CAPTURE_NOW requested, simulatedIndex=$idx")
            captureStillImage(simulatedIndex = idx)
        }
        intent.getIntExtra(EXTRA_LIFECYCLE_TEST_COUNT, 0).let { count ->
            if (count > 0) {
                val zoomMode = intent.getBooleanExtra(EXTRA_LIFECYCLE_ZOOM_TEST, false)
                Log.d(TAG, "EXTRA_LIFECYCLE_TEST_COUNT=$count requested, zoomMode=$zoomMode")
                runLifecycleTest(count, zoomMode)
            }
        }

        if (intent.getBooleanExtra(EXTRA_ZOOM_OPEN, false)) {
            Log.d(TAG, "EXTRA_ZOOM_OPEN requested")
            lastCapturedFile?.let { showZoomOverlay(it) }
        }
        if (intent.getBooleanExtra(EXTRA_ZOOM_CLOSE, false)) {
            Log.d(TAG, "EXTRA_ZOOM_CLOSE requested")
            hideZoomOverlay()
        }
    }

    override fun onStart() {
        super.onStart()
        cameraClient?.register()
        mainHandler.post(fpsRunnable)
        mainHandler.post(diagnosticsRunnable)
        mainHandler.post(devicePollRunnable)
        mainHandler.post(cameraHealthCheckRunnable)
        startMicMeter()
        startSpkMeter()
        mainHandler.post(firmwareVersionRunnable)
    }

    override fun onStop() {
        super.onStop()
        if (recordingState != RecordingState.IDLE) {
            stopRecording()
        }
        stopMicMeter()
        stopSpkMeter()
        stopLiveAprilTagDetection()
        mainHandler.removeCallbacks(fpsRunnable)
        mainHandler.removeCallbacks(diagnosticsRunnable)
        mainHandler.removeCallbacks(devicePollRunnable)
        mainHandler.removeCallbacks(cameraHealthCheckRunnable)
        mainHandler.removeCallbacks(firmwareVersionRunnable)
        currentCamera?.closeCamera()
        currentCamera = null
        currentDevice = null
        currentCtrlBlock = null
        cameraOpenedTime = 0L
        releaseCdc()
        pendingPermissionDevices.clear()
        isRequestingPermission = false
        cameraClient?.unRegister()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLifecycleTest()
        stopLiveAprilTagDetection()
        isZoomOpen = false
        thumbnailUpdateHandler.removeCallbacks(thumbnailUpdateRunnable)
        cameraClient?.unRegister()
        cameraClient?.destroy()
        try {
            wakeLock?.release()
            Log.d(TAG, "Wake lock released")
        } catch (_: Exception) { }
    }

    // -------------------------------------------------------------------------
    // Microphone VU meter
    // -------------------------------------------------------------------------

    private fun startMicMeter() {
        if (micMeterRunning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start mic meter: RECORD_AUDIO not granted")
            return
        }

        val sampleRate = 44100
        val channelConfig = AudioFormat.CHANNEL_IN_MONO
        val audioFormat = AudioFormat.ENCODING_PCM_16BIT
        val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
        if (minBufferSize <= 0) {
            Log.w(TAG, "Invalid AudioRecord min buffer size: $minBufferSize")
            return
        }

        try {
            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord failed to initialize")
                record.release()
                return
            }
            audioRecord = record
            audioBuffer = ShortArray(minBufferSize)
            micMeterRunning = true
            record.startRecording()

            micMeterThread = Thread {
                while (micMeterRunning) {
                    val read = record.read(audioBuffer, 0, audioBuffer.size)
                    if (read > 0) {
                        val rmsDb = computeRmsDb(audioBuffer, read)
                        runOnUiThread { updateMicMeter(rmsDb) }
                    }
                }
            }.apply {
                isDaemon = true
                name = "MicMeterThread"
                start()
            }
            Log.d(TAG, "Mic meter started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mic meter", e)
        }
    }

    private fun stopMicMeter() {
        micMeterRunning = false
        micMeterThread?.join(500)
        micMeterThread = null
        try {
            audioRecord?.stop()
        } catch (_: Exception) { }
        audioRecord?.release()
        audioRecord = null
        Log.d(TAG, "Mic meter stopped")
    }

    private fun computeRmsDb(buffer: ShortArray, length: Int): Double {
        var sum = 0.0
        for (i in 0 until length) {
            val sample = buffer[i].toDouble()
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / length)
        return if (rms > 0) 20.0 * kotlin.math.log10(rms / 32768.0) else -96.0
    }

    private fun updateMicMeter(db: Double) {
        val minDb = -60.0
        val maxDb = 0.0
        val percent = ((db - minDb) / (maxDb - minDb) * 100).toInt()
            .coerceIn(0, 100)
        micLevelMeter.progress = percent
        micLevelLabel.text = "MIC: %.1f dB".format(db)
    }

    // -------------------------------------------------------------------------
    // Speaker / global output VU meter (Visualizer on audio session 0)
    // -------------------------------------------------------------------------

    private fun startSpkMeter() {
        if (spkMeterRunning) return
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            Log.w(TAG, "Cannot start speaker meter: RECORD_AUDIO not granted")
            return
        }

        try {
            val vis = Visualizer(0)
            try {
                vis.captureSize = Visualizer.getCaptureSizeRange()[1]
            } catch (e: IllegalStateException) {
                // Some devices return an already-enabled Visualizer for session 0.
                Log.w(TAG, "Visualizer captureSize set failed, using default: ${e.message}")
            }
            vis.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(
                    visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                ) {}

                override fun onFftDataCapture(
                    visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                ) {}
            }, Visualizer.getMaxCaptureRate() / 2, false, true)
            vis.enabled = true
            visualizer = vis

            spkMeterRunning = true
            spkMeterThread = Thread {
                val captureSize = vis.captureSize
                val waveform = ByteArray(captureSize)
                while (spkMeterRunning) {
                    val result = vis.getWaveForm(waveform)
                    if (result == Visualizer.SUCCESS) {
                        val db = computeWaveformDb(waveform)
                        runOnUiThread { updateSpkMeter(db) }
                    }
                    Thread.sleep(50)
                }
            }.apply {
                isDaemon = true
                name = "SpkMeterThread"
                start()
            }
            Log.d(TAG, "Speaker meter started")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start speaker meter", e)
        }
    }

    private fun stopSpkMeter() {
        spkMeterRunning = false
        spkMeterThread?.join(500)
        spkMeterThread = null
        try {
            visualizer?.enabled = false
        } catch (_: Exception) { }
        visualizer?.release()
        visualizer = null
        Log.d(TAG, "Speaker meter stopped")
    }

    /**
     * Compute a dB value from Visualizer waveform bytes (0..255, 128 = zero).
     * Returns a negative dBFS-like value mapped to the same scale as the mic meter.
     */
    private fun computeWaveformDb(waveform: ByteArray): Double {
        var sum = 0.0
        var count = 0
        for (b in waveform) {
            val sample = (b.toInt() and 0xFF) - 128
            if (sample != 0) {
                sum += sample * sample
                count++
            }
        }
        if (count == 0) return -96.0
        val rms = kotlin.math.sqrt(sum / count)
        return if (rms > 0) 20.0 * kotlin.math.log10(rms / 128.0) else -96.0
    }

    private fun updateSpkMeter(db: Double) {
        val minDb = -60.0
        val maxDb = 0.0
        val percent = ((db - minDb) / (maxDb - minDb) * 100).toInt()
            .coerceIn(0, 100)
        spkLevelMeter.progress = percent
        spkLevelLabel.text = "SPK: %.1f dB".format(db)
    }

}
