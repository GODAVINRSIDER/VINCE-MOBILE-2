package com.godavin.vince

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Last 20 alerts: when each was meant to fire vs when the phone actually
 * delivered it. Lets us see whether lateness is a one-off or consistent,
 * and tells a code bug apart from the phone's battery manager delaying
 * alarms. Plain text in SharedPreferences, newest first.
 */
object AlertLog {
    private const val PREFS = "vince_alert_log"
    private const val KEY = "entries"
    private const val MAX = 20

    data class Entry(val type: String, val scheduledAt: Long, val firedAt: Long) {
        val lateMinutes: Int
            get() = if (scheduledAt <= 0L) 0 else Math.round((firedAt - scheduledAt) / 60_000.0).toInt()
    }

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, type: String, scheduledAt: Long, firedAt: Long) {
        if (type == AlertScheduler.T_SWEEP) return // background housekeeping, not a user-facing alert
        val old = prefs(context).getString(KEY, "") ?: ""
        val line = "$type|$scheduledAt|$firedAt"
        val all = (listOf(line) + old.lines().filter { it.isNotBlank() }).take(MAX)
        prefs(context).edit().putString(KEY, all.joinToString("\n")).apply()
    }

    fun recent(context: Context, limit: Int = 8): List<Entry> {
        val raw = prefs(context).getString(KEY, "") ?: ""
        return raw.lines().mapNotNull { l ->
            val p = l.split("|")
            if (p.size != 3) return@mapNotNull null
            val s = p[1].toLongOrNull() ?: return@mapNotNull null
            val f = p[2].toLongOrNull() ?: return@mapNotNull null
            Entry(p[0], s, f)
        }.take(limit)
    }

    fun clear(context: Context) { prefs(context).edit().remove(KEY).apply() }

    private fun label(type: String) = when (type) {
        AlertScheduler.T_LONDON -> "London"
        AlertScheduler.T_NY -> "New York"
        AlertScheduler.T_ASIA -> "Asia"
        "reminder_alarm" -> "Reminder (alarm)"
        "reminder_guard" -> "Reminder (guard)"
        AlertScheduler.T_BRIEFING -> "Briefing"
        AlertScheduler.T_JOURNAL -> "Journal"
        AlertScheduler.T_NEWS -> "News"
        else -> type
    }

    fun describe(e: Entry): String {
        val fmt = SimpleDateFormat("EEE h:mm a", Locale.getDefault())
        val due = if (e.scheduledAt > 0L) fmt.format(Date(e.scheduledAt)) else "?"
        val got = fmt.format(Date(e.firedAt))
        val verdict = when {
            e.scheduledAt <= 0L -> ""
            e.lateMinutes <= 1 -> " - on time"
            else -> " - ${e.lateMinutes} min late"
        }
        return "${label(e.type)}: due $due, arrived $got$verdict"
    }
}
