package com.example.remotesupportheadset

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.Visualizer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

class DualCameraActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "DualCameraActivity"
        private const val PREVIEW_WIDTH = 640
        private const val PREVIEW_HEIGHT = 480
        private const val REQUEST_CAMERA_PERMISSION = 1001
        private const val OVERLAY_TIMEOUT_MS = 5000L
    }

    private lateinit var textureLeft: AspectRatioTextureView
    private lateinit var textureRight: AspectRatioTextureView
    private lateinit var statusLeft: TextView
    private lateinit var statusRight: TextView
    private lateinit var labelLeft: TextView
    private lateinit var labelRight: TextView
    private lateinit var meterMic: ProgressBar
    private lateinit var meterSpeaker: ProgressBar

    private val cameraMap = LinkedHashMap<Int, MultiCameraClient.Camera>(2)
    private lateinit var cameraClient: MultiCameraClient

    private val pendingPermissionDevices = mutableListOf<UsbDevice>()
    private var isRequestingPermission = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private val hideOverlayRunnable = Runnable { hideOverlays() }

    // Audio meters
    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    private val micBuffer = ShortArray(1024)
    private var visualizer: Visualizer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_dual_camera)

        hideSystemUI()

        textureLeft  = findViewById(R.id.texture_camera_left)
        textureRight = findViewById(R.id.texture_camera_right)
        statusLeft   = findViewById(R.id.status_camera_left)
        statusRight  = findViewById(R.id.status_camera_right)
        labelLeft    = findViewById(R.id.label_camera_left)
        labelRight   = findViewById(R.id.label_camera_right)
        meterMic     = findViewById(R.id.meter_mic)
        meterSpeaker = findViewById(R.id.meter_speaker)

        // Initially show labels, they will hide automatically after 5s
        showOverlaysTemporarily()

        // Set up tap listener to show overlay
        val clickListener = View.OnClickListener { showOverlaysTemporarily() }
        textureLeft.setOnClickListener(clickListener)
        textureRight.setOnClickListener(clickListener)
        findViewById<View>(android.R.id.content).setOnClickListener(clickListener)

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
                    } else {
                        statusRight.visibility = View.GONE
                        labelRight.text = "Camera 2\n$PREVIEW_WIDTH x $PREVIEW_HEIGHT @ 30 FPS"
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
                    val nextStatus = if (cameraMap.size == 0) statusLeft else statusRight
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
                }
            }

            override fun onDisConnectDec(device: UsbDevice?, ctrlBlock: USBMonitor.UsbControlBlock?) {
                Log.d(TAG, "Camera disconnected: ${device?.deviceName}")
            }
        })
        
        checkAndRequestPermissions()
    }
    
    private fun hideSystemUI() {
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun showOverlaysTemporarily() {
        labelLeft.visibility = View.VISIBLE
        labelRight.visibility = View.VISIBLE
        
        mainHandler.removeCallbacks(hideOverlayRunnable)
        mainHandler.postDelayed(hideOverlayRunnable, OVERLAY_TIMEOUT_MS)
    }

    private fun hideOverlays() {
        labelLeft.visibility = View.GONE
        labelRight.visibility = View.GONE
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
                val nextStatus = if (cameraMap.size == 0) statusLeft else statusRight
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
                        val rms = Math.sqrt(sum / read)
                        val level = (rms / 32767.0 * 100).toInt() * 4 // Scale up factor
                        runOnUiThread {
                            meterMic.progress = level.coerceIn(0, 100)
                        }
                    }
                    Thread.sleep(50)
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
                            val amp = byte.toInt() - 128
                            sum += amp * amp
                        }
                        val rms = Math.sqrt(sum / it.size)
                        val level = (rms / 128.0 * 100).toInt() * 3 // Scale up factor
                        runOnUiThread {
                            meterSpeaker.progress = level.coerceIn(0, 100)
                        }
                    }
                }
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {}
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
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
    }

    private fun nextFreeSurface(): AspectRatioTextureView? = when (cameraMap.size) {
        0 -> textureLeft
        1 -> textureRight
        else -> null
    }
}
