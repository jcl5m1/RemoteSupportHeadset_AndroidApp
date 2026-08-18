package com.example.remotesupportheadset

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import com.jiangdg.ausbc.widget.AspectRatioSurfaceView
import java.io.File
import kotlin.math.min

/**
 * Reads a directory of JPEG files and plays them back as a synthetic camera feed.
 *
 * Frames are decoded, rendered to the supplied [AspectRatioSurfaceView], converted
 * to NV21, and delivered to a [FrameConsumer] on a dedicated background thread.
 * This lets the app exercise AprilTag and YOLO pipelines without an attached UVC
 * camera.
 */
class VideoFrameSource(
    private val activity: Activity,
    private val surfaceView: AspectRatioSurfaceView,
    private val frameDir: File,
    private val frameConsumer: FrameConsumer
) {
    interface FrameConsumer {
        /**
         * Called on the source thread for each decoded frame.
         *
         * The [bitmap] is owned by the receiver and must be recycled when no longer
         * needed. The [nv21] array is freshly allocated for this frame.
         */
        fun onFrame(bitmap: Bitmap, nv21: ByteArray, width: Int, height: Int)
    }

    companion object {
        private const val TAG = "VideoFrameSource"
        private const val TARGET_FPS = 15
        private const val FRAME_INTERVAL_MS = 1000L / TARGET_FPS

        /**
         * Convert an ARGB_8888 [bitmap] to an NV21 byte array.
         *
         * The conversion uses the BT.601 coefficients and outputs video-range Y
         * values so that the app's existing [nv21ToBitmap] path stays consistent.
         */
        fun bitmapToNv21(bitmap: Bitmap): ByteArray {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val ySize = width * height
            val nv21 = ByteArray(ySize + ySize / 2)

            // Y plane
            var yIndex = 0
            for (j in 0 until height) {
                for (i in 0 until width) {
                    val px = pixels[j * width + i]
                    val r = (px shr 16) and 0xFF
                    val g = (px shr 8) and 0xFF
                    val b = px and 0xFF
                    val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
                    nv21[yIndex++] = y.coerceIn(16, 235).toByte()
                }
            }

            // VU plane (NV21 ordering: V then U for each 2x2 block)
            var uvIndex = ySize
            for (j in 0 until height step 2) {
                for (i in 0 until width step 2) {
                    var rSum = 0
                    var gSum = 0
                    var bSum = 0
                    for (dj in 0..1) {
                        for (di in 0..1) {
                            val yIdx = min(j + dj, height - 1) * width + min(i + di, width - 1)
                            val px = pixels[yIdx]
                            rSum += (px shr 16) and 0xFF
                            gSum += (px shr 8) and 0xFF
                            bSum += px and 0xFF
                        }
                    }
                    val r = rSum shr 2
                    val g = gSum shr 2
                    val b = bSum shr 2
                    val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
                    val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128
                    nv21[uvIndex++] = v.coerceIn(0, 255).toByte()
                    nv21[uvIndex++] = u.coerceIn(0, 255).toByte()
                }
            }

            return nv21
        }
    }

    private val frames: List<File> = frameDir.listFiles { file ->
        file.isFile && (
            file.extension.equals("jpg", ignoreCase = true) ||
                file.extension.equals("jpeg", ignoreCase = true)
            )
    }?.sortedBy { it.name } ?: emptyList()

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var frameIndex = 0
    @Volatile
    private var running = false

    private val frameRunnable = object : Runnable {
        override fun run() {
            if (!running || activity.isFinishing) {
                return
            }

            val frameFile = frames.getOrNull(frameIndex) ?: return
            val loopStart = SystemClock.elapsedRealtime()

            val bitmap = decodeFrame(frameFile)
            if (bitmap == null) {
                frameIndex = (frameIndex + 1) % frames.size
                scheduleNext(loopStart)
                return
            }

            renderBitmap(bitmap)

            val nv21 = bitmapToNv21(bitmap)
            frameConsumer.onFrame(bitmap, nv21, bitmap.width, bitmap.height)

            frameIndex = (frameIndex + 1) % frames.size
            scheduleNext(loopStart)
        }

        private fun scheduleNext(loopStartTime: Long) {
            if (!running) return
            val elapsed = SystemClock.elapsedRealtime() - loopStartTime
            val delay = maxOf(0L, FRAME_INTERVAL_MS - elapsed)
            handler?.postDelayed(this, delay)
        }
    }

    init {
        Log.d(TAG, "Found ${frames.size} frames in ${frameDir.absolutePath}")
    }

    fun start() {
        if (running) return
        if (frames.isEmpty()) {
            Log.e(TAG, "No frames to play in ${frameDir.absolutePath}")
            return
        }
        running = true
        val t = HandlerThread("VideoFrameSource").apply { start() }
        thread = t
        handler = Handler(t.looper)
        handler?.post(frameRunnable)
        Log.d(TAG, "Started video frame source with ${frames.size} frames")
    }

    fun stop() {
        running = false
        handler?.removeCallbacks(frameRunnable)
        thread?.quitSafely()
        thread = null
        handler = null
        Log.d(TAG, "Stopped video frame source")
    }

    fun release() {
        stop()
    }

    val isRunning: Boolean
        get() = running

    private fun decodeFrame(file: File): Bitmap? {
        return try {
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                if (bitmap.config != Bitmap.Config.ARGB_8888) {
                    val converted = bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    bitmap.recycle()
                    converted
                } else {
                    bitmap
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode ${file.absolutePath}", e)
            null
        }
    }

    private fun renderBitmap(bitmap: Bitmap) {
        val holder = surfaceView.holder
        var canvas: Canvas? = null
        try {
            canvas = holder.lockCanvas()
            if (canvas != null) {
                canvas.drawBitmap(bitmap, null, Rect(0, 0, canvas.width, canvas.height), null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to render frame", e)
        } finally {
            try {
                canvas?.let { holder.unlockCanvasAndPost(it) }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unlock canvas", e)
            }
        }
    }
}
