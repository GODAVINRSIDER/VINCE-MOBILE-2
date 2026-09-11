package com.godavin.vince

import android.content.Context

/**
 * Same "deterministic local handler first, AI brain as fallback"
 * discipline as RealTimeTools - handles memory ("remember that X",
 * "what do you know about me") and reminders ("remind me in 20 minutes
 * to..."). Checked in ChatScreen's send() alongside RealTimeTools;
 * returns null if this message matched neither, in which case it falls
 * through to the normal AI chat path unchanged.
 */
object PersonalTools {

    fun handleLocalCommand(context: Context, text: String): String? {
        StructuredMemory.handleMemoryCommand(context, text)?.let { return it }

        val parsed = parseReminderCommand(text)
        if (parsed != null) {
            val (delaySeconds, confirmation, fireMessage) = parsed
            val fireAt = System.currentTimeMillis() + delaySeconds * 1000
            val id = ReminderStore.add(context, fireAt, fireMessage)
            ReminderScheduler.schedule(context, id, fireAt, fireMessage)
            return confirmation
        }

        return null
    }

    /** Returns (delaySeconds, spokenConfirmation, fireMessage) or null. */
    private fun parseReminderCommand(text: String): Triple<Long, String, String>? {
        val lower = text.lowercase()

        val reminderMatch = Regex(
            "(?:remind me (?:in|after)|set (?:a |an )?reminder (?:for|in))" +
                "(?:\\s+the\\s+next)?\\s+(\\d+)\\s*(second|minute|hour)s?\\s*(?:to\\s+(.*))?"
        ).find(lower)

        if (reminderMatch != null) {
            val amount = reminderMatch.groupValues[1].toLong()
            val unit = reminderMatch.groupValues[2]
            val task = reminderMatch.groupValues[3].trim().ifBlank { null }
            val delaySeconds = toSeconds(amount, unit)

            val fireMessage = if (task != null) "Reminder: $task" else "Reminder: time's up."
            val plural = if (amount != 1L) "s" else ""
            val confirmation = "Got it, I'll remind you in $amount $unit$plural" +
                (if (task != null) " to $task." else ".")
            return Triple(delaySeconds, confirmation, fireMessage)
        }

        val timerMatch = Regex(
            "(?:set a timer for|timer for)(?:\\s+the\\s+next)?\\s*(\\d+)\\s*(second|minute|hour)s?"
        ).find(lower)

        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toLong()
            val unit = timerMatch.groupValues[2]
            val delaySeconds = toSeconds(amount, unit)
            val plural = if (amount != 1L) "s" else ""
            val confirmation = "Timer set for $amount $unit$plural."
            return Triple(delaySeconds, confirmation, "Timer's up.")
        }

        return null
    }

    private fun toSeconds(amount: Long, unit: String): Long {
        return when (unit) {
            "second" -> amount
            "minute" -> amount * 60
            "hour" -> amount * 3600
            else -> amount
        }
    }
}
