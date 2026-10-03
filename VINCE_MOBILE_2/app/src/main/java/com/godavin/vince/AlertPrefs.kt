package com.godavin.vince

import android.content.Context

/**
 * Settings for the proactive alerts (session opens, news heads-up, daily
 * briefing, journal nudge). Plain SharedPreferences - nothing sensitive.
 */
object AlertPrefs {
    private const val PREFS = "vince_alert_prefs"

    const val K_LONDON = "alert_london"
    const val K_NY = "alert_ny"
    const val K_ASIA = "alert_asia"
    const val K_NEWS = "alert_news"
    const val K_NEWS_ALL_CCY = "alert_news_all_ccy"
    const val K_BRIEFING = "alert_briefing"
    const val K_JOURNAL = "alert_journal"
    const val K_RELIABLE = "alert_reliable_mode"
    const val K_BRIEF_MIN = "alert_briefing_minutes"
    const val K_JOURNAL_MIN = "alert_journal_minutes"
    const val K_SESSION_LEAD = "alert_session_lead"
    const val K_NEWS_LEAD = "alert_news_lead"
    const val K_LAST_SWEEP = "alert_last_sweep"
    const val K_NEWS_CODES = "alert_news_codes"

    // Asia is the only one off by default - it opens in the middle of the
    // night for Nairobi. Everything else starts on so the feature is
    // visible from the first run; each one has its own switch.
    private val DEFAULT_ON = mapOf(
        K_LONDON to true, K_NY to true, K_ASIA to false, K_NEWS to true,
        K_NEWS_ALL_CCY to false, K_BRIEFING to true, K_JOURNAL to true, K_RELIABLE to true
    )

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isOn(c: Context, key: String): Boolean = prefs(c).getBoolean(key, DEFAULT_ON[key] ?: true)
    fun setOn(c: Context, key: String, value: Boolean) { prefs(c).edit().putBoolean(key, value).apply() }

    fun getInt(c: Context, key: String, def: Int): Int = prefs(c).getInt(key, def)
    fun setInt(c: Context, key: String, value: Int) { prefs(c).edit().putInt(key, value).apply() }

    fun getLong(c: Context, key: String, def: Long): Long = prefs(c).getLong(key, def)
    fun setLong(c: Context, key: String, value: Long) { prefs(c).edit().putLong(key, value).apply() }

    fun getString(c: Context, key: String, def: String): String = prefs(c).getString(key, def) ?: def
    fun setString(c: Context, key: String, value: String) { prefs(c).edit().putString(key, value).apply() }

    fun briefingMinutes(c: Context) = getInt(c, K_BRIEF_MIN, 7 * 60 + 30)
    fun journalMinutes(c: Context) = getInt(c, K_JOURNAL_MIN, 21 * 60)
    fun sessionLead(c: Context) = getInt(c, K_SESSION_LEAD, 10)
    fun newsLead(c: Context) = getInt(c, K_NEWS_LEAD, 15)

    fun formatMinutes(total: Int): String {
        val h24 = (total / 60) % 24
        val m = total % 60
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val ampm = if (h24 < 12) "AM" else "PM"
        return "%d:%02d %s".format(h12, m, ampm)
    }
}
