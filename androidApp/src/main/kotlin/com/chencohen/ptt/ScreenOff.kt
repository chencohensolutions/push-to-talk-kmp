package com.chencohen.ptt

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Settings › Talk › Turn the screen off right after talk (2026-11-23, hen; off by default): Android lets an app turn
 * the screen off only as a device admin with the force-lock policy (the only one Talk asks for). With it, a burst that
 * woke the phone locks it the moment it ends, if nobody touched the screen; without it the lock screen's own short
 * timeout turns it off. To uninstall Talk, this is turned off first (here or in Android's device admin apps).
 */
object ScreenOff {
    fun admin(context: Context) = ComponentName(context, ScreenOffAdmin::class.java)

    fun on(context: Context): Boolean =
        context.getSystemService(DevicePolicyManager::class.java).isAdminActive(admin(context))

    /** Android's request for the force-lock policy. */
    fun request(context: Context, explanation: String): Intent =
        Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(context))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation)

    fun off(context: Context) {
        runCatching { context.getSystemService(DevicePolicyManager::class.java).removeActiveAdmin(admin(context)) }
    }

    /** Locks the phone now (the screen goes off) when allowed; false when not. */
    fun lockNow(context: Context): Boolean {
        if (!on(context)) return false
        return runCatching { context.getSystemService(DevicePolicyManager::class.java).lockNow() }
            .onFailure { Log.w("ScreenOff", "lockNow", it) }.isSuccess
    }
}

/** The device admin receiver for [ScreenOff] (force-lock only). */
class ScreenOffAdmin : DeviceAdminReceiver()
