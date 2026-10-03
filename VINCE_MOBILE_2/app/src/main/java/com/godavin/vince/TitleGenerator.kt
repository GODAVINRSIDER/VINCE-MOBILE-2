package com.godavin.vince

import android.content.Context

/**
 * Generates a short, summary-style title for a new chat thread after
 * its first exchange - "Gold 15m Breakout Setup" instead of the raw
 * first sentence typed, matching what Vincent asked for. Deliberately
 * separate from BrainRouter.sendMessage: this is a trivial, cheap task
 * that doesn't need the persona tone, memory block, or conversation
 * history riding along - just the bare exchange and a tight instruction.
 *
 * Same Gemini -> Groq -> OpenRouter fallback discipline as everywhere
 * else, skipping a provider with no key saved.
 */
object TitleGenerator {

    suspend fun generateTitle(context: Context, userMessage: String, aiReply: String): String? {
        val prompt = "Summarize the topic of this exchange in 3 to 6 words, title case, " +
            "no punctuation, no quotation marks - just the words, nothing else, nothing " +
            "explaining what you're doing.\n\nUser: $userMessage\nAssistant: $aiReply"

        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            GeminiClient.sendMessage(geminiKey, prompt).getOrNull()?.let { return clean(it) }
        }

        val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
        if (groqKey.isNotBlank()) {
            OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "openai/gpt-oss-120b",
                userMessage = prompt,
                providerLabel = "Groq"
            ).getOrNull()?.let { return clean(it) }
        }

        val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (openRouterKey.isNotBlank()) {
            OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = "openrouter/free",
                userMessage = prompt,
                providerLabel = "OpenRouter"
            ).getOrNull()?.let { return clean(it) }
        }

        return null
    }

    private fun clean(raw: String): String {
        return raw.trim()
            .trim('"', '\'', '.', '\n')
            .lines()
            .first()
            .take(60)
    }
}
