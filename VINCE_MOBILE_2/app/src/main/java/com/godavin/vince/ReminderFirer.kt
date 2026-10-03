package com.godavin.vince

import android.content.Context

/**
 * v2.3 - the ONE place a reminder is delivered. Two independent paths call it:
 * the AlarmManager alarm (ReminderReceiver) and the background guard service
 * (ReminderGuardService). Whichever gets there first delivers it; the store's
 * atomic take() makes sure the other path then does nothing, so there is never
 * a double notification.
 */
object ReminderFirer {
    fun fire(context: Context, id: Int, fallbackMessage: String, via: String) {
        val app = context.applicationContext
        val taken: Reminder? = if (id != -1) ReminderStore.take(app, id) else null
        // id == -1 (should not happen) or a reminder missing from the store while an
        // alarm still fired: deliver anyway rather than silently drop it.
        if (id != -1 && taken == null) return

        val message = taken?.message ?: fallbackMessage
        Notifs.show(app, Notifs.CH_REMINDER, if (id != -1) id else (System.currentTimeMillis() % 100000).toInt(), "VINCE reminder", message)

        try {
            val due = taken?.fireAtMillis ?: System.currentTimeMillis()
            AlertLog.record(app, "reminder_$via", due, System.currentTimeMillis())
        } catch (e: Exception) { /* logging only */ }

        try { ReminderGuardService.sync(app) } catch (e: Exception) { /* best-effort */ }
    }
}
