package com.godavin.vince

import android.util.Base64

/**
 * Vision fallback chain, same discipline as BrainRouter's text chain:
 * tries Gemini's vision API first (best quality, what's always been
 * used), and if that fails - quota hit, timeout, whatever - falls
 * through automatically to Groq's vision model, then OpenRouter's free
 * vision model, instead of photo/screen analysis just dying the moment
 * Gemini alone is unavailable. A provider with no key saved is skipped
 * silently, same as BrainRouter.
 *
 * Model choice notes:
 * - Groq: "qwen/qwen3.6-27b" is Groq's current vision-capable model
 *   (free-tier eligible as of when this was built - Groq's model
 *   lineup shifts fairly often, so if this specific name ever gets
 *   retired, check console.groq.com/docs/vision for the replacement).
 * - OpenRouter: "nvidia/nemotron-nano-12b-v2-vl:free" is a genuinely
 *   free vision-capable model on OpenRouter (no credits needed) - same
 *   "OpenRouter's exact free model rotates" caveat as the text chain's
 *   "openrouter/free" router already handles for text, but there isn't
 *   an equivalent auto-routing alias for vision specifically yet, so
 *   this one's a named model rather than a router alias.
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
            val result = OpenAiCompatibleClient.sendMessageWithImage(
                baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "qwen/qwen3.6-27b",
                question = question,
                imageBase64 = imageBase64,
                providerLabel = "Groq vision"
            )
            result.onSuccess { return result }
            attempts.add("Groq vision: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        if (openRouterKey.isNotBlank()) {
            val result = OpenAiCompatibleClient.sendMessageWithImage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = "nvidia/nemotron-nano-12b-v2-vl:free",
                question = question,
                imageBase64 = imageBase64,
                providerLabel = "OpenRouter vision"
            )
            result.onSuccess { return result }
            attempts.add("OpenRouter vision: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        if (attempts.isEmpty()) {
            return Result.failure(IllegalStateException(
                "No API keys saved for vision - add Gemini, Groq, or OpenRouter in Settings."
            ))
        }

        return Result.failure(Exception("All vision providers failed:\n" + attempts.joinToString("\n")))
    }
}
