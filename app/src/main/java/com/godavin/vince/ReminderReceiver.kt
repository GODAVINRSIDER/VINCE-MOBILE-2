package com.godavin.vince

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/**
 * Fires when a reminder's AlarmManager alarm goes off - shows a real
 * Android notification, then cleans the fired reminder out of
 * ReminderStore so it doesn't linger as "pending" forever.
 */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = ReminderScheduler.extractId(intent)
        val message = ReminderScheduler.extractMessage(intent)
        ReminderFirer.fire(context, id, message, "alarm")
    }
}
