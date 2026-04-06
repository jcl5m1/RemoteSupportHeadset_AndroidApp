package com.example.remotesupportheadset

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.jiangdg.ausbc.MultiCameraClient
import com.jiangdg.ausbc.callback.IDeviceConnectCallBack
import com.jiangdg.ausbc.camera.bean.CameraRequest
import com.jiangdg.ausbc.widget.AspectRatioTextureView
import com.serenegiant.usb.USBMonitor
import kotlin.math.sqrt

class DualCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DualCameraActivity"
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val OVERLAY_TIMEOUT_MS = 5000L
        private const val ZOOM_TIMEOUT_MS = 2000L
    }

    private lateinit var textureLeft: AspectRatioTextureView
    private lateinit var textureRight: AspectRatioTextureView
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView
    private lateinit var labelLeft: TextView
    private lateinit var labelRight: TextView
    private lateinit var meterMic: ProgressBar
    private lateinit var meterSpeaker: ProgressBar
    private lateinit var seekMicGain: SeekBar
    private lateinit var seekSpeakerGain: SeekBar

    private lateinit var containerLeft: View
    private lateinit var containerRight: View
    private lateinit var divider: View
    
    private lateinit var rootLayout: ConstraintLayout
    private lateinit var containerCamerasParent: LinearLayout
    private lateinit var containerControls: LinearLayout
    private lateinit var sectionMic: LinearLayout
    private lateinit var sectionSpeaker: LinearLayout

    private var micGain: Float = 1.0f
    private var speakerGain: Float = 1.0f
    private var toneGenerator: ToneGenerator? = null
    private lateinit var audioManager: AudioManager

    private val cameraMap = LinkedHashMap<Int, MultiCameraClient.Camera>(2)
    private lateinit var cameraClient: MultiCameraClient

    private val pendingPermissionDevices = mutableListOf<UsbDevice>()
    private var isRequestingPermission = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlays() }
    
    private val autoZoomRunnable = Runnable {
        if (cameraMap.size == 1 && zoomedCameraId == null) {
            Log.d(TAG, "Auto-zooming to Camera 1 after timeout")
            toggleZoom(R.id.texture_camera_left)
        }
    }

    private var zoomedCameraId: Int? = null // null: split, R.id.texture_camera_left, R.id.texture_camera_right

    // Audio meters
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val micBuffer = ShortArray(1024)
    private var visualizer: Visualizer? = null

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_camera)

        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        hideSystemUI()

        textureLeft  = findViewById(R.id.texture_camera_left)
        textureRight = findViewById(R.id.texture_camera_right)
        statusLeft   = findViewById(R.id.status_camera_left)
        statusRight  = findViewById(R.id.status_camera_right)
        labelLeft    = findViewById(R.id.label_camera_left)
        labelRight   = findViewById(R.id.label_camera_right)
        meterMic     = findViewById(R.id.meter_mic)
        meterSpeaker = findViewById(R.id.meter_speaker)
        seekMicGain  = findViewById(R.id.seek_mic_gain)
        seekSpeakerGain = findViewById(R.id.seek_speaker_gain)

        containerLeft  = findViewById(R.id.container_camera_left)
        containerRight = findViewById(R.id.container_camera_right)
        divider        = findViewById(R.id.divider_cameras)
        
        rootLayout = findViewById(R.id.root_layout)
        containerCamerasParent = findViewById(R.id.container_cameras_parent)
        containerControls = findViewById(R.id.container_controls)
        sectionMic = findViewById(R.id.section_mic)
        sectionSpeaker = findViewById(R.id.section_speaker)

        // Use STREAM_MUSIC for both tone and volume control
        toneGenerator = ToneGenerator(AudioManager.STREAM_MUSIC, 100)

        // Mic Gain Slider - Max 400 (from XML)
        seekMicGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                micGain = progress / 100.0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Speaker Volume Slider - Matches System Volume
        val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        seekSpeakerGain.max = maxVol
        seekSpeakerGain.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        speakerGain = if (maxVol > 0) seekSpeakerGain.progress.toFloat() / maxVol else 0f

        seekSpeakerGain.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
                    playTestChime()
                }
                speakerGain = if (maxVol > 0) progress.toFloat() / maxVol else 0f
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                playTestChime()
            }
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Initially show labels, they will hide automatically after 5s
        showOverlaysTemporarily()

        // Global gesture detector for background
        val gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                showOverlaysTemporarily()
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                if (zoomedCameraId != null) {
                    toggleZoom(zoomedCameraId!!)
                }
                return true
            }
        })

        // Specific gesture listeners for textures
        val createGestureListener = { viewId: Int ->
            GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent): Boolean = true
                override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                    showOverlaysTemporarily()
                    return true
                }
                override fun onDoubleTap(e: MotionEvent): Boolean {
                    toggleZoom(viewId)
                    return true
                }
            })
        }

        val leftGD = createGestureListener(R.id.texture_camera_left)
        val rightGD = createGestureListener(R.id.texture_camera_right)

        textureLeft.setOnTouchListener { _, event -> leftGD.onTouchEvent(event) }
        textureRight.setOnTouchListener { _, event -> rightGD.onTouchEvent(event) }
        findViewById<View>(android.R.id.content).setOnTouchListener { _, event -> 
            gestureDetector.onTouchEvent(event)
            true
        }

        cameraClient = MultiCameraClient(this, object : IDeviceConnectCallBack {
            override fun onAttachDev(device: UsbDevice?) {
                device ?: return
                Log.d(TAG, "USB device attached: ${device.deviceName}")
                runOnUiThread {
                    Toast.makeText(this@DualCameraActivity, "Attached: ${device.deviceName}", Toast.LENGTH_SHORT).show()
                }
                if (cameraMap.size >= 2) return
                queuePermissionRequest(device)
            }

            override fun onConnectDev(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                device ?: return
                isRequestingPermission = false
                runOnUiThread {
                    val surface = nextFreeSurface()
                    if (surface == null) {
                        Log.w(TAG, "No free surface slot for ${device.deviceName}")
                        processNextPermission()
                        return@runOnUiThread
                    }

                    if (shouldRotateDevice(device)) {
                        surface.rotation = 180f
                    } else {
                        surface.rotation = 0f
                    }

                    // Enforce aspect ratio
                    surface.setAspectRatio(PREVIEW_WIDTH, PREVIEW_HEIGHT)

                    val camera = MultiCameraClient.Camera(this@DualCameraActivity, device)
                    camera.setUsbControlBlock(ctrlBlock)
                    val request = CameraRequest.Builder()
                        .setPreviewWidth(PREVIEW_WIDTH)
                        .setPreviewHeight(PREVIEW_HEIGHT)
                        .create()

                    camera.openCamera(surface, request)
                    cameraMap[device.deviceId] = camera

                    if (cameraMap.size == 1) {
                        statusLeft.visibility = View.GONE
                        labelLeft.text = "Camera 1\n$PREVIEW_WIDTH x $PREVIEW_HEIGHT @ 30 FPS"
                        
                        // Start 2s timer. If no 2nd camera connects, zoom to Camera 1.
                        mainHandler.removeCallbacks(autoZoomRunnable)
                        mainHandler.postDelayed(autoZoomRunnable, ZOOM_TIMEOUT_MS)
                    } else {
                        statusRight.visibility = View.GONE
                        labelRight.text = "Camera 2\n$PREVIEW_WIDTH x $PREVIEW_HEIGHT @ 30 FPS"
                        
                        // Second camera connected, cancel auto-zoom if it was pending
                        mainHandler.removeCallbacks(autoZoomRunnable)
                        
                        // Return to split view if we were zoomed
                        if (zoomedCameraId != null) {
                            toggleZoom(zoomedCameraId!!)
                        }
                    }

                    Log.d(TAG, "Camera opened: ${device.deviceName} (slot ${cameraMap.size})")
                    processNextPermission()
                }
            }

            override fun onCancelDev(device: UsbDevice?) {
                Log.w(TAG, "USB permission cancelled: ${device?.deviceName}")
                isRequestingPermission = false
                runOnUiThread {
                    Toast.makeText(this@DualCameraActivity, "USB permission denied", Toast.LENGTH_SHORT).show()
                    val nextStatus = if (cameraMap.isEmpty()) statusLeft else statusRight
                    nextStatus.text = "Permission denied\nTap to try again..."
                    nextStatus.setOnClickListener {
                        device?.let { queuePermissionRequest(it) }
                    }
                    processNextPermission()
                }
            }

            override fun onDetachDec(device: UsbDevice?) {
                device ?: return
                Log.d(TAG, "USB device detached: ${device.deviceName}")
                pendingPermissionDevices.removeAll { it.deviceId == device.deviceId }
                runOnUiThread {
                    val wasFirst = cameraMap.keys.firstOrNull() == device.deviceId
                    cameraMap.remove(device.deviceId)?.closeCamera()
                    if (wasFirst) {
                        statusLeft.visibility = View.VISIBLE
                        statusLeft.text = "Waiting for camera…"
                        labelLeft.text = "Camera 1"
                    } else if (cameraMap.size < 2) {
                        statusRight.visibility = View.VISIBLE
                        statusRight.text = "Waiting for camera…"
                        labelRight.text = "Camera 2"
                    }
                    // If the detached camera was zoomed, reset to split
                    if (zoomedCameraId != null) {
                        toggleZoom(zoomedCameraId!!)
                    }
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                Log.d(TAG, "Camera disconnected: ${device?.deviceName}")
            }
        })
        
        checkAndRequestPermissions()
    }

    private fun toggleZoom(viewId: Int) {
        val cs = ConstraintSet()
        cs.clone(rootLayout)

        if (zoomedCameraId == viewId) {
            // Reset to split view
            zoomedCameraId = null
            containerLeft.visibility = View.VISIBLE
            containerRight.visibility = View.VISIBLE
            divider.visibility = View.VISIBLE
            
            // Restore horizontal bottom controls
            cs.clear(R.id.container_controls, ConstraintSet.TOP)
            cs.connect(R.id.container_controls, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            cs.connect(R.id.container_controls, ConstraintSet.START, ConstraintSet.PARENT_ID, ConstraintSet.START)
            cs.connect(R.id.container_controls, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.constrainWidth(R.id.container_controls, ConstraintSet.MATCH_CONSTRAINT)
            cs.constrainHeight(R.id.container_controls, ConstraintSet.WRAP_CONTENT)

            cs.connect(R.id.container_cameras_parent, ConstraintSet.BOTTOM, R.id.container_controls, ConstraintSet.TOP)
            cs.connect(R.id.container_cameras_parent, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)

            containerControls.orientation = LinearLayout.HORIZONTAL
            sectionMic.orientation = LinearLayout.VERTICAL
            sectionSpeaker.orientation = LinearLayout.VERTICAL
            
            updateControlOrientation(false)
        } else {
            // Zoom into the selected camera
            zoomedCameraId = viewId
            if (viewId == R.id.texture_camera_left) {
                containerLeft.visibility = View.VISIBLE
                containerRight.visibility = View.GONE
                divider.visibility = View.GONE
            } else {
                containerLeft.visibility = View.GONE
                containerRight.visibility = View.VISIBLE
                divider.visibility = View.GONE
            }
            
            // Switch to vertical right controls
            cs.clear(R.id.container_controls, ConstraintSet.START)
            cs.clear(R.id.container_controls, ConstraintSet.BOTTOM)
            cs.connect(R.id.container_controls, ConstraintSet.END, ConstraintSet.PARENT_ID, ConstraintSet.END)
            cs.connect(R.id.container_controls, ConstraintSet.TOP, ConstraintSet.PARENT_ID, ConstraintSet.TOP)
            cs.connect(R.id.container_controls, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            cs.constrainWidth(R.id.container_controls, ConstraintSet.WRAP_CONTENT)
            cs.constrainHeight(R.id.container_controls, ConstraintSet.MATCH_CONSTRAINT)

            cs.clear(R.id.container_cameras_parent, ConstraintSet.BOTTOM)
            cs.connect(R.id.container_cameras_parent, ConstraintSet.BOTTOM, ConstraintSet.PARENT_ID, ConstraintSet.BOTTOM)
            cs.connect(R.id.container_cameras_parent, ConstraintSet.END, R.id.container_controls, ConstraintSet.START)

            containerControls.orientation = LinearLayout.VERTICAL
            sectionMic.orientation = LinearLayout.VERTICAL
            sectionSpeaker.orientation = LinearLayout.VERTICAL
            
            updateControlOrientation(true)
        }
        cs.applyTo(rootLayout)
        showOverlaysTemporarily()
    }
    
    private fun updateControlOrientation(isVertical: Boolean) {
        val rotation = if (isVertical) 270f else 0f
        
        // When vertical, we need to swap width/height logic or just rotate
        // Rotating 270 degrees makes them vertical
        meterMic.rotation = rotation
        meterSpeaker.rotation = rotation
        seekMicGain.rotation = rotation
        seekSpeakerGain.rotation = rotation
        
        // Adjust layout params for vertical mode if needed
        // For simplicity, we just use rotation and standard wrap_content
        val size = if (isVertical) 200 else ViewGroup.LayoutParams.MATCH_PARENT
        
        seekMicGain.layoutParams.width = if (isVertical) 300 else ViewGroup.LayoutParams.MATCH_PARENT
        seekSpeakerGain.layoutParams.width = if (isVertical) 300 else ViewGroup.LayoutParams.MATCH_PARENT
        meterMic.layoutParams.width = if (isVertical) 300 else ViewGroup.LayoutParams.MATCH_PARENT
        meterSpeaker.layoutParams.width = if (isVertical) 300 else ViewGroup.LayoutParams.MATCH_PARENT
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val keyName = when (keyCode) {
            KeyEvent.KEYCODE_HEADSETHOOK -> "HEADSETHOOK (HANGUP/HOOK)"
            KeyEvent.KEYCODE_VOLUME_UP -> "VOLUME_UP"
            KeyEvent.KEYCODE_VOLUME_DOWN -> "VOLUME_DOWN"
            KeyEvent.KEYCODE_VOLUME_MUTE -> "VOLUME_MUTE"
            KeyEvent.KEYCODE_MUTE -> "MIC_MUTE"
            KeyEvent.KEYCODE_ENDCALL -> "END_CALL (HANGUP)"
            KeyEvent.KEYCODE_CALL -> "CALL_BUTTON"
            KeyEvent.KEYCODE_MEDIA_PLAY -> "MEDIA_PLAY"
            KeyEvent.KEYCODE_MEDIA_PAUSE -> "MEDIA_PAUSE"
            KeyEvent.KEYCODE_MEDIA_NEXT -> "MEDIA_NEXT"
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> "MEDIA_PREVIOUS"
            KeyEvent.KEYCODE_CAMERA -> "CAMERA_BUTTON"
            KeyEvent.KEYCODE_FOCUS -> "FOCUS_BUTTON"
            else -> KeyEvent.keyCodeToString(keyCode)
        }
        
        val eventInfo = "Button Event: $keyName"
        Log.d(TAG, eventInfo)
        
        runOnUiThread {
            // Show the event in the status text of the first camera slot
            statusLeft.visibility = View.VISIBLE
            statusLeft.text = eventInfo
            showOverlaysTemporarily()
            
            // If it's a volume event, update the seeker to match system state
            if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
                mainHandler.postDelayed({
                    seekSpeakerGain.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                }, 100)
            }
        }
        
        return super.onKeyDown(keyCode, event)
    }
    
    private fun playTestChime() {
        try {
            toneGenerator?.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
        } catch (e: Exception) {
            Log.e(TAG, "ToneGenerator error", e)
        }
    }

    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showOverlaysTemporarily() {
        labelLeft.visibility = View.VISIBLE
        labelRight.visibility = View.VISIBLE
        seekMicGain.visibility = View.VISIBLE
        seekSpeakerGain.visibility = View.VISIBLE
        
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun hideOverlays() {
        labelLeft.visibility = View.GONE
        labelRight.visibility = View.GONE
        seekMicGain.visibility = View.GONE
        seekSpeakerGain.visibility = View.GONE
        
        // Hide status if it's not the "Waiting" message
        if (!statusLeft.text.contains("Waiting")) {
            statusLeft.visibility = View.GONE
        }
    }
    
    private fun queuePermissionRequest(device: UsbDevice) {
        if (!pendingPermissionDevices.any { it.deviceId == device.deviceId } && !cameraMap.containsKey(device.deviceId)) {
            pendingPermissionDevices.add(device)
            processNextPermission()
        }
    }
    
    private fun processNextPermission() {
        if (isRequestingPermission || pendingPermissionDevices.isEmpty()) return
        
        val device = pendingPermissionDevices.removeAt(0)
        
        if (cameraClient.hasPermission(device) == true) {
            cameraClient.requestPermission(device)
        } else {
            isRequestingPermission = true
            runOnUiThread {
                val nextStatus = if (cameraMap.isEmpty()) statusLeft else statusRight
                nextStatus.text = "Requesting permission for\n${device.deviceName}..."
            }
            try {
                cameraClient.requestPermission(device)
            } catch (e: Exception) {
                Log.e(TAG, "Exception requesting permission", e)
                isRequestingPermission = false
                processNextPermission()
            }
        }
    }
    
    private fun shouldRotateDevice(device: UsbDevice): Boolean {
        // Automatically determine rotation based on device ID 
        // Note: Change deviceId depending on which is naturally upside down
        val invertedDeviceIds = listOf(4) 
        return device.deviceId in invertedDeviceIds || device.deviceName.endsWith("004")
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
            initCameras()
            startMeters()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                initCameras()
                startMeters()
            } else {
                Toast.makeText(this, "Permissions are required", Toast.LENGTH_LONG).show()
                initCameras() // Try anyway for cameras
            }
        }
    }

    private fun initCameras() {
        // Setup initial UI with device list after a short delay
        window.decorView.postDelayed({
            updateDeviceList()
        }, 1000)
    }

    private fun updateDeviceList() {
        val devices = cameraClient.getDeviceList()
        if (devices.isNullOrEmpty()) {
            Log.d(TAG, "No devices initially found")
            return
        }
        devices.take(2).forEach { queuePermissionRequest(it) }
    }
    
    private fun startMeters() {
        startMicMeter()
        startSpeakerMeter()
    }
    
    private fun stopMeters() {
        stopMicMeter()
        stopSpeakerMeter()
    }

    private fun startMicMeter() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        try {
            val minSize = AudioRecord.getMinBufferSize(8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            audioRecord = AudioRecord(MediaRecorder.AudioSource.MIC, 8000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minSize.coerceAtLeast(1024))
            audioRecord?.startRecording()
            isRecording = true

            Thread {
                while (isRecording) {
                    val read = audioRecord?.read(micBuffer, 0, micBuffer.size) ?: 0
                    if (read > 0) {
                        var sum = 0.0
                        for (i in 0 until read) {
                            sum += micBuffer[i] * micBuffer[i]
                        }
                        val rms = sqrt(sum / read)
                        // Apply mic gain (0.0 to 4.0) and a scale factor to fill 0-100 range
                        val level = (rms / 32767.0 * 100 * 4 * micGain).toInt()
                        runOnUiThread {
                            meterMic.progress = level.coerceIn(0, 100)
                        }
                    }
                    // ~30 FPS (1000ms / 30 = 33.3ms)
                    Thread.sleep(33)
                }
            }.start()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start mic meter", e)
        }
    }

    private fun stopMicMeter() {
        isRecording = false
        try {
            audioRecord?.stop()
        } catch (e: Exception) {}
        audioRecord?.release()
        audioRecord = null
    }

    private fun startSpeakerMeter() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return

        try {
            // Visualizer(0) attaches to the global output mix
            visualizer = Visualizer(0)
            visualizer?.enabled = false
            visualizer?.captureSize = Visualizer.getCaptureSizeRange()[1]
            visualizer?.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {
                    waveform?.let {
                        var sum = 0.0
                        for (byte in it) {
                            val amp = (byte.toInt() and 0xFF) - 128
                            sum += amp * amp
                        }
                        val rms = sqrt(sum / it.size)
                        // Scale level based on actual output signal
                        // speakerGain here acts as a scaling factor for the visualizer meter
                        val level = (rms / 128.0 * 100 * 3).toInt()
                        runOnUiThread {
                            meterSpeaker.progress = level.coerceIn(0, 100)
                        }
                    }
                }
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, 30000, true, false) // 30,000 mHz = 30 Hz (30 FPS)
            visualizer?.enabled = true
        } catch (e: Exception) {
            Log.e(TAG, "Visualizer error: check if another app is using it or if global mix is blocked", e)
        }
    }

    private fun stopSpeakerMeter() {
        try {
            visualizer?.enabled = false
            visualizer?.release()
            visualizer = null
        } catch (e: Exception) {}
    }

    override fun onStart() {
        super.onStart()
        cameraClient.register()
    }

    override fun onStop() {
        super.onStop()
        cameraMap.values.forEach { it.closeCamera() }
        cameraMap.clear()
        pendingPermissionDevices.clear()
        isRequestingPermission = false
        
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.removeCallbacks(autoZoomRunnable)
        stopMeters()

        statusLeft.visibility = View.VISIBLE
        statusLeft.text = "Waiting for camera…"
        statusRight.visibility = View.VISIBLE
        statusRight.text = "Waiting for camera…"
        cameraClient.unRegister()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraClient.isInitialized) {
            cameraClient.unRegister()
        }
        toneGenerator?.release()
    }

    private fun nextFreeSurface(): AspectRatioTextureView? = when (cameraMap.size) {
        0 -> textureLeft
        1 -> textureRight
        else -> null
    }
}
