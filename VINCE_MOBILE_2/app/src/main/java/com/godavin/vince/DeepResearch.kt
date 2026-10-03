package com.godavin.vince

import android.content.Context

/**
 * Fix - a single Tavily search is a lookup, not research. Vincent's
 * complaint was accurate: asking about prop firm rules got a shallow
 * single-pass answer because that's literally all WebSearchTool did -
 * one query, four snippets, done. Real research means several angles:
 * search, see what's actually there, search the specific gaps, THEN
 * synthesize - so this asks the AI itself to break the topic into a
 * few concrete sub-questions first, runs a real "advanced"-depth Tavily
 * search on each one, and combines all of it into one larger context
 * block for the final answer - genuinely more thorough, not just a
 * relabeled single search.
 */
object DeepResearch {

    private val TRIGGER_PHRASES = listOf(
        "research ", "deep dive", "look into", "find out everything about",
        "investigate", "detailed research on", "do research on", "dig into"
    )

    fun isResearchRequest(text: String): Boolean {
        val lower = text.lowercase()
        return TRIGGER_PHRASES.any { lower.contains(it) }
    }

    /** Returns a combined block of real search results across several
     * angles on [topic], or null if sub-query generation or every search
     * failed (caller falls back to an ordinary single search in that
     * case rather than answering with nothing). */
    suspend fun research(context: Context, topic: String): String? {
        val tavilyKey = ApiKeyStore.getKey(context, Provider.TAVILY)
        if (tavilyKey.isBlank()) return null

        val subQueryPrompt = "I need to research this: \"$topic\". List exactly 3 specific, " +
            "different search queries that together would cover it thoroughly - different " +
            "angles (e.g. official rules/requirements, specifics/numbers, recent changes or " +
            "comparisons). One query per line, nothing else, no numbering, no explanation."

        val subQueriesRaw = quickAsk(context, subQueryPrompt) ?: return null
        val subQueries = subQueriesRaw.lines()
            .map { it.trim().trim('-', '*', '.', ' ') }
            .filter { it.isNotBlank() }
            .take(3)
        if (subQueries.isEmpty()) return null

        val combined = StringBuilder()
        for (query in subQueries) {
            val result = WebSearchTool.search(tavilyKey, query, depth = "advanced", maxResults = 4)
            result.getOrNull()?.let { combined.append("Research angle: $query\n$it\n\n") }
        }

        return combined.toString().trim().ifBlank { null }
    }

    /** Same minimal Gemini->Groq->OpenRouter fallback as TitleGenerator -
     * a trivial planning step, doesn't need persona/memory context. */
    private suspend fun quickAsk(context: Context, prompt: String): String? {
        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            GeminiClient.sendMessage(geminiKey, prompt).getOrNull()?.let { return it }
        }
        val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
        if (groqKey.isNotBlank()) {
            OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "openai/gpt-oss-120b",
                userMessage = prompt,
                providerLabel = "Groq"
            ).getOrNull()?.let { return it }
        }
        val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (openRouterKey.isNotBlank()) {
            OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = "openrouter/free",
                userMessage = prompt,
                providerLabel = "OpenRouter"
            ).getOrNull()?.let { return it }
        }
        return null
    }
}
