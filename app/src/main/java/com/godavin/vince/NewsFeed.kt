package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

data class CalEvent(val timeMillis: Long, val country: String, val title: String)

/**
 * Structured version of the same ForexFactory weekly feed NewsTools uses
 * for its spoken answer - the alerts and briefing need real event times,
 * not a pre-formatted sentence. Short timeouts because it can run inside
 * a broadcast receiver with a hard time budget.
 */
object NewsFeed {
    private const val URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    suspend fun fetchHighImpact(): Result<List<CalEvent>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder().url(URL).addHeader("User-Agent", "Mozilla/5.0").build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure<List<CalEvent>>(Exception("Calendar error (${response.code})"))
                }
                val events = JSONArray(response.body?.string().orEmpty())
                val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)
                val out = mutableListOf<CalEvent>()
                for (i in 0 until events.length()) {
                    val e = events.getJSONObject(i)
                    if (e.optString("impact") != "High") continue
                    val time = try { parser.parse(e.optString("date"))?.time } catch (ex: Exception) { null } ?: continue
                    out.add(CalEvent(time, e.optString("country"), e.optString("title")))
                }
                out.sortBy { it.timeMillis }
                Result.success(out.toList())
            }
        } catch (e: Exception) {
            Result.failure<List<CalEvent>>(e)
        }
    }

    /** USD/EUR/GBP/JPY by default (what moves gold, indices and the majors); all currencies if the user opts in. */
    fun matchesPrefs(context: android.content.Context, event: CalEvent): Boolean {
        if (AlertPrefs.isOn(context, AlertPrefs.K_NEWS_ALL_CCY)) return true
        return event.country.uppercase() in setOf("USD", "EUR", "GBP", "JPY")
    }
}
