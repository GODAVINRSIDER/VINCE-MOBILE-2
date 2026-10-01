package com.godavin.vince

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * After a phone reboot: (1) re-arm every alert and saved reminder - alarms
 * do not survive a reboot on their own - and (2) restart the floating
 * widget if it was on before and the "display over other apps" permission
 * is still granted.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        try {
            AlertScheduler.rescheduleAll(context)
        } catch (e: Exception) {
            // never let alert re-arming block the widget restart below
        }

        if (!WidgetState.isEnabled(context)) return
        if (!canDrawOverlays(context)) return

        val serviceIntent = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
