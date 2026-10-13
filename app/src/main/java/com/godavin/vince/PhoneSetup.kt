package com.godavin.vince

import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast

/**
 * v2.6 - guided phone-control setup that works on any phone, no laptop or adb.
 * Android deliberately lets only YOU lift the "restricted settings" lock on a
 * sideloaded app (an app that could unlock itself would defeat the whole point),
 * so this opens the right screens in order, tells you the exact taps, and ticks
 * each step as it detects it was done.
 */
object PhoneSetup {
    /** true = unlocked, false = still locked, null = this Android version can't tell. */
    fun restrictedUnlocked(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 33) return true
        return try {
            val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = ops.unsafeCheckOpNoThrow("android:access_restricted_settings", Process.myUid(), context.packageName)
            mode == AppOpsManager.MODE_ALLOWED
        } catch (e: Exception) {
            null
        }
    }

    fun accessibilityOn(context: Context) = VinceAccessibilityService.isEnabled(context)
    fun notificationOn(context: Context) = VinceNotificationListener.isEnabled(context)

    /** How many of the 3 steps are done (an undetectable lock step counts as done). */
    fun doneCount(context: Context): Int {
        var n = 0
        if (restrictedUnlocked(context) != false) n++
        if (accessibilityOn(context)) n++
        if (notificationOn(context)) n++
        return n
    }

    private fun toast(context: Context, msg: String) {
        try { Toast.makeText(context, msg, Toast.LENGTH_LONG).show() } catch (e: Exception) { }
    }

    private fun start(context: Context, i: Intent): Boolean = try {
        context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }

    fun openAppInfo(context: Context) {
        toast(context, "Tap the three dots (top right), then \"Allow restricted settings\". No dots? Open Accessibility first and tap VINCE once.")
        start(context, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
    }

    fun openAccessibility(context: Context) {
        toast(context, "Find VINCE phone control and turn it ON.")
        if (Build.VERSION.SDK_INT >= 34) {
            // straight to VINCE's own accessibility page
            val svc = ComponentName(context, VinceAccessibilityService::class.java).flattenToString()
            val direct = Intent("android.settings.ACCESSIBILITY_DETAIL_SETTINGS")
                .putExtra(Intent.EXTRA_COMPONENT_NAME, svc)
            if (start(context, direct)) return
        }
        start(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openNotificationAccess(context: Context) {
        toast(context, "Turn VINCE ON in the list.")
        start(context, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
    }

    /** Opens whichever setup screen is the next one still undone. */
    fun openNextStep(context: Context) {
        when {
            restrictedUnlocked(context) == false -> openAppInfo(context)
            !accessibilityOn(context) -> openAccessibility(context)
            !notificationOn(context) -> openNotificationAccess(context)
            else -> toast(context, "Phone control is fully set up.")
        }
    }
}
