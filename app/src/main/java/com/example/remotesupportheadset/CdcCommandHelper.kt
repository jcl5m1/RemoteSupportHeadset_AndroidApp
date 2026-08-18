package com.example.remotesupportheadset

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import android.util.Log
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.charset.Charset

/**
 * Helper that sends text commands to the ESP32-P4 over CDC-ACM and reads the
 * firmware's text responses. It can operate in two modes:
 *
 * 1. **Raw bulk-transfer mode** — used when the caller already owns the
 *    [UsbDeviceConnection] and the CDC bulk endpoints (as the app does through
 *    AndroidUSBCamera's USBMonitor). This avoids the interface-claiming
 *    conflicts that prevent usb-serial-for-android from opening the port while
 *    the UVC stack is active.
 *
 * 2. **usb-serial-for-android mode** — used as a fallback when no connection
 *    is supplied. This requires the CDC interfaces to be unclaimed.
 */
class CdcCommandHelper(
    context: Context,
    private val preferredDevice: UsbDevice? = null,
    private val preferredConnection: UsbDeviceConnection? = null,
    private val preferredOutEndpoint: UsbEndpoint? = null,
    private val preferredInEndpoint: UsbEndpoint? = null
) {

    companion object {
        private const val TAG = "CdcCommandHelper"
        private const val VID_ESP = 0x303A
        private const val PID_CDC_UVC = 0x4022
        private const val BAUD = 115200
        private const val BULK_TIMEOUT_MS = 500
    }

    private val usbManager: UsbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var driver: UsbSerialDriver? = null
    private var port: UsbSerialPort? = null
    private var ownedConnection: UsbDeviceConnection? = null

    private var rawConnection: UsbDeviceConnection? = null
    private var rawOutEndpoint: UsbEndpoint? = null
    private var rawInEndpoint: UsbEndpoint? = null

    val isOpen: Boolean
        get() = port != null || rawConnection != null

    /**
     * Try to open the CDC port. Returns true on success. Must be called after
     * the user has granted USB permission.
     */
    fun open(): Boolean {
        close()

        // Prefer the raw connection/endpoints supplied by the caller.
        val rawConn = preferredConnection
        val rawOut = preferredOutEndpoint
        val rawIn = preferredInEndpoint
        if (rawConn != null && rawOut != null && rawIn != null) {
            Log.d(TAG, "Using raw CDC bulk endpoints (out=${rawOut.address}, in=${rawIn.address})")
            rawConnection = rawConn
            rawOutEndpoint = rawOut
            rawInEndpoint = rawIn
            clearEndpointHalt(rawOut)
            clearEndpointHalt(rawIn)
            drainRawInput()
            return true
        }

        return openViaUsbSerial()
    }

    private fun openViaUsbSerial(): Boolean {
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
        ownedConnection = connection

        val selectedPort = selectedDriver.ports.firstOrNull()
        if (selectedPort == null) {
            connection.close()
            ownedConnection = null
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
            ownedConnection = null
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
        if (ownedConnection != null) {
            try {
                ownedConnection?.close()
            } catch (_: IOException) { }
            ownedConnection = null
        }
        rawConnection = null
        rawOutEndpoint = null
        rawInEndpoint = null
    }

    private fun clearEndpointHalt(endpoint: UsbEndpoint) {
        val conn = rawConnection ?: return
        try {
            val result = conn.controlTransfer(0x02, 1, 0, endpoint.address, null, 0, BULK_TIMEOUT_MS)
            Log.d(TAG, "Cleared endpoint halt 0x${endpoint.address.toString(16)}: result=$result")
        } catch (_: Exception) { }
    }

    private fun drainRawInput() {
        val conn = rawConnection ?: return
        val inEp = rawInEndpoint ?: return
        val buf = ByteArray(256)
        try {
            while (conn.bulkTransfer(inEp, buf, buf.size, 50) > 0) {
                // discard stale data
            }
        } catch (_: Exception) { }
    }

    /**
     * Send an exposure time in microseconds. The firmware converts it to the
     * nearest OV5647 register value. Returns the raw response line, if any.
     */
    fun setExposureUs(us: Int): String? {
        return sendCommand("exp_us $us")
    }

    /**
     * Ask the firmware to report its build version. Returns the raw response
     * line, e.g. "BUILD_VERSION 20260817_123045", or null on failure.
     */
    fun queryBuildVersion(): String? {
        return sendCommand("version")
    }

    /**
     * Ask the firmware to report the current exposure register value and its
     * duration in microseconds. Uses the `status` command because it reports
     * the live AE exposure (whether auto or manual).
     */
    fun queryExposureUs(): String? {
        return sendCommand("status")
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

    /**
     * Force the firmware anti-banding flicker frequency. [hz] must be 50 or 60.
     * Use [enableFlickerAutoDetection] to let the firmware auto-detect again.
     */
    fun setFlickerHz(hz: Int): String? {
        return sendCommand("flicker $hz")
    }

    /**
     * Let the firmware auto-detect 50/60 Hz flicker again.
     */
    fun enableFlickerAutoDetection(): String? {
        return sendCommand("flicker auto")
    }

    /**
     * Ask the firmware to report the current anti-banding flicker mode.
     */
    fun queryFlickerMode(): String? {
        return sendCommand("flicker")
    }

    fun sendCommand(cmd: String): String? {
        val rawConn = rawConnection
        val rawOut = rawOutEndpoint
        val rawIn = rawInEndpoint
        if (rawConn != null && rawOut != null && rawIn != null) {
            return sendRaw(rawConn, rawOut, rawIn, cmd)
        }

        val p = port ?: return null
        return try {
            drainInput(p)
            val out = "$cmd\r\n".toByteArray(Charset.forName("UTF-8"))
            p.write(out, 500)
            Thread.sleep(80)
            readResponse(p)
        } catch (e: IOException) {
            Log.w(TAG, "CDC write failed for '$cmd'", e)
            null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
    }

    private fun sendRaw(
        conn: UsbDeviceConnection,
        outEp: UsbEndpoint,
        inEp: UsbEndpoint,
        cmd: String
    ): String? {
        return try {
            drainRawInput()
            val out = "$cmd\r\n".toByteArray(Charset.forName("UTF-8"))
            val written = conn.bulkTransfer(outEp, out, out.size, BULK_TIMEOUT_MS)
            if (written < 0) {
                Log.w(TAG, "Raw CDC OUT bulkTransfer failed for '$cmd': $written")
                return null
            }
            Thread.sleep(80)
            readRawResponse(conn, inEp)
        } catch (e: Exception) {
            Log.w(TAG, "Raw CDC command failed for '$cmd'", e)
            null
        }
    }

    private fun readRawResponse(conn: UsbDeviceConnection, inEp: UsbEndpoint): String? {
        val buf = ByteArray(256)
        return try {
            val n = conn.bulkTransfer(inEp, buf, buf.size, BULK_TIMEOUT_MS)
            if (n > 0) {
                String(buf, 0, n, Charset.forName("UTF-8")).trim()
            } else {
                ""
            }
        } catch (e: Exception) {
            Log.w(TAG, "Raw CDC read failed", e)
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
