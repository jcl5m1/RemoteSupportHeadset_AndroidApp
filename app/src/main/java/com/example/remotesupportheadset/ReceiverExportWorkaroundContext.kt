package com.example.remotesupportheadset

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.Manifest
import android.os.Build
import android.os.Bundle

/**
 * Wraps a Context so that two-argument [registerReceiver] calls made by older
 * libraries (such as AndroidUSBCamera 3.2.7's USBMonitor) are automatically
 * supplied with [RECEIVER_NOT_EXPORTED] on Android 14+.
 *
 * Without this, the library crashes on launch with:
 *   SecurityException: One of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED
 *   should be specified when a receiver isn't being registered exclusively
 *   for system broadcasts
 */
class ReceiverExportWorkaroundContext(base: Context) : ContextWrapper(base) {

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun registerReceiver(receiver: BroadcastReceiver?, filter: IntentFilter?): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(receiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter)
        }
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        flags: Int
    ): Intent? {
        return super.registerReceiver(receiver, filter, flags)
    }

    override fun registerReceiver(
        receiver: BroadcastReceiver?,
        filter: IntentFilter?,
        broadcastPermission: String?,
        scheduler: android.os.Handler?
    ): Intent? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            registerReceiver(receiver, filter, broadcastPermission, scheduler, RECEIVER_NOT_EXPORTED)
        } else {
            super.registerReceiver(receiver, filter, broadcastPermission, scheduler)
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
}
