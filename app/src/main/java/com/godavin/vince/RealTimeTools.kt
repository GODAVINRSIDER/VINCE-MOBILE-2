package com.godavin.vince

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Same "deterministic local handler first, AI brain as fallback" pattern
 * PC-VINCE's commands.py already uses for things like price/time/news -
 * never let the AI guess at a fact a real data source can answer exactly.
 * Checked before BrainRouter in ChatScreen's send(); returns null if this
 * message isn't a time/price/news question, in which case it falls
 * through to the normal AI chat path unchanged.
 *
 * Time is answered instantly from the phone's own clock/timezone - no
 * network call needed, and unlike the PC (which has no idea where the
 * user physically is), the phone's local time IS the user's real local
 * time.
 */
object RealTimeTools {
    private val TIME_TRIGGERS = listOf(
        "what time", "current time", "what's the time", "whats the time", "time is it"
    )
    private val DATE_TRIGGERS = listOf(
        "what's the date", "whats the date", "what day is it", "today's date", "what is today's date"
    )
    private val NEWS_TRIGGERS = listOf(
        "high impact", "high-impact", "economic calendar", "upcoming news",
        "upcoming events", "market news", "news calendar", "economic events",
        "what's on the calendar", "whats on the calendar"
    )

    suspend fun handleLocalCommand(context: Context, text: String): String? {
        val lower = text.lowercase()

        if (TIME_TRIGGERS.any { lower.contains(it) }) {
            return currentTimeReply()
        }

        if (DATE_TRIGGERS.any { lower.contains(it) }) {
            return currentDateReply()
        }

        if (NEWS_TRIGGERS.any { lower.contains(it) }) {
            val result = NewsTools.getUpcomingHighImpact()
            return result.fold(
                onSuccess = { it },
                onFailure = { e -> "Couldn't fetch the economic calendar right now. (${e.message})" }
            )
        }

        // Live market PRICE fetching (Yahoo/Finnhub) was removed per
        // Vincent's call - it never worked reliably on-device and he'd
        // rather price questions just fall through to the AI persona
        // (which will say it can't check live prices) than keep hitting
        // a broken local fetch. Time/date/economic-calendar above are
        // unaffected - those always worked fine.

        return null
    }

    private fun currentTimeReply(): String {
        val fmt = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "It's ${fmt.format(Date())} (your device's local time)."
    }

    private fun currentDateReply(): String {
        val fmt = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.getDefault())
        return "Today is ${fmt.format(Date())}."
    }
}
