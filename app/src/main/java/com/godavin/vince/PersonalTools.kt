package com.godavin.vince

import android.content.Context
import java.util.Calendar

/**
 * Same "deterministic local handler first, AI brain as fallback"
 * discipline as RealTimeTools - handles memory ("remember that X",
 * "what do you know about me"), reminders (both "remind me in 20 minutes
 * to..." and "remind me at 4:30 to..."), and now opening other installed
 * apps ("open WhatsApp"). Checked in ChatScreen's send() alongside
 * RealTimeTools; returns null if this message matched none of these, in
 * which case it falls through to the normal AI chat path unchanged.
 */
object PersonalTools {

    fun handleLocalCommand(context: Context, text: String): String? {
        StructuredMemory.handleMemoryCommand(context, text)?.let { return it }

        val parsedReminder = parseReminderCommand(text)
        if (parsedReminder != null) {
            val (fireAtMillis, confirmation, fireMessage) = parsedReminder
            val id = ReminderStore.add(context, fireAtMillis, fireMessage)
            val scheduled = ReminderScheduler.schedule(context, id, fireAtMillis, fireMessage)
            if (!scheduled) {
                // ReminderScheduler already sent the user to the system
                // "allow exact alarms" screen - don't leave a dead entry
                // sitting in the store since nothing was actually armed.
                ReminderStore.remove(context, id)
                return "I need one-time permission to set exact reminders - " +
                    "I've opened the settings screen for it. Grant it, then ask me again."
            }
            return confirmation
        }

        val appName = AppLauncher.extractAppNameFromCommand(text)
        if (appName != null) {
            val openedLabel = AppLauncher.openAppByName(context, appName)
            return if (openedLabel != null) {
                "Opening $openedLabel."
            } else {
                "I couldn't find an app called \"$appName\" on this phone."
            }
        }

        return null
    }

    private data class ParsedReminder(
        val fireAtMillis: Long,
        val confirmation: String,
        val fireMessage: String
    )

    /** Returns a resolved fire time + confirmation + fire message, or null
     * if [text] doesn't match a reminder/timer command. Handles both
     * relative ("in 20 minutes") and absolute ("at 4:30" / "at 4:30 pm")
     * phrasing, always resolved against the phone's own real clock so
     * "in 2 minutes" and "at 4:30" both land on the actual current time. */
    private fun parseReminderCommand(text: String): ParsedReminder? {
        val lower = text.lowercase()

        // "remind me in/after N second/minute/hour(s) [to ...]"
        val relativeMatch = Regex(
            "(?:remind me (?:in|after)|set (?:a |an )?reminder (?:for|in))" +
                "(?:\\s+the\\s+next)?\\s+(\\d+)\\s*(second|minute|hour)s?\\s*(?:to\\s+(.*))?"
        ).find(lower)

        if (relativeMatch != null) {
            val amount = relativeMatch.groupValues[1].toLong()
            val unit = relativeMatch.groupValues[2]
            val task = relativeMatch.groupValues[3].trim().ifBlank { null }
            val fireAtMillis = System.currentTimeMillis() + toSeconds(amount, unit) * 1000

            val fireMessage = if (task != null) "Reminder: $task" else "Reminder: time's up."
            val plural = if (amount != 1L) "s" else ""
            val confirmation = "Got it, I'll remind you in $amount $unit$plural" +
                (if (task != null) " to $task." else ".")
            return ParsedReminder(fireAtMillis, confirmation, fireMessage)
        }

        // "remind me at 4:30 [am/pm] [to ...]" / "set a reminder at 4:30 [to ...]"
        val absoluteMatch = Regex(
            "(?:remind me at|set (?:a |an )?reminder (?:for|at))\\s+" +
                "(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?\\s*(?:to\\s+(.*))?"
        ).find(lower)

        if (absoluteMatch != null) {
            var hour = absoluteMatch.groupValues[1].toInt()
            val minute = absoluteMatch.groupValues[2].ifBlank { "0" }.toInt()
            val meridiem = absoluteMatch.groupValues[3].ifBlank { null }
            val task = absoluteMatch.groupValues[4].trim().ifBlank { null }

            if (meridiem == "pm" && hour < 12) hour += 12
            if (meridiem == "am" && hour == 12) hour = 0

            if (hour !in 0..23 || minute !in 0..59) return null

            val fireAtMillis = resolveNextOccurrence(hour, minute)
            val fireMessage = if (task != null) "Reminder: $task" else "Reminder: time's up."

            val displayHour = when {
                hour == 0 -> 12
                hour > 12 -> hour - 12
                else -> hour
            }
            val displaySuffix = if (meridiem != null) meridiem.uppercase() else if (hour >= 12) "PM" else "AM"
            val timeLabel = "$displayHour:${minute.toString().padStart(2, '0')} $displaySuffix"

            val confirmation = "Got it, I'll remind you at $timeLabel" +
                (if (task != null) " to $task." else ".")
            return ParsedReminder(fireAtMillis, confirmation, fireMessage)
        }

        // "set a timer for N second/minute/hour(s)" - unchanged, always relative
        val timerMatch = Regex(
            "(?:set a timer for|timer for)(?:\\s+the\\s+next)?\\s*(\\d+)\\s*(second|minute|hour)s?"
        ).find(lower)

        if (timerMatch != null) {
            val amount = timerMatch.groupValues[1].toLong()
            val unit = timerMatch.groupValues[2]
            val fireAtMillis = System.currentTimeMillis() + toSeconds(amount, unit) * 1000
            val plural = if (amount != 1L) "s" else ""
            val confirmation = "Timer set for $amount $unit$plural."
            return ParsedReminder(fireAtMillis, confirmation, "Timer's up.")
        }

        return null
    }

    /** Resolves [hour]:[minute] (24h) against the phone's real Calendar -
     * today if that time hasn't passed yet, otherwise tomorrow. This is
     * what makes "remind me at 4:30" fire against the actual device
     * clock rather than any app-internal notion of time. */
    private fun resolveNextOccurrence(hour: Int, minute: Int): Long {
        val target = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (target.before(Calendar.getInstance())) {
            target.add(Calendar.DAY_OF_MONTH, 1)
        }
        return target.timeInMillis
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
