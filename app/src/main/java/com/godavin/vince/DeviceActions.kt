package com.godavin.vince

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock

/**
 * Small phone actions, done with standard Android intents - VINCE hands
 * the job to the browser / Maps / Clock app and stops there (it does not
 * control anything inside those apps). Deterministic, checked before the AI.
 */
object DeviceActions {
    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    private val OPEN_SITE = Regex(
        "^(?:open|go to|visit|launch|take me to)\\s+((?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|co|ke|app|dev|info|me|ai|tv|trade|finance|news)(?:/\\S*)?)$", OPTS
    )
    private val GOOGLE = Regex("^google\\s+(.+)$", OPTS)
    private val TIMER_2 = Regex("^(?:set|start|run)\\s+(?:a\\s+)?timer\\s+(?:for|of)\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$", OPTS)
    private val NAVIGATE = Regex("^(?:navigate to|directions to|take me to|show me on (?:the )?map)\\s+(.+)$", OPTS)

    fun handle(context: Context, rawText: String): String? {
        val text = rawText.replace('\u2019', '\'').trim().trimEnd('.', '!', '?')

        OPEN_SITE.find(text)?.let { m ->
            val raw = m.groupValues[1]
            val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
            return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opening $raw.", "I couldn't open a browser for $raw.")
        }

        GOOGLE.find(text)?.let { m ->
            val q = m.groupValues[1].trim()
            val url = "https://www.google.com/search?q=" + Uri.encode(q)
            return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Searching Google for \"$q\".", "I couldn't open a browser.")
        }

        TIMER_2.find(text)?.let { m ->
            val seconds = toSeconds(m.groupValues[1].toInt(), m.groupValues[2])
            return startTimer(context, seconds)
        }

        NAVIGATE.find(text)?.let { m ->
            val place = m.groupValues[1].trim()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
            return launch(context, intent, "Opening maps for $place.", "I couldn't find a maps app.")
        }
        return null
    }

    private fun toSeconds(amount: Int, unit: String): Int {
        val u = unit.lowercase()
        return when {
            u.startsWith("sec") -> amount
            u.startsWith("min") -> amount * 60
            else -> amount * 3600
        }
    }

    private fun startTimer(context: Context, seconds: Int): String {
        if (seconds <= 0 || seconds > 24 * 3600) return "Give me a timer between 1 second and 24 hours."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "VINCE timer")
        }
        val label = when {
            seconds % 3600 == 0 -> "${seconds / 3600} hour(s)"
            seconds % 60 == 0 -> "${seconds / 60} minute(s)"
            else -> "$seconds seconds"
        }
        return launch(context, intent, "Timer set for $label in your Clock app.", "I couldn't reach your Clock app to start a timer.")
    }

    private fun launch(context: Context, intent: Intent, okMsg: String, failMsg: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActivityLog.addEvent(context, okMsg.trimEnd('.'))
            okMsg
        } catch (e: ActivityNotFoundException) {
            failMsg
        } catch (e: SecurityException) {
            failMsg
        }
    }
}
