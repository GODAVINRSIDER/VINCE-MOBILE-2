package com.godavin.vince

import android.content.Context

/**
 * Persisted "should the floating widget be running" flag - separate
 * from whether OverlayService happens to be alive right now (that dies
 * on every reboot/force-close). This is what lets BootReceiver.kt know
 * whether to restart the widget after a reboot, instead of it just
 * staying off until the user manually reopens the app and flips it back
 * on every single time.
 */
object WidgetState {
    private const val PREFS = "vince_widget_prefs"
    private const val KEY_ENABLED = "widget_enabled"

    fun isEnabled(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }
}
