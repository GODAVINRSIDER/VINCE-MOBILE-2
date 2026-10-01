package com.godavin.vince

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "remind me at 3pm to call John", "remind me in 20 minutes to check gold",
 * "list my reminders", "cancel my reminders". Parsed deterministically in
 * code (never left to the AI to interpret), then armed through
 * ReminderScheduler, which now uses alarm-clock alarms and is re-armed on
 * app open and after a reboot.
 */
object ReminderCommands {
    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val SET_START = Regex("^(?:please\\s+|can you\\s+|could you\\s+)*(?:remind me|set (?:me )?(?:a |an )?(?:reminder|alarm)|reminder)\\b(.*)$", OPTS)
    private val LIST = Regex("^(?:what(?:'s| are| is)?|show|list|any)?\\s*(?:me\\s+)?(?:my\\s+)?(?:pending\\s+|upcoming\\s+)?reminders\\??$", OPTS)
    private val CANCEL = Regex("^(?:cancel|clear|delete|remove)\\s+(?:all\\s+)?(?:of\\s+)?(?:my\\s+)?reminders$", OPTS)

    private val RELATIVE = Regex("\\bin\\s+(\\d+|an?|half an?)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?|hr)\\b", RegexOption.IGNORE_CASE)
    private val ABSOLUTE = Regex("\\b(?:at|by|for|around)\\s+(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)?(?![\\d:])", RegexOption.IGNORE_CASE)
    private val BARE_AMPM = Regex("\\b(\\d{1,2})(?::(\\d{2}))?\\s*(a\\.?m\\.?|p\\.?m\\.?)(?![a-z])", RegexOption.IGNORE_CASE)

    fun handle(context: Context, rawText: String): String? {
        val text = rawText.replace('\u2019', '\'').trim().trimEnd('.', '!')
        val zone = ZoneId.systemDefault()

        if (CANCEL.containsMatchIn(text)) {
            val all = ReminderStore.getAll(context)
            all.forEach { ReminderScheduler.cancel(context, it.id, it.message); ReminderStore.remove(context, it.id) }
            return if (all.isEmpty()) "You have no pending reminders." else "Cancelled ${all.size} reminder(s)."
        }
        if (LIST.matches(text)) {
            val all = ReminderStore.getAll(context).sortedBy { it.fireAtMillis }
            if (all.isEmpty()) return "You have no pending reminders."
            val fmt = DateTimeFormatter.ofPattern("EEE h:mm a", Locale.getDefault())
            return "Pending reminders:\n" + all.joinToString("\n") {
                "- ${Instant.ofEpochMilli(it.fireAtMillis).atZone(zone).format(fmt)}: ${it.message}"
            }
        }

        val start = SET_START.find(text) ?: return null
        var rest = start.groupValues[1].trim()
        val now = ZonedDateTime.now(zone)
        var fireAt: ZonedDateTime? = null
        var matchedRange: IntRange? = null

        RELATIVE.find(rest)?.let { m ->
            val amountRaw = m.groupValues[1].lowercase()
            val amount = when {
                amountRaw == "a" || amountRaw == "an" -> 1.0
                amountRaw.startsWith("half") -> 0.5
                else -> amountRaw.toDouble()
            }
            val unit = m.groupValues[2].lowercase()
            val seconds = when {
                unit.startsWith("sec") -> amount
                unit.startsWith("min") -> amount * 60
                else -> amount * 3600
            }
            fireAt = now.plusSeconds(seconds.toLong())
            matchedRange = m.range
        }

        if (fireAt == null) {
            val m = ABSOLUTE.find(rest) ?: BARE_AMPM.find(rest)
            if (m != null) {
                val hourRaw = m.groupValues[1].toInt()
                val minute = m.groupValues[2].ifBlank { "0" }.toInt()
                val ampm = m.groupValues[3].lowercase().replace(".", "")
                if (hourRaw in 0..23 && minute in 0..59) {
                    val tomorrow = rest.contains("tomorrow", ignoreCase = true)
                    val baseDate: LocalDate = if (tomorrow) now.toLocalDate().plusDays(1) else now.toLocalDate()

                    val hours: List<Int> = when {
                        ampm == "pm" -> listOf(if (hourRaw == 12) 12 else (hourRaw % 12) + 12)
                        ampm == "am" -> listOf(if (hourRaw == 12) 0 else hourRaw % 12)
                        hourRaw > 12 || hourRaw == 0 -> listOf(hourRaw)
                        else -> listOf(hourRaw % 12, (hourRaw % 12) + 12) // "at 3" -> nearest 3 AM/PM
                    }
                    var best: ZonedDateTime? = null
                    for (h in hours) {
                        var cand = ZonedDateTime.of(baseDate, LocalTime.of(h, minute), zone)
                        if (!tomorrow && !cand.isAfter(now.plusSeconds(5))) cand = cand.plusDays(1)
                        if (best == null || cand.isBefore(best)) best = cand
                    }
                    fireAt = best
                    matchedRange = m.range
                }
            }
        }

        val target = fireAt
        if (target == null) {
            // Only ask for a time when it really looks like a reminder request
            // ("remind me to ..."); anything else ("remind me what we said")
            // falls through to the normal AI chat.
            return if (rest.trim().startsWith("to ", ignoreCase = true)) {
                "When should I remind you? Try: remind me at 3pm to ... or remind me in 20 minutes to ..."
            } else null
        }

        // message = what's left once the time phrase and filler words are removed
        val range = matchedRange
        if (range != null) rest = (rest.substring(0, range.first) + " " + rest.substring(range.last + 1))
        var message = rest
            .replace(Regex("\\btomorrow\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .trim(',', '.', '-', ':')
            .trim()
        message = message.replace(Regex("^(?:to|that|about|of)\\s+", RegexOption.IGNORE_CASE), "").trim()
        if (message.isBlank()) message = "Reminder"

        val fireMillis = target.toInstant().toEpochMilli()
        val id = ReminderStore.add(context, fireMillis, message)
        val scheduled = ReminderScheduler.schedule(context, id, fireMillis, message)
        if (!scheduled) {
            ReminderStore.remove(context, id)
            return "I need the \"Alarms & reminders\" permission to set that. I opened the settings page - turn it on, then ask me again."
        }
        ActivityLog.addEvent(context, "Reminder set")

        val whenText = if (target.toLocalDate() == now.toLocalDate()) {
            "at " + target.format(DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault()))
        } else {
            target.format(DateTimeFormatter.ofPattern("EEEE 'at' h:mm a", Locale.getDefault()))
        }
        return "Done. I'll remind you $whenText: $message"
    }
}
