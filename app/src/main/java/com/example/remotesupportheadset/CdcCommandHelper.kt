package com.example.remotesupportheadset

import android.content.Context
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.charset.Charset

/**
 * Opens the CDC-ACM serial port on the ESP32-P4 composite device and sends
 * runtime exposure commands for the anti-banding servo.
 *
 * This is separate from the UVC camera stack. The UVC library claims the video
 * interface; this helper tries to claim only the CDC-ACM interface on the same
 * device. If the UVC stack already has an exclusive device connection, the
 * helper will fail to open and commands will be skipped.
 */
class CdcCommandHelper(context: Context) {

    companion object {
        private const val TAG = "CdcCommandHelper"
        private const val VID_ESP = 0x303A
        private const val PID_CDC_UVC = 0x4022
        private const val BAUD = 115200
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var driver: UsbSerialDriver? = null
    private var port: UsbSerialPort? = null

    val isOpen: Boolean
        get() = port != null

    /**
     * Try to open the first CDC port on the ESP32 device. Returns true on
     * success. Must be called after the user has granted USB permission.
     */
    fun open(): Boolean {
        close()

        val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        driver = availableDrivers.firstOrNull {
            it.device.vendorId == VID_ESP && it.device.productId == PID_CDC_UVC
        }

        val selectedDriver = driver
        if (selectedDriver == null) {
            Log.w(TAG, "No ESP32 CDC-UVC device found (vid=0x${VID_ESP.toString(16)}, pid=0x${PID_CDC_UVC.toString(16)})")
            return false
        }

        val device = selectedDriver.device
        if (!usbManager.hasPermission(device)) {
            Log.w(TAG, "No USB permission for ${device.deviceName}")
            return false
        }

        val connection = usbManager.openDevice(device)
        if (connection == null) {
            Log.w(TAG, "UsbManager.openDevice() returned null; another driver may already own the device")
            return false
        }

        val selectedPort = selectedDriver.ports.firstOrNull()
        if (selectedPort == null) {
            connection.close()
            Log.w(TAG, "Driver reported no serial ports")
            return false
        }

        return try {
            selectedPort.open(connection)
            selectedPort.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            port = selectedPort
            Log.i(TAG, "CDC port open on ${device.deviceName}")
            true
        } catch (e: IOException) {
            Log.w(TAG, "Failed to open CDC port", e)
            try {
                selectedPort.close()
            } catch (_: IOException) { }
            connection.close()
            false
        }
    }

    fun close() {
        val p = port
        port = null
        if (p != null) {
            try {
                p.close()
            } catch (_: IOException) { }
        }
    }

    /**
     * Send an exposure time in microseconds. The firmware converts it to the
     * nearest OV5647 register value. Returns the raw response line, if any.
     */
    fun setExposureUs(us: Int): String? {
        return sendCommand("exp_us $us")
    }

    /**
     * Ask the firmware to report the current exposure register value and its
     * duration in microseconds.
     *
     * We use the `status` command because it reports the live AE exposure
     * (whether auto or manual).  The bare `exp_us` command only returns the
     * last manually-set exposure value.  We also drain stale CDC input first
     * so we don't pick up the response from a previous command.
     */
    fun queryExposureUs(): String? {
        val p = port ?: return null
        return try {
            drainInput(p)
            val out = "status\r\n".toByteArray(Charset.forName("UTF-8"))
            p.write(out, 500)
            Thread.sleep(120)
            readResponse(p)
        } catch (e: IOException) {
            Log.w(TAG, "CDC status query failed", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun drainInput(port: UsbSerialPort) {
        val buf = ByteArray(256)
        try {
            while (port.read(buf, 50) > 0) {
                // discard stale data
            }
        } catch (_: IOException) { }
    }

    /**
     * Put the firmware in manual exposure mode and disable the AE loop so the
     * Android servo has full control.
     */
    fun disableAutoExposure(): String? {
        return sendCommand("manual")
    }

    /**
     * Re-enable the firmware AE loop.
     */
    fun enableAutoExposure(): String? {
        return sendCommand("auto")
    }

    fun sendCommand(cmd: String): String? {
        val p = port ?: return null
        return try {
            val out = "$cmd\r\n".toByteArray(Charset.forName("UTF-8"))
            p.write(out, 500)
            // Give the firmware a moment to echo/answer.
            Thread.sleep(60)
            readResponse(p)
        } catch (e: IOException) {
            Log.w(TAG, "CDC write failed for '$cmd'", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun readResponse(port: UsbSerialPort): String? {
        val buf = ByteArray(256)
        return try {
            val n = port.read(buf, 500)
            if (n > 0) {
                String(buf, 0, n, Charset.forName("UTF-8")).trim()
            } else {
                ""
            }
        } catch (e: IOException) {
            Log.w(TAG, "CDC read failed", e)
            null
        }
    }
}
