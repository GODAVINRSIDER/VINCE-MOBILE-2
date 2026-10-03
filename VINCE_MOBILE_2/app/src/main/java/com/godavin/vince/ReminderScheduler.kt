package com.godavin.vince

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Schedules a real OS-level alarm for each reminder, so it fires even if
 * VINCE isn't running at the time - not an in-app timer that dies with
 * the process.
 *
 * Now uses setExactAndAllowWhileIdle rather than the previous
 * setAndAllowWhileIdle: the inexact version was the real cause of
 * reminders firing a few minutes late - Android is explicitly allowed to
 * batch/delay inexact alarms (more so in Doze), and personal reminders
 * ("remind me in 2 minutes", "remind me at 4:30") need to land on time to
 * actually be useful. This does require the Android 12+ "exact alarm"
 * permission - handled below by sending the user to the one-time system
 * toggle if it isn't granted yet, same discipline as every other
 * permission in this app (never silently fail, never crash either).
 */
object ReminderScheduler {
    private const val EXTRA_ID = "reminder_id"
    private const val EXTRA_MESSAGE = "reminder_message"

    internal fun pendingIntentFor(context: Context, id: Int, message: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_MESSAGE, message)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /**
     * Returns true if the alarm was actually scheduled. Returns false if
     * Android 12+ hasn't granted exact-alarm permission yet - in that case
     * this redirects the user to the system settings screen to grant it;
     * the caller should tell the user to try the reminder again after
     * granting it.
     */
    fun schedule(context: Context, id: Int, fireAtMillis: Long, message: String): Boolean {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            val settingsIntent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(settingsIntent)
            return false
        }

        // Fix - armed through AlertScheduler.setReliableAlarm (alarm-clock
        // alarm, exempt from Doze batching) instead of a plain exact alarm,
        // which is the weakest type on aggressive OEM battery managers.
        AlertScheduler.setReliableAlarm(context, fireAtMillis, pendingIntentFor(context, id, message))
        // Second delivery path + keep-alive, so the reminder does not depend on the alarm alone.
        ReminderGuardService.sync(context)
        return true
    }

    /**
     * Re-arms every saved reminder (app open / phone reboot). A reminder
     * whose time passed while the phone was off or the app was killed is
     * shown immediately as a "missed reminder" instead of silently lost.
     * Never redirects to a settings screen - this runs in the background.
     */
    fun rearmAll(context: Context) {
        val now = System.currentTimeMillis()
        for (r in ReminderStore.getAll(context)) {
            if (r.fireAtMillis > now + 1000) {
                AlertScheduler.setReliableAlarm(context, r.fireAtMillis, pendingIntentFor(context, r.id, r.message))
            } else {
                if (ReminderStore.take(context, r.id) != null) {
                    Notifs.show(context, Notifs.CH_REMINDER, r.id, "VINCE (missed reminder)", r.message)
                }
            }
        }
    }

    /** Re-arms only reminders still in the future (never shows anything). */
    fun rearmFuture(context: Context) {
        val now = System.currentTimeMillis()
        for (r in ReminderStore.getAll(context)) {
            if (r.fireAtMillis > now + 1000) {
                AlertScheduler.setReliableAlarm(context, r.fireAtMillis, pendingIntentFor(context, r.id, r.message))
            }
        }
    }

    fun cancel(context: Context, id: Int, message: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(context, id, message))
        // The caller removes the reminder from the store right after; the guard
        // re-checks on its next tick and stops itself if nothing is left.
    }

    fun extractId(intent: Intent): Int = intent.getIntExtra(EXTRA_ID, -1)
    fun extractMessage(intent: Intent): String = intent.getStringExtra(EXTRA_MESSAGE) ?: "Reminder"
}
