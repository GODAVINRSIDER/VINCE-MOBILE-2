package com.godavin.vince

import android.content.Context

/**
 * Tries Gemini first, then Groq, then OpenRouter - falling through
 * automatically on a quota error, timeout, or any other failure, instead
 * of surfacing the first provider's error straight to the chat. This is
 * what actually fixes the "hit my quota limit" problem: one provider
 * being unavailable no longer stops VINCE from answering.
 *
 * A provider with no key saved is skipped silently (not treated as a
 * failure) - Settings decides which providers are even in the rotation.
 * Only if every provider with a saved key fails does this return a
 * combined error, so the chat screen can show something honest instead
 * of pretending everything's fine.
 */
object BrainRouter {

    suspend fun sendMessage(context: Context, userMessage: String): String {
        val attempts = mutableListOf<String>()

        // Stage 13 - prepend durable memory (name, saved facts) so it's
        // present regardless of which chat thread this is, not just
        // whatever happens to be in this thread's own history. None of
        // the provider clients have a separate "system role" wired up
        // yet, so this rides along as part of the message text itself -
        // simple and works identically across all three providers.
        val contextBlock = StructuredMemory.buildContextBlock(context)
        val fullMessage = if (contextBlock.isBlank()) {
            userMessage
        } else {
            "$contextBlock\n\nUser: $userMessage"
        }

        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            val result = GeminiClient.sendMessage(geminiKey, fullMessage)
            result.onSuccess { return it }
            attempts.add("Gemini: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
        if (groqKey.isNotBlank()) {
            val result = OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "openai/gpt-oss-120b",
                userMessage = fullMessage,
                providerLabel = "Groq"
            )
            result.onSuccess { return it }
            attempts.add("Groq: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (openRouterKey.isNotBlank()) {
            val result = OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                // "openrouter/free" is OpenRouter's own router model - it always
                // resolves to whichever specific free model is currently available
                // on their end, rather than us hardcoding one exact free model
                // name that goes stale whenever THAT model gets rotated out
                // (which is exactly what broke here the first time).
                model = "openrouter/free",
                userMessage = fullMessage,
                providerLabel = "OpenRouter"
            )
            result.onSuccess { return it }
            attempts.add("OpenRouter: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        if (attempts.isEmpty()) {
            return "No API keys saved yet - add at least one (Gemini, Groq, or OpenRouter) in Settings."
        }

        return "All providers failed:\n" + attempts.joinToString("\n")
    }
}
