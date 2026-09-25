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
        "this month", "this year", "today's", "today", "tonight", "yesterday",
        "last night", "last week", "last month", "this season", "right now",
        "as of now", "up to date", "still the", "is still", "still president",
        "still ceo", "just happened", "just announced", "breaking",
        "update on", "status of", "what's new", "search for", "search the web",
        "look up", "find out about", "news about", "who is the current",
        "what is the current", "nowadays"
    )

    // Fix - Vincent's real-world case: "did Trump meet Xi yesterday" and
    // "is Trump the current president" both slipped through the OLD trigger
    // list ("yesterday" wasn't in it at all, and the political-entity
    // question didn't happen to contain the literal word "current" every
    // time), so VINCE answered confidently from stale 2024 training data
    // instead of ever reaching for Tavily. These are a second, independent
    // check - a message matching ANY of TRIGGER_PHRASES or NEWS_KEYWORDS
    // or containing a 2024+ year number triggers a real search. Deliberately
    // wide here: for questions about officeholders, elections, deaths, wars,
    // summits, results - being wrong with confidence is worse than one
    // extra cheap Tavily call.
    private val NEWS_KEYWORDS = listOf(
        "president", "prime minister", "ceo of", "who won", "election",
        "elected", "resigned", "resignation", "died", "passed away", "war in",
        "invasion", "summit", "met with", "meeting between", "ceasefire",
        "score", "result of", "who is now", "what happened to"
    )

    private val YEAR_PATTERN = Regex("\\b20(2[4-9]|3[0-9])\\b")

    fun needsSearch(text: String): Boolean {
        val lower = text.lowercase()
        if (TRIGGER_PHRASES.any { lower.contains(it) }) return true
        if (NEWS_KEYWORDS.any { lower.contains(it) }) return true
        if (YEAR_PATTERN.containsMatchIn(lower)) return true
        return false
    }

    private val QUESTION_STARTERS = listOf(
        "who ", "who's", "what ", "what's", "when ", "when's", "where ",
        "why ", "how ", "is ", "are ", "was ", "were ", "does ", "do ",
        "did ", "will ", "can ", "could ", "has ", "have ", "should "
    )

    /** Cheap check for "this is phrased as a genuine question" - not a
     * search decision by itself, just gates whether aiNeedsSearch below
     * is worth even calling. */
    fun looksLikeQuestion(text: String): Boolean {
        val trimmed = text.trim().lowercase()
        if (trimmed.contains("?")) return true
        return QUESTION_STARTERS.any { trimmed.startsWith(it) }
    }

    // Fix - found via direct testing: "when did they meet recently?"
    // worked (keyword match), but the exact same question WITHOUT
    // "recently" - "when did Trump meet Xi" - still answered wrong. The
    // AI self-classifier below has a structural blind spot: asking a
    // frozen-training model "would you need a live check for this?" only
    // works if the model already suspects it's missing something. A
    // question that looks, from inside its own frozen 2024 knowledge,
    // like an already-fully-answerable historical fact (it confidently
    // "knows" about 2017/2019 meetings) gives it no signal that anything
    // happened since - so it answers its own gate-check "NO" and walks
    // straight into the same stale answer. A model can't reliably judge
    // what it doesn't know it doesn't know. So this is no longer used as
    // the search gate - see searchNeeded in BrainRouter.kt, which now
    // fires on any real question directly instead of asking the model to
    // self-assess. Left here only in case a narrower, cheaper gate is
    // ever wanted again later.
    suspend fun aiNeedsSearch(context: android.content.Context, text: String): Boolean {
        val prompt = "Question: \"$text\"\n\n" +
            "Would answering this correctly and completely require checking live/current " +
            "information - recent events, who currently holds a position, something that " +
            "happened recently, an ongoing situation, current prices or promotions, or " +
            "anything that could have changed after a 2024 training cutoff? Reply with " +
            "exactly one word: YES or NO."
        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        val answer = if (geminiKey.isNotBlank()) {
            GeminiClient.sendMessage(geminiKey, prompt).getOrNull()
        } else null
            ?: run {
                val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
                if (groqKey.isNotBlank()) {
                    OpenAiCompatibleClient.sendMessage(
                        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                        apiKey = groqKey,
                        model = "openai/gpt-oss-120b",
                        userMessage = prompt,
                        providerLabel = "Groq"
                    ).getOrNull()
                } else null
            }
        return answer?.trim()?.startsWith("YES", ignoreCase = true) == true
    }

    /** Returns a compact block of real web results (title + published date
     * + short excerpt per result) ready to hand to the AI as extra
     * context - not a final answer itself, since the AI still needs to
     * read and synthesize it into a natural reply.
     *
     * Fix - Vincent's "when did they meet recently?" case exposed a real
     * gap here: that query DID reach this function (Tavily confirmed 43
     * credits used), but ran with topic "general" (SEO-ranked), which
     * happily returned well-established 2017-2019 pages that outrank a
     * few-day-old article on pure relevance. Two changes fix the actual
     * retrieval, not just the trigger detection: (1) [days] - when set
     * (only valid alongside topic "news"), tells Tavily to hard-filter
     * OUT any result older than that many days, rather than just
     * preferring newer ones - old pages can no longer sneak through by
     * out-ranking a recent one. (2) each result now shows its own
     * published date inline, so even if an older result does slip in,
     * the model can see it's stale and weigh it correctly instead of
     * treating every result as equally "current."
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
        maxResults: Int = 5,
        topic: String = "general",
        days: Int? = null
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
                    put("topic", topic)
                    put("include_answer", false)
                    // days only applies when topic is "news" per Tavily's API -
                    // this is the hard recency cutoff, not just a ranking hint.
                    if (topic == "news" && days != null) {
                        put("days", days)
                    }
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
                        // Fix - previously returned a filler string that got
                        // wrapped in "these are ground truth" prefix text,
                        // which is confusing/contradictory for the model to
                        // receive. An empty success return here now flows
                        // into BrainRouter's existing isBlank() check the
                        // same way a failed search does, so a real zero-
                        // results case (a real risk now with the days
                        // recency filter) correctly triggers the honest
                        // "can't confirm" hedge instead of odd filler text.
                        return@withContext Result.success("")
                    }

                    val summary = StringBuilder()
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val title = item.optString("title")
                        val content = item.optString("content").take(if (depth == "advanced") 800 else 300)
                        val published = item.optString("published_date").takeIf { it.isNotBlank() }
                        val dateTag = if (published != null) "[published: $published] " else ""
                        summary.append("- $dateTag$title: $content\n")
                    }
                    Result.success(summary.toString().trim())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
