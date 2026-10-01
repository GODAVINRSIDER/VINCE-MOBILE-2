package com.godavin.vince

import android.content.Context
import java.time.LocalDate

/**
 * VINCE Mobile isn't linked to any trading account, so instead of pulling
 * targets from one, the daily briefing and journal nudge use what you tell
 * it: standing trading rules, and a plan/targets for today that you set
 * yourself ("today's plan: ..."). The plan expires at midnight on its own.
 */
object TradingPlanStore {
    private const val PREFS = "vince_plan_prefs"
    private const val K_RULES = "rules"
    private const val K_PLAN = "plan_text"
    private const val K_PLAN_DATE = "plan_date"

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun today() = LocalDate.now().toString()

    fun getRules(c: Context): String = prefs(c).getString(K_RULES, "") ?: ""
    fun setRules(c: Context, text: String) { prefs(c).edit().putString(K_RULES, text.trim()).apply() }

    fun getPlanToday(c: Context): String {
        val p = prefs(c)
        return if (p.getString(K_PLAN_DATE, "") == today()) p.getString(K_PLAN, "") ?: "" else ""
    }

    fun setPlanToday(c: Context, text: String) {
        prefs(c).edit().putString(K_PLAN, text.trim()).putString(K_PLAN_DATE, today()).apply()
    }
}

/** Chat commands for rules and today's plan. Returns a reply, or null if the text isn't one of them. */
object PlanCommands {
    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private val SET_RULES = Regex("^(?:(?:set|update|save)\\s+)?my\\s+(?:trading\\s+)?rules\\s*(?:to|:|-)\\s*(.+)$", OPTS)
    private val ADD_RULE = Regex("^add\\s+(?:a\\s+)?rule\\s*[:\\-]?\\s*(.+)$", OPTS)
    private val SET_PLAN = Regex("^(?:(?:set|update|save)\\s+)?(?:today'?s|todays)\\s+(?:plan|targets?|goals?)\\s*(?:are|is|to|:|-)\\s*(.+)$", OPTS)
    private val SET_PLAN_2 = Regex("^my\\s+(?:plan|targets?|goals?)\\s+(?:for\\s+)?today\\s*(?:are|is|:|-)\\s*(.+)$", OPTS)
    private val SHOW_RULES = Regex("^(?:show|what(?:'s| are| is)?|read|list|tell me)\\s+(?:me\\s+)?(?:my\\s+)?(?:trading\\s+)?rules\\??$", OPTS)
    private val SHOW_PLAN = Regex("^(?:show|what(?:'s| is)?|read|tell me)\\s+(?:me\\s+)?(?:my\\s+)?(?:today'?s|todays)\\s+(?:plan|targets?|goals?)\\??$", OPTS)
    private val CLEAR_RULES = Regex("^clear\\s+(?:my\\s+)?(?:trading\\s+)?rules$", OPTS)
    private val CLEAR_PLAN = Regex("^clear\\s+(?:my\\s+)?(?:today'?s|todays)\\s+(?:plan|targets?|goals?)$", OPTS)

    fun handle(context: Context, rawText: String): String? {
        val text = rawText.replace('\u2019', '\'').trim()

        SET_RULES.find(text)?.let {
            TradingPlanStore.setRules(context, it.groupValues[1])
            return "Saved your trading rules. They'll show up in your briefing and journal check-in."
        }
        ADD_RULE.find(text)?.let {
            val existing = TradingPlanStore.getRules(context)
            val line = it.groupValues[1].trim()
            TradingPlanStore.setRules(context, if (existing.isBlank()) line else "$existing\n$line")
            return "Added to your rules."
        }
        SET_PLAN.find(text)?.let { return savePlan(context, it.groupValues[1]) }
        SET_PLAN_2.find(text)?.let { return savePlan(context, it.groupValues[1]) }

        if (SHOW_RULES.containsMatchIn(text)) {
            val rules = TradingPlanStore.getRules(context)
            return if (rules.isBlank()) "You haven't saved any rules yet. Say: my rules: ..."
            else "Your trading rules:\n$rules"
        }
        if (SHOW_PLAN.containsMatchIn(text)) {
            val plan = TradingPlanStore.getPlanToday(context)
            return if (plan.isBlank()) "No plan set for today yet. Say: today's plan: ..."
            else "Today's plan:\n$plan"
        }
        if (CLEAR_RULES.containsMatchIn(text)) {
            TradingPlanStore.setRules(context, "")
            return "Cleared your trading rules."
        }
        if (CLEAR_PLAN.containsMatchIn(text)) {
            TradingPlanStore.setPlanToday(context, "")
            return "Cleared today's plan."
        }
        return null
    }

    private fun savePlan(context: Context, plan: String): String {
        TradingPlanStore.setPlanToday(context, plan)
        return "Locked in today's plan. I'll hold you to it in the journal check-in tonight."
    }
}
