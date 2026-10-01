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

        try { AlertScheduler.onFired(app, type) } catch (e: Exception) { /* keep going */ }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                handle(app, type, title, body)
            } catch (e: Exception) {
                // an alert must never crash the app
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun handle(context: Context, type: String, title: String, body: String) {
        when (type) {
            AlertScheduler.T_LONDON -> Notifs.show(
                context, Notifs.CH_SESSION, 7101,
                "London session opening soon",
                sessionBody(context, "Europe/London", 8, 0, "London opens")
            )
            AlertScheduler.T_NY -> Notifs.show(
                context, Notifs.CH_SESSION, 7102,
                "New York session opening soon",
                sessionBody(context, "America/New_York", 8, 0, "New York opens") +
                    " US stocks open at 9:30 AM New York time."
            )
            AlertScheduler.T_ASIA -> Notifs.show(
                context, Notifs.CH_SESSION, 7103,
                "Asia session opening soon",
                sessionBody(context, "Asia/Tokyo", 9, 0, "Tokyo opens")
            )
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

    private fun sessionBody(context: Context, zoneId: String, hour: Int, minute: Int, label: String): String {
        val lead = AlertPrefs.sessionLead(context)
        val zone = ZoneId.systemDefault()
        val ex = ZoneId.of(zoneId)
        val open = ZonedDateTime.of(ZonedDateTime.now(ex).toLocalDate(), java.time.LocalTime.of(hour, minute), ex)
            .withZoneSameInstant(zone)
        val time = open.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        val rules = TradingPlanStore.getRules(context)
        val rulesNote = if (rules.isBlank()) "" else " Stick to your rules."
        return "$label at $time your time (in about $lead min).$rulesNote"
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
