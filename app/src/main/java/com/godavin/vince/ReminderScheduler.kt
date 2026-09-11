package com.godavin.vince

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Schedules a real OS-level alarm for each reminder, so it fires even if
 * VINCE isn't running at the time - not an in-app timer that dies with
 * the process. Uses AlarmManager's inexact-but-battery-friendly
 * setAndAllowWhileIdle rather than an exact alarm: personal reminders
 * being off by a minute or two is fine, and this deliberately avoids
 * needing Android 12+'s special "exact alarm" permission (a much bigger,
 * more sensitive permission grant than a reminder feature warrants).
 */
object ReminderScheduler {
    private const val EXTRA_ID = "reminder_id"
    private const val EXTRA_MESSAGE = "reminder_message"

    private fun pendingIntentFor(context: Context, id: Int, message: String): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_ID, id)
            putExtra(EXTRA_MESSAGE, message)
        }
        return PendingIntent.getBroadcast(
            context, id, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(context: Context, id: Int, fireAtMillis: Long, message: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pendingIntent = pendingIntentFor(context, id, message)
        alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, fireAtMillis, pendingIntent)
    }

    fun cancel(context: Context, id: Int, message: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        alarmManager.cancel(pendingIntentFor(context, id, message))
    }

    fun extractId(intent: Intent): Int = intent.getIntExtra(EXTRA_ID, -1)
    fun extractMessage(intent: Intent): String = intent.getStringExtra(EXTRA_MESSAGE) ?: "Reminder"
}
