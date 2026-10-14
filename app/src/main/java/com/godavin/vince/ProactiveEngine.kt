package com.godavin.vince

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Date
import java.util.Locale

/**
 * v2.7 - proactive personas.
 *
 * Until now VINCE only spoke when spoken to (apart from session/news alerts). This makes
 * the three personas reach out on their own, JARVIS/FRIDAY style: a short check-in with
 * ONE real opinion or idea and an easy question, in the persona's own lane, at times you
 * choose. It is NOT trading-only - the prompt rotates across projects and tools, learning,
 * the brand and business, health and energy, money and plain life.
 *
 *   Morning  - VINCE  (builder / strategist: what to focus on)
 *   Midday   - DAVINA (calm and creative: an idea, a better way, something to learn)
 *   Evening  - CLARA  (warm: how the day went, wind down, what to carry to tomorrow)
 *
 * Guard rails so it never becomes noise: a master switch and one switch per check-in,
 * quiet hours, at most 3 a day, no live web search (so it never burns Tavily credits),
 * and the last few check-ins are shown to the model so it does not repeat itself.
 * If every AI provider fails, it stays silent instead of sending filler.
 *
 * Alarms are armed through AlertScheduler exactly like the briefing, so they share its
 * re-arm-on-open / re-arm-on-boot behaviour and its "reliable mode".
 */
object ProactiveEngine {
    const val THREAD = "proactive_checkins"
    const val K_MASTER = "pro_master"
    const val K_QUIET_START = "pro_quiet_start"   // minutes after midnight
    const val K_QUIET_END = "pro_quiet_end"
    private const val K_DAY = "pro_day"
    private const val K_COUNT = "pro_count"
    private const val MAX_PER_DAY = 3

    enum class Slot(
        val type: String,
        val rc: Int,
        val notifId: Int,
        val prefKey: String,
        val minKey: String,
        val label: String,
        val defaultMinutes: Int,
        val persona: Persona,
        val brief: String
    ) {
        MORNING(
            "pro_morning", 9107, 7201, "pro_morning_on", "pro_morning_min", "Morning check-in", 9 * 60 + 30, Persona.VINCE,
            "It is the start of the user's working day. Open with a sharp take on what deserves their focus today " +
                "(a project they are building, something they said they wanted to do, or a decision they keep postponing)."
        ),
        MIDDAY(
            "pro_midday", 9108, 7202, "pro_midday_on", "pro_midday_min", "Midday idea", 14 * 60 + 30, Persona.DAVINA,
            "It is the middle of the day. Bring ONE fresh idea, a smarter way to do something they are working on, " +
                "or one short thing worth learning today, and say which you would pick."
        ),
        EVENING(
            "pro_evening", 9109, 7203, "pro_evening_on", "pro_evening_min", "Evening wind-down", 19 * 60, Persona.CLARA,
            "It is the evening. Check in on how the day really went, give an honest, kind observation, and suggest " +
                "one small thing to carry into tomorrow or one way to switch off."
        );
    }

    fun slotForType(type: String): Slot? = Slot.values().find { it.type == type }

    // ------------------------------------------------------------------ settings

    fun masterOn(c: Context) = AlertPrefs.isOn(c, K_MASTER)
    fun slotOn(c: Context, s: Slot) = AlertPrefs.isOn(c, s.prefKey)
    fun minutes(c: Context, s: Slot) = AlertPrefs.getInt(c, s.minKey, s.defaultMinutes)
    fun quietStart(c: Context) = AlertPrefs.getInt(c, K_QUIET_START, 22 * 60)
    fun quietEnd(c: Context) = AlertPrefs.getInt(c, K_QUIET_END, 7 * 60)

    private fun inQuietHours(c: Context): Boolean {
        val now = ZonedDateTime.now(ZoneId.systemDefault())
        val m = now.hour * 60 + now.minute
        val s = quietStart(c)
        val e = quietEnd(c)
        return if (s == e) false else if (s < e) (m in s until e) else (m >= s || m < e)
    }

    private fun todayKey(): String = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    private fun sentToday(c: Context): Int =
        if (AlertPrefs.getString(c, K_DAY, "") == todayKey()) AlertPrefs.getInt(c, K_COUNT, 0) else 0

    private fun bumpSent(c: Context) {
        val n = sentToday(c) + 1
        AlertPrefs.setString(c, K_DAY, todayKey())
        AlertPrefs.setInt(c, K_COUNT, n)
    }

    // ------------------------------------------------------------------ scheduling

    /** Arms (or cancels) all three check-ins. Safe to call as often as you like. */
    fun scheduleAll(context: Context) {
        val app = context.applicationContext
        for (s in Slot.values()) scheduleSlot(app, s)
    }

    fun scheduleSlot(context: Context, s: Slot) {
        val app = context.applicationContext
        if (!masterOn(app) || !slotOn(app, s)) {
            val am = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(AlertScheduler.pending(app, s.rc, s.type))
            return
        }
        val minutesOfDay = minutes(app, s)
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var target = now.withHour(minutesOfDay / 60).withMinute(minutesOfDay % 60).withSecond(0).withNano(0)
        if (!target.isAfter(now.plusSeconds(5))) target = target.plusDays(1)
        val at = target.toInstant().toEpochMilli()
        AlertScheduler.setReliableAlarm(app, at, AlertScheduler.pending(app, s.rc, s.type, scheduledAt = at))
    }

    // ------------------------------------------------------------------ the check-in itself

    /**
     * Writes and posts one check-in. [force] = a manual "send one now" test (ignores the
     * switches, quiet hours and the daily limit). Returns the text, or null if nothing was sent.
     */
    suspend fun fire(context: Context, slot: Slot, force: Boolean = false, timeoutMs: Long = 9_000L): String? {
        val app = context.applicationContext
        if (!force) {
            if (!masterOn(app) || !slotOn(app, slot)) return null
            if (inQuietHours(app)) return null
            if (sentToday(app) >= MAX_PER_DAY) return null
        }
        val text = compose(app, slot, timeoutMs) ?: return null
        ConversationStore.ensureThread(app, THREAD, "Check-ins")
        ConversationStore.addMessage(app, THREAD, ChatMessage(fromUser = false, text = text, persona = slot.persona.name))
        Notifs.show(app, Notifs.CH_PROACTIVE, slot.notifId, slot.persona.displayName, text, openThreadId = THREAD)
        bumpSent(app)
        return text
    }

    private fun recentCheckins(c: Context): String {
        val thread = try { ConversationStore.getThread(c, THREAD) } catch (e: Exception) { null } ?: return ""
        return thread.messages.filter { !it.fromUser }.takeLast(3).joinToString(" | ") { it.text.replace('\n', ' ').take(160) }
    }

    private suspend fun compose(c: Context, slot: Slot, timeoutMs: Long): String? {
        val day = SimpleDateFormat("EEEE", Locale.getDefault()).format(Date())
        val plan = TradingPlanStore.getPlanToday(c)
        val recent = recentCheckins(c)
        val instruction = buildString {
            append("INTERNAL TASK - this is NOT a message from the user. You are ${slot.persona.displayName} and you are ")
            append("sending the user ONE short, unprompted check-in notification. ")
            append(slot.brief).append(" Today is $day. ")
            if (plan.isNotBlank()) append("Their plan for today: ${plan.lines().filter { it.isNotBlank() }.joinToString(" / ").take(300)}. ")
            append("Rules: 2 to 4 short sentences, plain text, no Markdown, no long greeting. Give ONE specific, useful ")
            append("opinion, idea or suggestion in your own lane and voice, then end with ONE easy question or choice. ")
            append("Do not default to trading: rotate across their projects and tools, learning, their brand and business, ")
            append("health and energy, money and ordinary life, and use what you remember about them when it fits. ")
            append("Never invent facts about the user. Never say this is a notification, a task or a check-in. ")
            if (recent.isNotBlank()) append("Do not repeat these recent messages or their topics: $recent")
        }
        // an alarm broadcast only has ~10 s, so the alarm path gives up at 9 s (the manual test waits longer)
        val raw = withTimeoutOrNull(timeoutMs) {
            BrainRouter.sendMessage(c, instruction, slot.persona, emptyList(), null, allowSearch = false)
        } ?: return null
        if (raw.startsWith("All providers failed") || raw.startsWith("No API keys")) return null
        val clean = raw.replace("*", "").replace("#", "").trim()
        return clean.takeIf { it.isNotBlank() }?.take(700)
    }
}
