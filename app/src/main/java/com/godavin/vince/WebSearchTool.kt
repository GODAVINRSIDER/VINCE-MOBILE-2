package com.godavin.vince

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /** Sites used by the most recent search - only used to narrate progress. */
    @Volatile var lastSources: List<String> = emptyList()
    /** v2.11 - every site used by the searches of the current reply (deep research runs several). Cleared per reply. */
    val traceHosts = java.util.concurrent.CopyOnWriteArrayList<String>()

    // v2.5 - an ordinary lookup (basic depth) must never hold up the reply for
    // long: connect in 3s, whole call done in 4s, otherwise it counts as "could
    // not pull live data" and VINCE answers anyway. Deep research (advanced
    // depth) keeps the slower client above.
    private val fastClient = client.newBuilder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(4, TimeUnit.SECONDS)
        .callTimeout(4, TimeUnit.SECONDS)
        .build()

    // v2.10.1 - when the user explicitly asks to search ("search about it online"), give the call room on a slow mobile
    // network instead of the 4s fast-path - a timed-out search silently becomes "answer from memory", which is how invented
    // answers happen.
    private val patientClient = client.newBuilder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(9, TimeUnit.SECONDS)
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

    // ---- v2.3: FORCED search. No model discretion. ----
    // Any message containing one of these words ALWAYS goes to Tavily.
    private val FORCE_REGEX = Regex(
        "\\b(search|look\\s?up|look for|find out|research|google|check online|online|latest|" +
            "current|currently|recent|recently|right now|now|today|tonight|tomorrow|this week|" +
            "this month|news|update|updates|outlook|forecast|what'?s happening|trending|" +
            "price of|live|breaking|anything new|what'?s new|so far|retry|try again|search again|rerun|re-run)\\b",
        RegexOption.IGNORE_CASE
    )

    /** True when the message contains a word that demands live info. */
    fun mustSearch(text: String): Boolean = FORCE_REGEX.containsMatchIn(text) || needsSearch(text)

    private val SMALL_TALK = setOf(
        "hi", "hello", "hey", "yo", "thanks", "thank you", "thx", "ok", "okay", "k", "yes", "no",
        "yeah", "yep", "nope", "cool", "nice", "great", "good", "alright", "sure", "got it", "lol",
        "haha", "bye", "goodbye", "good morning", "good afternoon", "good evening", "good night",
        "hey vince", "hey clara", "hey davina", "hi vince", "hi clara", "hi davina", "hello vince",
        "hello clara", "hello davina", "wow", "perfect", "awesome", "right", "hmm", "continue", "go on"
    )

    private val IMPERATIVE_STARTERS = listOf(
        "analyze", "analyse", "give me", "tell me", "show me", "explain", "compare", "suggest",
        "recommend", "summarize", "summarise", "break down", "plan", "review", "evaluate",
        "what do you think", "should i", "advise", "help me", "walk me"
    )

    /** Wide default: every real question or substantive request searches.
     * Only tiny small talk skips it. */
    fun shouldSearch(text: String): Boolean {
        val cleaned = text.trim().lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        if (cleaned.isBlank()) return false
        if (cleaned in SMALL_TALK) return false
        if (mustSearch(text)) return true
        if (looksLikeQuestion(text)) return true
        if (IMPERATIVE_STARTERS.any { cleaned.startsWith(it) }) return true
        return cleaned.split(" ").size >= 5
    }

    // v2.10.1 - "search about it online and tell me what you find" used to be sent to Tavily AS IS (10 words, so the old
    // "short follow-up" glue never applied) - a query with no subject, which returns random articles that the model then
    // dutifully summarised. Now the command words are stripped and, when what is left has no real subject (or points back
    // with it / that / they ...), the previous message is used as the subject.
    private val GREETING_PREFIX = Regex("^(hi|hello|hey|yo)\\b( (vince|clara|davina))?[,!.:]*\\s*", RegexOption.IGNORE_CASE)
    private val COMMAND_FLUFF = listOf(
        Regex("\\b(please|kindly)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(can|could|would) you\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(search|look\\s?up|google|find out|check)( (about|for|up|on|into))?\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(online|on the web|on the internet|on google)\\b", RegexOption.IGNORE_CASE),
        Regex("\\bwhat you (find|found|get|see)\\b", RegexOption.IGNORE_CASE),
        Regex("\\b(and )?(then )?(tell|let) me( know)?\\b", RegexOption.IGNORE_CASE)
    )
    private val REFERENTS = setOf("it", "its", "this", "that", "they", "them", "their", "those", "these", "he", "she", "him", "her")
    private val FILLER = setOf("the", "a", "an", "and", "or", "of", "to", "me", "you", "about", "on", "in", "is", "are", "was",
        "were", "do", "does", "did", "for", "with", "please", "up", "so", "what", "whats", "now", "look", "find", "show")

    /** Builds the Tavily query: strips the swipe-reply wrapper, greetings and "search ... online" boilerplate, and when the
     * message has no subject of its own (or only "it/that/they"), uses the previous message as the subject. */
    fun buildQuery(userMessage: String, history: List<ChatMessage>): String {
        val raw = userMessage.replace(Regex("^\\(Replying to earlier message: \"[^\"]*\"\\)\\s*"), "").trim()
        val q = GREETING_PREFIX.replace(raw, "").trim().ifBlank { raw }
        var core = q
        for (r in COMMAND_FLUFF) core = r.replace(core, " ")
        core = core.replace(Regex("[^A-Za-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()
        val words = core.split(" ").filter { it.isNotBlank() }
        val content = words.filter { it.lowercase() !in FILLER && it.lowercase() !in REFERENTS }
        val hasReferent = words.any { it.lowercase() in REFERENTS }
        val needsContext = content.size < 2 || (hasReferent && words.size <= 12) || q.split(Regex("\\s+")).size <= 4
        if (!needsContext) return core.ifBlank { q }.take(300)

        val prev = history.filter { it.fromUser }.map { GREETING_PREFIX.replace(it.text.trim(), "").trim() }
            .lastOrNull { it.isNotBlank() && !it.equals(q, ignoreCase = true) && !it.equals(raw, ignoreCase = true) }
        if (prev.isNullOrBlank()) return q.take(300)
        val extra = if (content.size >= 1) " " + content.joinToString(" ") else ""
        return (prev.take(200) + extra).take(300)
    }

    /** Live search. Event-style questions (news, who holds a post, results) go news-first with a hard recency
     * filter; everything else (fares, prices, how-tos, places) goes straight to the general index, because the
     * news index only holds news articles and returns junk or nothing for evergreen questions. Every result must
     * mention the key terms of the query (see anchorsOf), otherwise it is dropped - an empty return then flows
     * into BrainRouter's honest "could not verify" path instead of summarising unrelated pages. */
    suspend fun liveSearch(
        apiKey: String,
        query: String,
        days: Int,
        patient: Boolean = false,
        newsFirst: Boolean = true
    ): Result<String> {
        val anchors = anchorsOf(query)
        if (!newsFirst) {
            return search(apiKey, query, topic = "general", patient = patient, anchors = anchors)
        }
        val started = System.currentTimeMillis()
        val news = search(apiKey, query, topic = "news", days = days, patient = patient, anchors = anchors)
        // v2.5 - a failed or timed-out news search is returned as-is (no second slow call); only a genuine
        // "zero results" falls back to a general search, and only if the first call was quick.
        if (news.isFailure) return news
        if (!news.getOrNull().isNullOrBlank()) return news
        if (!patient && System.currentTimeMillis() - started > 2500) return news
        val general = search(apiKey, query, topic = "general", patient = patient, anchors = anchors)
        return if (general.isSuccess) general else news
    }

    // v2.13.1 - is this question about an event / who-holds-what / a result (news-first), or evergreen (fares,
    // prices, how-tos, places)? Words like "online" or "search" say nothing about it, so they are not used here.
    private val EVENT_HINTS = listOf(
        "latest", "recent", "recently", "today", "tonight", "yesterday", "breaking", "this week", "this month",
        "last night", "last week", "last month", "right now", "just happened", "just announced", "news"
    )

    fun isEventQuery(text: String): Boolean {
        val lower = text.lowercase()
        return NEWS_KEYWORDS.any { lower.contains(it) } || EVENT_HINTS.any { lower.contains(it) } ||
            YEAR_PATTERN.containsMatchIn(lower)
    }

    // ---- v2.13.1: clean query + relevance guard ----
    // Real failure (Oct 2026): a boda-fare question was searched as "no search how much can it cost as fare
    // not buying price have" - two chat messages glued together by regex - and the results were travel and
    // cloud sites that were then shown as sources. Two fixes: (1) one tiny AI call rewrites the conversation
    // into a clean standalone query; (2) every result must mention the key terms of that query or it is dropped.
    private val GENERIC_WORDS = setOf(
        "much", "many", "cost", "costs", "price", "prices", "fare", "fares", "this", "that", "with", "from",
        "what", "when", "where", "which", "does", "will", "would", "could", "should", "have", "about", "there",
        "their", "they", "been", "being", "into", "over", "than", "then", "some", "like", "just", "only", "also",
        "long", "take", "takes", "best", "good", "need", "want", "trip", "ride", "today", "latest", "current",
        "currently", "recent", "recently", "news", "update", "right", "week", "month", "year", "yesterday",
        "tomorrow", "tell", "show", "find", "search", "online", "how", "someone", "something", "going", "get"
    )

    /** The words a relevant page must mention: query words of 4+ letters that are not generic, singularised. */
    fun anchorsOf(query: String): Set<String> {
        val out = LinkedHashSet<String>()
        for (w in query.lowercase().split(Regex("[^a-z0-9]+"))) {
            if (w.length < 4 || w in GENERIC_WORDS) continue
            out.add(if (w.length >= 5 && w.endsWith("s")) w.dropLast(1) else w)
            if (out.size >= 6) break
        }
        return out
    }

    /** True when the text mentions enough of the anchors (1 if there are 3 or fewer, otherwise 2). */
    fun isRelevantText(text: String, anchors: Set<String>): Boolean {
        if (anchors.isEmpty()) return true
        val lower = text.lowercase()
        val need = if (anchors.size <= 3) 1 else 2
        return anchors.count { lower.contains(it) } >= need
    }

    private fun needsRewrite(raw: String, history: List<ChatMessage>): Boolean {
        val words = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size > 6) return true
        if (words.any { it.lowercase().trim('.', ',', '?', '!') in REFERENTS }) return true
        if (COMMAND_FLUFF.any { it.containsMatchIn(raw) }) return true
        return history.isNotEmpty() && words.size <= 4
    }

    /** Clean standalone search query. Falls back to the regex builder when no AI brain answers within 3s. */
    suspend fun smartQuery(context: Context, userMessage: String, history: List<ChatMessage>): String {
        val fallback = buildQuery(userMessage, history)
        val raw = userMessage.replace(Regex("^\\(Replying to earlier message: \"[^\"]*\"\\)\\s*"), "").trim()
        if (!needsRewrite(raw, history)) return fallback
        return try {
            val recent = history.takeLast(4).joinToString("\n") {
                (if (it.fromUser) "User: " else "Assistant: ") + it.text.trim().take(200)
            }
            val prompt = "Rewrite the user's LATEST message as ONE short standalone web search query (max 12 words). " +
                "Use the earlier messages only to fill in a missing subject or place. Keep names, places and numbers. " +
                "Drop chat words (no, search, can you, please, rerun, tell me). Keep the exact intent: a fare or " +
                "cost to pay for a service is not a purchase price. Reply with only the query.\n\n" +
                (if (recent.isNotBlank()) "Earlier messages:\n$recent\n\n" else "") +
                "Latest message: $raw"
            val answer = withTimeoutOrNull(3000L) {
                val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
                val g = if (geminiKey.isNotBlank()) GeminiClient.sendMessage(geminiKey, prompt).getOrNull() else null
                g ?: run {
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
            }
            val q = answer?.trim()?.lines()?.firstOrNull()?.trim()?.trim('"', '\'', '.', '`')?.replace(Regex("^(query|search query)\\s*:\\s*", RegexOption.IGNORE_CASE), "")?.trim().orEmpty()
            if (q.length in 6..200) q else fallback
        } catch (e: Exception) {
            fallback
        }
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
        days: Int? = null,
        patient: Boolean = false,
        anchors: Set<String> = emptySet()
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

                (if (depth == "basic") (if (patient) patientClient else fastClient) else client).newCall(request).execute().use { response ->
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
                    val hosts = mutableListOf<String>()
                    var kept = 0
                    for (i in 0 until results.length()) {
                        val item = results.getJSONObject(i)
                        val title = item.optString("title")
                        val rawContent = item.optString("content")
                        // v2.13.1 - drop results that do not mention the key terms of the query.
                        if (!isRelevantText(title + " " + rawContent + " " + item.optString("url"), anchors)) continue
                        kept++
                        // v2.6 - remember the sites so VINCE can say "reading reuters.com" (only sites actually used)
                        item.optString("url").takeIf { it.isNotBlank() }?.let { u ->
                            val h = u.substringAfter("://").substringBefore("/").removePrefix("www.")
                            if (h.isNotBlank() && h !in hosts) hosts.add(h)
                        }
                        val content = rawContent.take(if (depth == "advanced") 800 else 300)
                        val published = item.optString("published_date").takeIf { it.isNotBlank() }
                        val dateTag = if (published != null) "[published: $published] " else ""
                        summary.append("- $dateTag$title: $content\n")
                    }
                    if (kept == 0) return@withContext Result.success("")
                    lastSources = hosts.take(2)
                    for (h in hosts) if (h !in traceHosts) traceHosts.add(h)
                    Result.success(summary.toString().trim())
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }
}
