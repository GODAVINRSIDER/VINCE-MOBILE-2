package com.godavin.vince

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * Schedules every proactive alert as a real OS alarm.
 *
 * - Session opens use the exchange's own time zone (London 08:00, New York
 *   08:00, Tokyo 09:00), so daylight-saving shifts are handled by the
 *   time-zone rules instead of hardcoded UTC hours. Weekends are skipped.
 * - Briefing / journal are a fixed time of day on the phone's clock.
 * - News heads-ups are scheduled by a "sweep": fetch the economic calendar,
 *   then arm one alarm per upcoming high-impact event. The sweep re-runs
 *   daily, whenever the app opens, and after a reboot.
 * - Alarms don't repeat on their own, so every alarm re-arms its next
 *   occurrence when it fires (see AlertReceiver) and everything is
 *   re-armed on app open and on boot.
 */
object AlertScheduler {
    const val EXTRA_TYPE = "alert_type"
    const val EXTRA_TITLE = "alert_title"
    const val EXTRA_BODY = "alert_body"

    const val T_LONDON = "london"
    const val T_NY = "ny"
    const val T_ASIA = "asia"
    const val T_BRIEFING = "briefing"
    const val T_JOURNAL = "journal"
    const val T_SWEEP = "news_sweep"
    const val T_NEWS = "news"

    private const val RC_LONDON = 9101
    private const val RC_NY = 9102
    private const val RC_ASIA = 9103
    private const val RC_BRIEFING = 9104
    private const val RC_JOURNAL = 9105
    private const val RC_SWEEP = 9106

    private const val SWEEP_HOUR = 5
    private const val SWEEP_THROTTLE_MS = 3 * 60 * 60 * 1000L

    private fun pending(context: Context, requestCode: Int, type: String, title: String = "", body: String = ""): PendingIntent {
        val intent = Intent(context, AlertReceiver::class.java).apply {
            putExtra(EXTRA_TYPE, type)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_BODY, body)
        }
        return PendingIntent.getBroadcast(
            context, requestCode, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Arms [operation] as reliably as the phone allows (alarm-clock alarms are exempt from Doze batching). */
    fun setReliableAlarm(context: Context, triggerAtMillis: Long, operation: PendingIntent) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (canExact) {
                if (AlertPrefs.isOn(context, AlertPrefs.K_RELIABLE)) {
                    val showIntent = PendingIntent.getActivity(
                        context, 0, Intent(context, MainActivity::class.java),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    am.setAlarmClock(AlarmManager.AlarmClockInfo(triggerAtMillis, showIntent), operation)
                } else {
                    am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
                }
            } else {
                am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
            }
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, operation)
        }
    }

    private fun cancel(context: Context, requestCode: Int, type: String) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pending(context, requestCode, type))
    }

    /** Re-arms everything. Safe to call as often as you like - each alarm just replaces its previous self. */
    fun rescheduleAll(context: Context, forceNews: Boolean = false) {
        val app = context.applicationContext
        Notifs.ensureChannels(app)
        scheduleSession(app, T_LONDON, RC_LONDON, AlertPrefs.K_LONDON, "Europe/London", 8, 0)
        scheduleSession(app, T_NY, RC_NY, AlertPrefs.K_NY, "America/New_York", 8, 0)
        scheduleSession(app, T_ASIA, RC_ASIA, AlertPrefs.K_ASIA, "Asia/Tokyo", 9, 0)
        scheduleDaily(app, T_BRIEFING, RC_BRIEFING, AlertPrefs.K_BRIEFING, AlertPrefs.briefingMinutes(app))
        scheduleDaily(app, T_JOURNAL, RC_JOURNAL, AlertPrefs.K_JOURNAL, AlertPrefs.journalMinutes(app))
        scheduleSweepAlarm(app)
        ReminderScheduler.rearmAll(app)
        // The news sweep needs the network, so it runs off the main thread (throttled).
        CoroutineScope(Dispatchers.IO).launch {
            try { runNewsSweep(app, force = forceNews) } catch (e: Exception) { /* best-effort */ }
        }
    }

    /** Called by the receiver when [type] fires: arm its next occurrence. */
    fun onFired(context: Context, type: String) {
        when (type) {
            T_LONDON -> scheduleSession(context, type, RC_LONDON, AlertPrefs.K_LONDON, "Europe/London", 8, 0)
            T_NY -> scheduleSession(context, type, RC_NY, AlertPrefs.K_NY, "America/New_York", 8, 0)
            T_ASIA -> scheduleSession(context, type, RC_ASIA, AlertPrefs.K_ASIA, "Asia/Tokyo", 9, 0)
            T_BRIEFING -> scheduleDaily(context, type, RC_BRIEFING, AlertPrefs.K_BRIEFING, AlertPrefs.briefingMinutes(context))
            T_JOURNAL -> scheduleDaily(context, type, RC_JOURNAL, AlertPrefs.K_JOURNAL, AlertPrefs.journalMinutes(context))
            T_SWEEP -> scheduleSweepAlarm(context)
        }
    }

    // ---- sessions ---------------------------------------------------

    private fun scheduleSession(context: Context, type: String, rc: Int, key: String, zoneId: String, hour: Int, minute: Int) {
        if (!AlertPrefs.isOn(context, key)) { cancel(context, rc, type); return }
        val fireAt = nextSessionFire(zoneId, hour, minute, AlertPrefs.sessionLead(context), Instant.now())
        setReliableAlarm(context, fireAt.toEpochMilli(), pending(context, rc, type))
    }

    /** Next weekday session open (in the exchange's own zone), minus the lead time. */
    fun nextSessionFire(zoneId: String, hour: Int, minute: Int, leadMin: Int, from: Instant): Instant {
        val zone = ZoneId.of(zoneId)
        val startDate = from.atZone(zone).toLocalDate()
        for (i in 0..9) {
            val open = ZonedDateTime.of(startDate.plusDays(i.toLong()), LocalTime.of(hour, minute), zone)
            if (open.dayOfWeek == DayOfWeek.SATURDAY || open.dayOfWeek == DayOfWeek.SUNDAY) continue
            val fire = open.minusMinutes(leadMin.toLong()).toInstant()
            if (fire.isAfter(from.plusSeconds(5))) return fire
        }
        return from.plusSeconds(24 * 3600) // unreachable in practice
    }

    // ---- daily briefing / journal -----------------------------------

    private fun scheduleDaily(context: Context, type: String, rc: Int, key: String, minutesOfDay: Int) {
        if (!AlertPrefs.isOn(context, key)) { cancel(context, rc, type); return }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var target = now.withHour(minutesOfDay / 60).withMinute(minutesOfDay % 60).withSecond(0).withNano(0)
        if (!target.isAfter(now.plusSeconds(5))) target = target.plusDays(1)
        setReliableAlarm(context, target.toInstant().toEpochMilli(), pending(context, rc, type))
    }

    // ---- news -------------------------------------------------------

    private fun scheduleSweepAlarm(context: Context) {
        if (!AlertPrefs.isOn(context, AlertPrefs.K_NEWS)) { cancel(context, RC_SWEEP, T_SWEEP); return }
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var target = now.withHour(SWEEP_HOUR).withMinute(0).withSecond(0).withNano(0)
        if (!target.isAfter(now.plusSeconds(5))) target = target.plusDays(1)
        setReliableAlarm(context, target.toInstant().toEpochMilli(), pending(context, RC_SWEEP, T_SWEEP))
    }

    /** Fetches the calendar and arms one alarm per upcoming high-impact event in the next 48 hours. */
    suspend fun runNewsSweep(context: Context, force: Boolean) {
        val app = context.applicationContext
        val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // Always clear whatever the previous sweep armed, so toggling news
        // off (or changing the lead time) can't leave stale alarms behind.
        val oldCodes = AlertPrefs.getString(app, AlertPrefs.K_NEWS_CODES, "")
            .split(",").mapNotNull { it.toIntOrNull() }
        val enabled = AlertPrefs.isOn(app, AlertPrefs.K_NEWS)

        val now = System.currentTimeMillis()
        val last = AlertPrefs.getLong(app, AlertPrefs.K_LAST_SWEEP, 0L)
        if (enabled && !force && oldCodes.isNotEmpty() && now - last < SWEEP_THROTTLE_MS) return

        for (code in oldCodes) am.cancel(pending(app, code, T_NEWS))
        AlertPrefs.setString(app, AlertPrefs.K_NEWS_CODES, "")
        if (!enabled) return

        val events = NewsFeed.fetchHighImpact().getOrNull() ?: return
        val leadMs = AlertPrefs.newsLead(app) * 60_000L
        val horizon = now + 48 * 3600_000L
        val tf = java.text.SimpleDateFormat("EEE h:mm a", java.util.Locale.getDefault())
        val codes = mutableListOf<Int>()

        for (e in events) {
            if (e.timeMillis <= now + leadMs || e.timeMillis > horizon) continue
            if (!NewsFeed.matchesPrefs(app, e)) continue
            val code = 100_000 + ((e.timeMillis / 60_000L + e.title.hashCode()) and 0x7FFFFFFF).toInt() % 800_000
            val title = "High-impact news in ${AlertPrefs.newsLead(app)} min"
            val body = "${e.country} ${e.title} at ${tf.format(java.util.Date(e.timeMillis))}. " +
                "Expect volatility - check your open trades and stops."
            setReliableAlarm(app, e.timeMillis - leadMs, pending(app, code, T_NEWS, title, body))
            codes.add(code)
        }
        AlertPrefs.setString(app, AlertPrefs.K_NEWS_CODES, codes.joinToString(","))
        AlertPrefs.setLong(app, AlertPrefs.K_LAST_SWEEP, now)
    }
}
