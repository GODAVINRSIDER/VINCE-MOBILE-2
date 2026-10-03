package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/**
 * Pulls the current week's high-impact economic calendar from
 * ForexFactory's own public JSON feed - the exact same source PC-VINCE's
 * news_api.py already uses. No API key, no signup. As of mid-2026 this
 * feed is still what professional ForexFactory-powered trading plugins
 * are built on, so it's reused here rather than guessing at an
 * alternative.
 */
object NewsTools {
    private const val CALENDAR_URL = "https://nfs.faireconomy.media/ff_calendar_thisweek.json"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    suspend fun getUpcomingHighImpact(limit: Int = 3): Result<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url(CALENDAR_URL)
                    .addHeader("User-Agent", "Mozilla/5.0")
                    .build()

                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Economic calendar error (${response.code})")
                        )
                    }

                    val body = response.body?.string().orEmpty()
                    val events = JSONArray(body)
                    val now = Date()
                    val isoParser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US)

                    val upcoming = mutableListOf<Pair<Date, String>>()
                    for (i in 0 until events.length()) {
                        val e = events.getJSONObject(i)
                        if (e.optString("impact") != "High") continue

                        val eventTime = try {
                            isoParser.parse(e.optString("date")) ?: continue
                        } catch (ex: Exception) {
                            continue
                        }
                        if (eventTime.before(now)) continue

                        val country = e.optString("country")
                        val title = e.optString("title")
                        upcoming.add(eventTime to "$country $title")
                    }

                    upcoming.sortBy { it.first }
                    val top = upcoming.take(limit)

                    if (top.isEmpty()) {
                        return@withContext Result.success("No high-impact events found for the rest of this week.")
                    }

                    val formatted = top.joinToString(". ") { (time, label) ->
                        val hoursAway = (time.time - now.time) / (1000.0 * 60 * 60)
                        val when_ = when {
                            hoursAway < 1 -> "in ${(hoursAway * 60).toInt()} minutes"
                            hoursAway < 24 -> "in about ${hoursAway.toInt()} hours"
                            else -> "in about ${(hoursAway / 24).toInt()} days"
                        }
                        "$label $when_"
                    }

                    Result.success("Upcoming high-impact events: $formatted.")
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
