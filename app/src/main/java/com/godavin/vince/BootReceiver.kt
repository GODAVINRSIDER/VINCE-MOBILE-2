package com.godavin.vince

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

/**
 * Widget didn't survive a phone reboot before this - it would just stay
 * off until the user manually reopened the app and flipped it back on.
 * This restarts it automatically on boot, but only if it was actually
 * on before AND the "display over other apps" permission is still
 * granted (Android can't guarantee that survives every OS update, so
 * this checks rather than assumes).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!WidgetState.isEnabled(context)) return
        if (!canDrawOverlays(context)) return

        val serviceIntent = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, serviceIntent)
    }
}
