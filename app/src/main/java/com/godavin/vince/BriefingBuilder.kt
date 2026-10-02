package com.godavin.vince

import android.content.Context
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Builds the daily briefing from things VINCE Mobile genuinely knows:
 * the clock, trading-session times, the economic calendar, optional web
 * headlines (Tavily), and what YOU told it (rules + today's plan). It is
 * not linked to any trading account, so nothing here claims to know your
 * balance, open trades or P&L. The factual part is built in code, not by
 * the AI, so times and events can't be hallucinated; the AI only adds an
 * optional one-line focus reminder when the briefing is requested in chat.
 */
object BriefingBuilder {
    const val BRIEFING_THREAD = "alerts-briefing"
    const val JOURNAL_THREAD = "alerts-journal"

    private data class Session(val label: String, val zoneId: String, val hour: Int, val minute: Int, val extra: String = "")

    private val SESSIONS = listOf(
        Session("Asia (Tokyo open)", "Asia/Tokyo", 9, 0),
        Session("London open", "Europe/London", 8, 0),
        Session("New York open", "America/New_York", 8, 0, "US stocks open 9:30 AM New York time")
    )

    suspend fun build(context: Context, includeHeadlines: Boolean): String {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val tf = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())

        val (eventsResult, headlines) = coroutineScope {
            val e = async { NewsFeed.fetchHighImpact() }
            val h = async { if (includeHeadlines) fetchHeadlines(context) else null }
            e.await() to h.await()
        }

        val sb = StringBuilder()
        val part = when (now.hour) {
            in 5..11 -> "Morning"
            in 12..16 -> "Afternoon"
            in 17..21 -> "Evening"
            else -> "Late-night"
        }
        sb.append("$part briefing - ${now.format(dayFmt)}\n")
        sb.append("Your time: ${now.format(tf)} (${zone.id})\n\n")

        // ---- sessions
        sb.append("Sessions (your time):\n")
        for (s in SESSIONS) {
            val exZone = ZoneId.of(s.zoneId)
            val exNow = now.withZoneSameInstant(exZone)
            if (exNow.dayOfWeek == DayOfWeek.SATURDAY || exNow.dayOfWeek == DayOfWeek.SUNDAY) {
                sb.append("- ${s.label}: closed for the weekend\n")
                continue
            }
            val open = ZonedDateTime.of(exNow.toLocalDate(), LocalTime.of(s.hour, s.minute), exZone)
            val local = open.withZoneSameInstant(zone)
            val state = if (open.isBefore(exNow)) "already open" else "upcoming"
            val extra = if (s.extra.isNotBlank()) " (${s.extra})" else ""
            sb.append("- ${s.label}: ${local.format(tf)} - $state$extra\n")
        }

        // ---- calendar
        sb.append("\nHigh-impact news:\n")
        val events = eventsResult.getOrNull()
        val todayEvents = mutableListOf<CalEvent>()
        if (events == null) {
            sb.append("- Couldn't load the economic calendar (offline or the feed is down).\n")
        } else {
            val relevant = events.filter { NewsFeed.matchesPrefs(context, it) }
            val today = now.toLocalDate()
            val tomorrow = today.plusDays(1)
            for (e in relevant) {
                val d = java.time.Instant.ofEpochMilli(e.timeMillis).atZone(zone)
                if (d.toLocalDate() == today && e.timeMillis > System.currentTimeMillis() - 3600_000L) todayEvents.add(e)
            }
            if (todayEvents.isEmpty()) {
                sb.append("- Nothing high-impact left for the day.\n")
            } else {
                for (e in todayEvents) {
                    val t = java.time.Instant.ofEpochMilli(e.timeMillis).atZone(zone).format(tf)
                    sb.append("- $t  ${e.country} ${e.title}\n")
                }
            }
            val later = relevant.filter {
                java.time.Instant.ofEpochMilli(it.timeMillis).atZone(zone).toLocalDate().isAfter(today)
            }.take(3)
            if (later.isNotEmpty()) {
                sb.append("Coming up:\n")
                for (e in later) {
                    val d = java.time.Instant.ofEpochMilli(e.timeMillis).atZone(zone)
                    val dayLabel = if (d.toLocalDate() == tomorrow) "Tomorrow" else d.format(DateTimeFormatter.ofPattern("EEE", Locale.getDefault()))
                    sb.append("- $dayLabel ${d.format(tf)}  ${e.country} ${e.title}\n")
                }
            }
        }

        // ---- headlines
        if (!headlines.isNullOrEmpty()) {
            sb.append("\nHeadlines:\n")
            for (h in headlines) sb.append("- $h\n")
        }

        // ---- your own rules and plan
        val rules = TradingPlanStore.getRules(context)
        val plan = TradingPlanStore.getPlanToday(context)
        sb.append("\nYour rules:\n")
        if (rules.isBlank()) sb.append("- None saved yet. Say: my rules: ...\n")
        else rules.lines().filter { it.isNotBlank() }.forEach { sb.append("- ${it.trim().trimStart('-', '*', ' ')}\n") }
        sb.append("\nPlan for today:\n")
        if (plan.isBlank()) sb.append("- Not set. Say: today's plan: ... (targets, max loss, pairs to watch)\n")
        else sb.append("- ${plan.replace("\n", "\n- ")}\n")

        return sb.toString().trim()
    }

    /** Offline-safe version for the notification path when the network is slow. */
    suspend fun buildWithin(context: Context, millis: Long, includeHeadlines: Boolean): String {
        return withTimeoutOrNull(millis) { build(context, includeHeadlines = includeHeadlines) }
            ?: buildNoNetwork(context)
    }

    private fun buildNoNetwork(context: Context): String {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        val tf = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault())
        val sb = StringBuilder()
        sb.append("Briefing - ${now.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))}, ${now.format(tf)}\n")
        sb.append("Couldn't reach the network for news, so this is the offline version.\n\n")
        val rules = TradingPlanStore.getRules(context)
        val plan = TradingPlanStore.getPlanToday(context)
        sb.append("Your rules:\n")
        sb.append(if (rules.isBlank()) "- None saved yet.\n" else rules.lines().filter { it.isNotBlank() }.joinToString("\n") { "- ${it.trim()}" } + "\n")
        sb.append("\nPlan for today:\n")
        sb.append(if (plan.isBlank()) "- Not set.\n" else "- $plan\n")
        sb.append("\nSay \"give me my briefing\" later for the full version with the news calendar.")
        return sb.toString()
    }

    private suspend fun fetchHeadlines(context: Context): List<String>? {
        val key = ApiKeyStore.getKey(context, Provider.TAVILY)
        if (key.isBlank()) return null
        val result = WebSearchTool.search(
            apiKey = key,
            query = "gold dollar indices forex market movers",
            depth = "basic",
            maxResults = 4,
            topic = "news",
            days = 2
        ).getOrNull() ?: return null
        val lines = result.lines().mapNotNull { raw ->
            val line = raw.trim().removePrefix("-").trim()
            if (line.isBlank()) return@mapNotNull null
            val noDate = line.replace(Regex("^\\[published:[^\\]]*\\]\\s*"), "")
            noDate.substringBefore(": ").take(110).ifBlank { null }
        }
        return lines.take(3).ifEmpty { null }
    }
}
