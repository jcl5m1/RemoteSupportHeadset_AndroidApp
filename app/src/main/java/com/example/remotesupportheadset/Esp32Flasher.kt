package com.example.remotesupportheadset

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.util.Log

/**
 * JNI wrapper around esp-serial-flasher for flashing an ESP32-P4 target
 * over Android USB Host CDC ACM.
 *
 * The native layer calls back into [usbBulkTransfer] to perform bulk IN/OUT
 * transfers on the download-mode USB device, and into [onFlashProgress] to
 * report how many bytes of the current image have been written.
 */
class Esp32Flasher(
    private val connection: UsbDeviceConnection,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint
) {
    companion object {
        private const val TAG = "Esp32Flasher"

        init {
            System.loadLibrary("esp32flasher")
        }
    }

    private var progressCallback: ((transferred: Long, total: Long) -> Unit)? = null

    /**
     * Called from native code. [endpoint] is 0 for IN, 1 for OUT.
     * Returns the number of bytes transferred, or a negative value on error.
     */
    @Suppress("unused")
    private fun usbBulkTransfer(endpoint: Int, buffer: ByteArray, length: Int, timeout: Int): Int {
        val ep = if (endpoint == 0) inEndpoint else outEndpoint
        return try {
            connection.bulkTransfer(ep, buffer, length, timeout)
        } catch (e: Exception) {
            Log.e(TAG, "bulkTransfer failed", e)
            -1
        }
    }

    /**
     * Called from native code after each flash block is acknowledged.
     */
    @Suppress("unused")
    private fun onFlashProgress(transferred: Long, total: Long) {
        progressCallback?.invoke(transferred, total)
    }

    /**
     * Flash one binary image to [offset] on the target.
     * @return 0 on success, non-zero on failure.
     */
    external fun nativeFlash(
        fd: Int,
        inEpAddr: Int,
        outEpAddr: Int,
        maxPkt: Int,
        offset: Int,
        image: ByteArray
    ): Int

    /**
     * Convenience wrapper that flashes [image] to [offset].
     * [onProgress] is invoked on the thread performing the flash.
     */
    fun flashImage(
        offset: Int,
        image: ByteArray,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null
    ): Boolean {
        progressCallback = onProgress
        return try {
            val result = nativeFlash(
                connection.fileDescriptor,
                inEndpoint.address,
                outEndpoint.address,
                inEndpoint.maxPacketSize,
                offset,
                image
            )
            result == 0
        } finally {
            progressCallback = null
        }
    }
}
