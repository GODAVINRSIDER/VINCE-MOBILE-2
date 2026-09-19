package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Real-time web search, via Tavily - a search API built specifically for
 * AI agents (returns clean extracted text, not raw HTML to parse). This
 * is the actual fix for "how does VINCE stay up to date": date/time
 * awareness (already fixed) tells it WHEN it is; this tells it WHAT'S
 * actually happening, by fetching real current web results and handing
 * them to the AI as context before it answers - the same retrieval
 * pattern that makes Claude itself able to answer current-events
 * questions, rather than relying on any model's frozen training data.
 *
 * Deliberately narrow trigger detection (needsSearch) rather than
 * searching on every message - keeps it fast/free for ordinary
 * conversation and only reaches for the network when a question
 * actually sounds like it needs current information.
 */
object WebSearchTool {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val TRIGGER_PHRASES = listOf(
        "latest", "current", "currently", "recent", "recently", "this week",
        "this month", "today's", "right now", "as of now", "up to date",
        "what's new", "search for", "search the web", "look up", "find out about",
        "news about", "who is the current", "what is the current", "nowadays"
    )

    fun needsSearch(text: String): Boolean {
        val lower = text.lowercase()
        return TRIGGER_PHRASES.any { lower.contains(it) }
    }

    /** Returns a compact block of real web results (title + short
     * excerpt per result) ready to hand to the AI as extra context - not
     * a final answer itself, since the AI still needs to read and
     * synthesize it into a natural reply.
     *
     * [depth] "basic" (default, fast) or "advanced" (Tavily digs deeper
     * into each page's actual content, slower/more expensive - used by
     * DeepResearch.kt for genuine research requests, not ordinary
     * "what's the latest on X" lookups). [maxResults] lets DeepResearch
     * pull more sources per sub-query than an ordinary lookup needs. */
    suspend fun search(
        apiKey: String,
        query: String,
        depth: String = "basic",
        maxResults: Int = 4
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("No Tavily API key saved."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("api_key", apiKey)
                    put("query", query)
                    put("max_results", maxResults)
                    put("search_depth", depth)
                }
                val body = requestJson.toString().toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url("https://api.tavily.com/search")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Tavily error (${response.code}): ${responseBody.take(150)}")
                        )
                    }

                    val json = JSONObject(responseBody)
                    val results = json.optJSONArray("results")
                    if (results == null || results.length() == 0) {
                        return@withContext Result.success("No web results found for this query.")
                    }

                    val summary = StringBuilder()
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val title = item.optString("title")
                        val content = item.optString("content").take(if (depth == "advanced") 800 else 300)
                        summary.append("- $title: $content\n")
                    }
                    Result.success(summary.toString().trim())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
