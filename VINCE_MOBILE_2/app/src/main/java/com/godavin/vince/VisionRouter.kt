package com.godavin.vince

import android.util.Base64

/**
 * Vision fallback chain: Gemini (several models, with retry) -> Groq ->
 * OpenRouter free models. A provider with no key saved is skipped silently.
 *
 * Fix (Oct 2026): every model name here is now discovered live or tried in
 * a small list, never a single hardcoded guess:
 * - Gemini: retries a 503, then falls through to other flash models (see
 *   GeminiVision).
 * - Groq: its hardcoded llama-4-scout was shut down by Groq on 2026-07-17
 *   (the 404 seen on-device). Now asks Groq's live model list; if Groq has
 *   no vision model at all, it is skipped with a one-line note.
 * - OpenRouter: tries up to 4 free vision models, then OpenRouter's own
 *   "openrouter/free" router, so one rate-limited (429) model no longer
 *   fails the whole provider.
 */
object VisionRouter {

    suspend fun describeImage(
        geminiKey: String,
        groqKey: String,
        openRouterKey: String,
        imageBytes: ByteArray,
        question: String
    ): Result<String> {
        val attempts = mutableListOf<String>()

        if (geminiKey.isNotBlank()) {
            val result = GeminiVision.describeImage(geminiKey, imageBytes, question)
            result.onSuccess { return result }
            attempts.add("Gemini: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        val imageBase64 = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

        if (groqKey.isNotBlank()) {
            val models = OpenAiCompatibleClient.findGroqVisionModels(groqKey)
            if (models.isEmpty()) {
                attempts.add("Groq: no vision-capable model available on this key right now")
            } else {
                var lastError = "failed"
                for (model in models) {
                    val result = OpenAiCompatibleClient.sendMessageWithImage(
                        baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                        apiKey = groqKey,
                        model = model,
                        question = question,
                        imageBase64 = imageBase64,
                        providerLabel = "Groq vision"
                    )
                    result.onSuccess { return result }
                    lastError = result.exceptionOrNull()?.message ?: "failed"
                }
                attempts.add("Groq: $lastError")
            }
        }

        if (openRouterKey.isNotBlank()) {
            val models = OpenAiCompatibleClient.findFreeVisionModels(openRouterKey) + "openrouter/free"
            var lastError = "failed"
            for (model in models) {
                val result = OpenAiCompatibleClient.sendMessageWithImage(
                    baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                    apiKey = openRouterKey,
                    model = model,
                    question = question,
                    imageBase64 = imageBase64,
                    providerLabel = "OpenRouter vision"
                )
                result.onSuccess { return result }
                lastError = result.exceptionOrNull()?.message ?: "failed"
            }
            attempts.add("OpenRouter: $lastError (tried ${models.size} models)")
        }

        if (attempts.isEmpty()) {
            return Result.failure(IllegalStateException(
                "No API keys saved for vision - add Gemini, Groq, or OpenRouter in Settings."
            ))
        }

        return Result.failure(Exception(
            "Every vision provider is busy or unavailable right now - this is usually " +
                "temporary, try again in a minute.\n" +
                attempts.joinToString("\n") { "- " + it.take(160) }
        ))
    }
}
