package com.godavin.vince

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Same "deterministic local handler first, AI brain as fallback" pattern
 * PC-VINCE's commands.py already uses for things like price/time - never
 * let the AI guess at a fact the device or a real data source can answer
 * exactly. Checked before BrainRouter in ChatScreen's send(); returns
 * null if this message isn't a time/price question, in which case it
 * falls through to the normal AI chat path unchanged.
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
    private val PRICE_TRIGGERS = listOf(
        "price of", "current price", "market price", "trading at", "how much is",
        "what's the price", "whats the price", "price is", "worth right now"
    )

    suspend fun handleLocalCommand(text: String): String? {
        val lower = text.lowercase()

        if (TIME_TRIGGERS.any { lower.contains(it) }) {
            return currentTimeReply()
        }

        if (DATE_TRIGGERS.any { lower.contains(it) }) {
            return currentDateReply()
        }

        val symbolMatch = MarketTools.matchSymbol(lower)
        if (symbolMatch != null && PRICE_TRIGGERS.any { lower.contains(it) }) {
            val (displayName, ticker) = symbolMatch
            val result = MarketTools.fetchPrice(ticker)
            return result.fold(
                onSuccess = { price -> "The current price of ${displayName.uppercase()} is $price." },
                onFailure = { e -> "Couldn't fetch the current price for ${displayName.uppercase()} right now. (${e.message})" }
            )
        }

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
