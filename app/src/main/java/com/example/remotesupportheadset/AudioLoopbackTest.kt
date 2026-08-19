package com.example.remotesupportheadset

import android.app.Activity
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.math.PI
import kotlin.math.sin

/**
 * In-app audio loopback harness for hardware qualification.
 *
 * The ESP32-P4 is connected to the Android phone over USB-OTG and exposes a UAC2
 * speaker + microphone. This class lets the phone:
 *
 *   - play a generated pure tone through the ESP32 speaker, and
 *   - record from the ESP32 microphone to a WAV file.
 *
 * A host script on a MacBook supplies the reference speaker/microphone, sends
 * the start/stop commands via ADB, pulls the recordings, and analyses them.
 */
class AudioLoopbackTest(
    private val activity: Activity,
    private val cdcCommandHelper: CdcCommandHelper
) {

    companion object {
        private const val TAG = "AudioLoopbackTest"
        private const val SAMPLE_RATE = 48000
        private const val CHANNEL_OUT = AudioFormat.CHANNEL_OUT_MONO
        private const val CHANNEL_IN = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }

    @Volatile
    private var running = false

    /**
     * Find the ESP32-P4 USB audio device among the currently attached audio devices.
     * On Android 10+ the VID/PID are available; on older releases we match the product name.
     */
    fun findUsbAudioDevice(isInput: Boolean): AudioDeviceInfo? {
        val audioManager = activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val flags = if (isInput) AudioManager.GET_DEVICES_INPUTS else AudioManager.GET_DEVICES_OUTPUTS
        val candidates = audioManager.getDevices(flags)
        for (device in candidates) {
            val type = device.type
            if (type != AudioDeviceInfo.TYPE_USB_HEADSET && type != AudioDeviceInfo.TYPE_USB_DEVICE) {
                continue
            }
            if (device.productName?.contains("ESP32", ignoreCase = true) == true) {
                return device
            }
        }
        return null
    }

    /**
     * Send `spkvol <percent>` to the firmware. The firmware maps this directly to
     * the ES8311 codec volume and ignores subsequent UAC2 volume requests while
     * the lock is held.
     */
    fun setSpeakerVolume(percent: Int): String? {
        return cdcCommandHelper.setSpeakerVolume(percent)
    }

    /**
     * Restore the default safe volume and unlock UAC2 volume control.
     */
    fun resetSpeakerVolume(): String? {
        return cdcCommandHelper.resetSpeakerVolume()
    }

    /**
     * Generate a mono 16-bit sine tone.
     */
    fun generateTone(frequency: Double, durationSec: Double, amplitude: Double = 0.5): ShortArray {
        val amplitudeClamped = amplitude.coerceIn(0.0, 1.0)
        val sampleCount = (durationSec * SAMPLE_RATE).toInt()
        return ShortArray(sampleCount) { i ->
            val sample = amplitudeClamped * sin(2.0 * PI * frequency * i / SAMPLE_RATE)
            (sample * Short.MAX_VALUE).toInt().toShort()
        }
    }

    /**
     * Write a PCM byte stream (little-endian 16-bit mono) to a WAV file.
     */
    @Throws(IOException::class)
    fun writeWavFile(pcmBytes: ByteArray, wavFile: File, sampleRate: Int, channels: Int, bits: Int) {
        val byteRate = sampleRate * channels * bits / 8
        val blockAlign = channels * bits / 8
        val dataSize = pcmBytes.size
        val totalSize = 36 + dataSize

        FileOutputStream(wavFile).use { out ->
            out.write("RIFF".toByteArray())
            out.writeIntLe(totalSize)
            out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray())
            out.writeIntLe(16)                 // subchunk size
            out.writeShortLe(1)                // audio format = PCM
            out.writeShortLe(channels)
            out.writeIntLe(sampleRate)
            out.writeIntLe(byteRate)
            out.writeShortLe(blockAlign)
            out.writeShortLe(bits)
            out.write("data".toByteArray())
            out.writeIntLe(dataSize)
            out.write(pcmBytes)
        }
    }

    private fun FileOutputStream.writeIntLe(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
        write(value shr 16 and 0xFF)
        write(value shr 24 and 0xFF)
    }

    private fun FileOutputStream.writeShortLe(value: Int) {
        write(value and 0xFF)
        write(value shr 8 and 0xFF)
    }

    /**
     * Record from [device] (or the default input if null) for [durationSec] seconds
     * and save the result as a mono WAV file at [outputFile].
     *
     * The recording runs on a background thread. [onComplete] is called on the
     * caller's thread after the file is written.
     */
    fun recordFromDevice(
        device: AudioDeviceInfo?,
        durationSec: Double,
        outputFile: File,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (running) {
            onComplete(false, "another audio operation is already running")
            return
        }
        running = true

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var success = false
            var error: String? = null
            try {
                val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_IN, ENCODING)
                // USB audio needs a generous buffer to survive scheduling jitter on the
                // host.  Use at least 4x the minimum and never less than ~170 ms.
                val bufferSize = (minBuf * 4).coerceAtLeast(16384)

                val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioRecord.Builder()
                        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(ENCODING)
                                .setChannelMask(CHANNEL_IN)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioRecord(
                        MediaRecorder.AudioSource.DEFAULT,
                        SAMPLE_RATE,
                        CHANNEL_IN,
                        ENCODING,
                        bufferSize
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device != null) {
                    recorder.preferredDevice = device
                }

                val pcmFile = File(outputFile.parent, "${outputFile.name}.raw")
                recorder.startRecording()
                Log.i(TAG, "Recording started, device=${device?.productName ?: "default"}, bufSize=$bufferSize")

                FileOutputStream(pcmFile).use { out ->
                    val buffer = ShortArray(1024)
                    val endTime = SystemClock.elapsedRealtime() + (durationSec * 1000).toLong()
                    while (SystemClock.elapsedRealtime() < endTime) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            for (i in 0 until read) {
                                val s = buffer[i].toInt()
                                out.write(s and 0xFF)
                                out.write(s shr 8 and 0xFF)
                            }
                        }
                    }
                }

                recorder.stop()
                recorder.release()

                writeWavFile(pcmFile.readBytes(), outputFile, SAMPLE_RATE, 1, 16)
                pcmFile.delete()
                success = true
                Log.i(TAG, "Recording saved to ${outputFile.absolutePath}")
            } catch (e: Exception) {
                Log.e(TAG, "Recording failed", e)
                error = e.message
            } finally {
                running = false
                activity.runOnUiThread { onComplete(success, error) }
            }
        }, "AudioLoopbackRecord").start()
    }

    /**
     * Play a generated tone through [device] (or the default output if null) for
     * [durationSec] seconds. [onComplete] is called on the caller's thread after
     * playback finishes.
     */
    fun playToneToDevice(
        device: AudioDeviceInfo?,
        frequency: Double,
        durationSec: Double,
        amplitude: Double = 0.5,
        onComplete: (Boolean, String?) -> Unit
    ) {
        if (running) {
            onComplete(false, "another audio operation is already running")
            return
        }
        running = true

        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            var success = false
            var error: String? = null
            try {
                val samples = generateTone(frequency, durationSec, amplitude)
                val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, CHANNEL_OUT, ENCODING)
                // Keep the track buffer small so AudioTrack.write() blocks in real time.
                // A buffer sized for the full tone would let write() return immediately
                // and the tone would be cut off when we call stop().
                val bufferSize = (minBuf * 2).coerceAtLeast(4096).coerceAtMost(32768)

                val track = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    AudioTrack.Builder()
                        .setAudioAttributes(
                            AudioAttributes.Builder()
                                .setUsage(AudioAttributes.USAGE_MEDIA)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                                .build()
                        )
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setSampleRate(SAMPLE_RATE)
                                .setEncoding(ENCODING)
                                .setChannelMask(CHANNEL_OUT)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferSize)
                        .build()
                } else {
                    @Suppress("DEPRECATION")
                    AudioTrack(
                        AudioManager.STREAM_MUSIC,
                        SAMPLE_RATE,
                        CHANNEL_OUT,
                        ENCODING,
                        bufferSize,
                        AudioTrack.MODE_STREAM
                    )
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && device != null) {
                    track.preferredDevice = device
                }

                Log.i(TAG, "Playback started, device=${device?.productName ?: "default"}")
                track.play()

                // Write in chunks so the UI thread is not blocked.
                val chunkSize = 1024
                var offset = 0
                while (offset < samples.size) {
                    val end = (offset + chunkSize).coerceAtMost(samples.size)
                    track.write(samples, offset, end - offset)
                    offset = end
                }

                // Let the last samples drain.
                Thread.sleep(50)
                track.stop()
                track.release()
                success = true
                Log.i(TAG, "Playback finished")
            } catch (e: Exception) {
                Log.e(TAG, "Playback failed", e)
                error = e.message
            } finally {
                running = false
                activity.runOnUiThread { onComplete(success, error) }
            }
        }, "AudioLoopbackPlay").start()
    }

    /**
     * Stop any currently running operation. Currently this only unblocks the
     * polling loops by letting the timeout expire; callers normally use the
     * explicit duration parameters instead.
     */
    fun stop() {
        // The record/playback threads self-terminate after their fixed duration.
        Log.d(TAG, "stop() called")
    }
}
