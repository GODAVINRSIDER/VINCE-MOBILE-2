package com.godavin.vince

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Receives every proactive-alert alarm. The next occurrence is armed first,
 * synchronously, so even if the work below fails the chain of alerts keeps
 * going. Network work (briefing, news sweep) runs inside goAsync() with a
 * hard timeout, falling back to an offline message instead of hanging.
 */
class AlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(AlertScheduler.EXTRA_TYPE) ?: return
        val app = context.applicationContext
        val title = intent.getStringExtra(AlertScheduler.EXTRA_TITLE).orEmpty()
        val body = intent.getStringExtra(AlertScheduler.EXTRA_BODY).orEmpty()
        val scheduledAt = intent.getLongExtra(AlertScheduler.EXTRA_SCHEDULED, 0L)

        // Record intended vs actual delivery time (shown in Settings), so a
        // late alert can be measured instead of guessed at.
        try { AlertLog.record(app, type, scheduledAt, System.currentTimeMillis()) } catch (e: Exception) { /* logging only */ }

        try { AlertScheduler.onFired(app, type) } catch (e: Exception) { /* keep going */ }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(app, type, title, body, scheduledAt)
            } catch (e: Exception) {
                // an alert must never crash the app
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, type: String, title: String, body: String, scheduledAt: Long) {
        when (type) {
            AlertScheduler.T_LONDON -> sessionAlert(context, 7101, "London", "Europe/London", 8, 0, scheduledAt, "")
            AlertScheduler.T_NY -> sessionAlert(
                context, 7102, "New York", "America/New_York", 8, 0, scheduledAt,
                " US stocks open at 9:30 AM New York time."
            )
            AlertScheduler.T_ASIA -> sessionAlert(context, 7103, "Asia (Tokyo)", "Asia/Tokyo", 9, 0, scheduledAt, "")
            AlertScheduler.T_NEWS -> Notifs.show(
                context, Notifs.CH_NEWS, 7106 + (body.hashCode() and 0xFFFF),
                title, body
            )
            AlertScheduler.T_SWEEP -> {
                withTimeoutOrNull(9_000) { AlertScheduler.runNewsSweep(context, force = true) }
            }
            AlertScheduler.T_BRIEFING -> {
                val text = BriefingBuilder.buildWithin(context, 9_000, includeHeadlines = true)
                ConversationStore.ensureThread(context, BriefingBuilder.BRIEFING_THREAD, "Daily briefing")
                ConversationStore.addMessage(
                    context, BriefingBuilder.BRIEFING_THREAD,
                    ChatMessage(fromUser = false, text = text, persona = PersonaState.getActive(context).name)
                )
                Notifs.show(
                    context, Notifs.CH_BRIEFING, 7104, "Your briefing is ready",
                    text, openThreadId = BriefingBuilder.BRIEFING_THREAD
                )
            }
            AlertScheduler.T_JOURNAL -> {
                val text = journalPrompt(context)
                ConversationStore.ensureThread(context, BriefingBuilder.JOURNAL_THREAD, "Daily journal")
                ConversationStore.addMessage(
                    context, BriefingBuilder.JOURNAL_THREAD,
                    ChatMessage(fromUser = false, text = text, persona = PersonaState.getActive(context).name)
                )
                Notifs.show(
                    context, Notifs.CH_JOURNAL, 7105, "Journal check-in",
                    "How did the day go? Take two minutes to log it.",
                    openThreadId = BriefingBuilder.JOURNAL_THREAD
                )
            }
        }
    }

    /**
     * The wording is worked out from the real clock, not assumed: if the
     * phone delivered this alert late, it says the session already opened
     * (and how long ago) instead of claiming "in about 10 min".
     */
    private fun sessionAlert(
        context: Context, notifId: Int, name: String, zoneId: String, hour: Int, minute: Int,
        scheduledAt: Long, suffix: String
    ) {
        val lead = AlertPrefs.sessionLead(context)
        val ex = ZoneId.of(zoneId)
        val now = System.currentTimeMillis()
        val openMs = if (scheduledAt > 0L) {
            scheduledAt + lead * 60_000L
        } else {
            ZonedDateTime.of(ZonedDateTime.now(ex).toLocalDate(), java.time.LocalTime.of(hour, minute), ex)
                .toInstant().toEpochMilli()
        }
        val timeText = java.time.Instant.ofEpochMilli(openMs).atZone(ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val minsToOpen = Math.round((openMs - now) / 60_000.0).toInt()
        val rulesNote = if (TradingPlanStore.getRules(context).isBlank()) "" else " Stick to your rules."

        val title: String
        val body: String
        when {
            minsToOpen >= 2 -> {
                title = "$name session opening soon"
                body = "$name opens at $timeText your time (in about $minsToOpen min).$suffix$rulesNote"
            }
            minsToOpen in -1..1 -> {
                title = "$name session is opening now"
                body = "$name opens at $timeText your time.$suffix$rulesNote"
            }
            else -> {
                val ago = -minsToOpen
                title = "$name session already open"
                body = "$name opened at $timeText your time, $ago min ago. " +
                    "(This alert reached you late - your phone delayed it.)$suffix$rulesNote"
            }
        }
        Notifs.show(context, Notifs.CH_SESSION, notifId, title, body)
    }

    private fun journalPrompt(context: Context): String {
        val plan = TradingPlanStore.getPlanToday(context)
        val rules = TradingPlanStore.getRules(context)
        val sb = StringBuilder("Journal check-in. Reply here and I'll keep it in memory.\n\n")
        if (plan.isNotBlank()) sb.append("Today's plan was: ${plan.lines().joinToString(" / ")}\n\n")
        sb.append("1. What trades did you take, and how did they close?\n")
        sb.append("2. Did you follow your rules")
        if (rules.isNotBlank()) sb.append(" (${rules.lines().filter { it.isNotBlank() }.joinToString("; ").take(160)})")
        sb.append("?\n")
        sb.append("3. What was your best decision and your worst one?\n")
        sb.append("4. One thing to do differently tomorrow.")
        return sb.toString()
    }
}
