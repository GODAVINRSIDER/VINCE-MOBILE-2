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

        showNotification(context, id, message)
        if (id != -1) {
            ReminderStore.remove(context, id)
        }
    }

    private fun showNotification(context: Context, id: Int, message: String) {
        val channelId = "vince_reminders"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "VINCE Reminders", NotificationManager.IMPORTANCE_HIGH
            )
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val openAppIntent = Intent(context, MainActivity::class.java)
        val contentPendingIntent = PendingIntent.getActivity(
            context, id, openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("VINCE")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        // If POST_NOTIFICATIONS was never granted (Android 13+), this
        // silently does nothing rather than crashing - same "never let a
        // nice-to-have fail loudly" discipline as the rest of the app.
        try {
            NotificationManagerCompat.from(context).notify(id, notification)
        } catch (e: SecurityException) {
            // permission not granted - nothing more to do here
        }
    }
}
