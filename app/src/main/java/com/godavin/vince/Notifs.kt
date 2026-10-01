package com.godavin.vince

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

/** Notification channels + one helper to post an alert that opens the app (optionally straight into a chat thread). */
object Notifs {
    const val EXTRA_OPEN_THREAD = "open_thread_id"

    const val CH_SESSION = "vince_sessions"
    const val CH_NEWS = "vince_news"
    const val CH_BRIEFING = "vince_briefing"
    const val CH_JOURNAL = "vince_journal"
    const val CH_REMINDER = "vince_reminders"

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(NotificationChannel(CH_SESSION, "Session alerts", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_NEWS, "News heads-up", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel(CH_BRIEFING, "Daily briefing", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_JOURNAL, "Journal check-in", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel(CH_REMINDER, "VINCE Reminders", NotificationManager.IMPORTANCE_HIGH))
    }

    fun show(
        context: Context,
        channel: String,
        notifId: Int,
        title: String,
        body: String,
        openThreadId: String? = null
    ) {
        ensureChannels(context)
        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            if (openThreadId != null) putExtra(EXTRA_OPEN_THREAD, openThreadId)
        }
        val contentIntent = PendingIntent.getActivity(
            context, notifId, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty().take(120))
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifId, notification)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted - nothing more we can do here
        }
    }
}
