package com.godavin.vince

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v2.3 - background guard for reminders (and the alerts around them).
 *
 * Why it exists: on many phones an app that is swiped away or idle has its
 * alarms dropped or delayed, so a reminder only showed up once VINCE was
 * opened again. This is a small foreground service (silent, minimum-priority
 * notification) that:
 *  - keeps the VINCE process alive and high priority while a reminder is
 *    pending (or always, if "Keep VINCE awake" is on in Settings > Alerts),
 *  - runs its own timer as a SECOND delivery path next to the AlarmManager
 *    alarm; whichever fires first delivers, the other is ignored,
 *  - re-arms every alarm each time it wakes, so a dropped alarm is repaired.
 */
class ReminderGuardService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loop: Job? = null

    companion object {
        private const val CHANNEL = "vince_guard"
        private const val NOTIF_ID = 9002

        /** Starts or stops the guard to match what is pending. Safe to call from anywhere. */
        fun sync(context: Context) {
            val app = context.applicationContext
            val needed = ReminderStore.getAll(app).isNotEmpty() || AlertPrefs.isOn(app, AlertPrefs.K_GUARD)
            try {
                if (needed) {
                    ContextCompat.startForegroundService(app, Intent(app, ReminderGuardService::class.java))
                } else {
                    app.stopService(Intent(app, ReminderGuardService::class.java))
                }
            } catch (e: Exception) {
                // Android can refuse a foreground-service start from the background.
                // The alarm path still works; opening the app starts the guard again.
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        goForeground("VINCE is keeping watch")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        goForeground(statusText())
        loop?.cancel()
        loop = scope.launch { runLoop() }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Swiped from recents: ask the system to bring the guard back right away.
        try {
            val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, 9002, Intent(this, ReminderGuardService::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + 1500, pi)
        } catch (e: Exception) { /* best-effort */ }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        loop?.cancel()
        super.onDestroy()
    }

    private suspend fun runLoop() {
        var tick = 0
        while (true) {
            val now = System.currentTimeMillis()
            val all = ReminderStore.getAll(applicationContext)

            // Deliver anything that is due (the alarm may not have fired).
            for (r in all.filter { it.fireAtMillis <= now + 500 }) {
                try { ReminderFirer.fire(applicationContext, r.id, r.message, "guard") } catch (e: Exception) { }
            }

            val pending = ReminderStore.getAll(applicationContext)

            // Every ~2 minutes make sure each alarm is still armed (repairs dropped alarms).
            if (tick % 8 == 0 && pending.isNotEmpty()) {
                try { ReminderScheduler.rearmFuture(applicationContext) } catch (e: Exception) { }
            }
            tick++

            updateNotification(statusText(pending.size))

            if (pending.isEmpty() && !AlertPrefs.isOn(applicationContext, AlertPrefs.K_GUARD)) {
                stopSelf()
                return
            }

            val next = pending.minOfOrNull { it.fireAtMillis }
            val wait = if (next == null) 30_000L else (next - System.currentTimeMillis()).coerceIn(1_000L, 15_000L)
            delay(wait)
        }
    }

    private fun statusText(count: Int = ReminderStore.getAll(applicationContext).size): String =
        if (count > 0) "Watching $count reminder${if (count == 1) "" else "s"}" else "Keeping alerts on time"

    private fun updateNotification(text: String) {
        try {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) { }
    }

    private fun buildNotification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setContentTitle("VINCE")
        .setContentText(text)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setOngoing(true)
        .setSilent(true)
        .setPriority(NotificationCompat.PRIORITY_MIN)
        .build()

    private fun goForeground(text: String) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(
                NotificationChannel(CHANNEL, "VINCE background guard", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val n = buildNotification(text)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
        }
    }
}
