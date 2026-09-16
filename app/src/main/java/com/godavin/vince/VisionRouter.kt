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
 * - Groq: "meta-llama/llama-4-scout-17b-16e-instruct" - Groq's
 *   production-tier vision model. (The first version of this used
 *   qwen/qwen3.6-27b, which turned out to be a preview-only model many
 *   accounts don't have access to - confirmed broken via a real 404
 *   from Vincent's device - switched to this more broadly-available one.)
 * - OpenRouter: found DYNAMICALLY via OpenAiCompatibleClient.
 *   findFreeVisionModel() rather than a hardcoded slug - OpenRouter's
 *   specific free vision model names change often enough that
 *   hardcoding one already broke twice (proven on-device). Asking
 *   OpenRouter's own live model list which vision model is currently
 *   free is self-correcting instead of another guess.
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
                model = "meta-llama/llama-4-scout-17b-16e-instruct",
                question = question,
                imageBase64 = imageBase64,
                providerLabel = "Groq vision"
            )
            result.onSuccess { return result }
            attempts.add("Groq vision: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        if (openRouterKey.isNotBlank()) {
            val model = OpenAiCompatibleClient.findFreeVisionModel(openRouterKey)
                ?: "meta-llama/llama-3.2-11b-vision-instruct:free" // last-resort guess if discovery itself fails
            val result = OpenAiCompatibleClient.sendMessageWithImage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = model,
                question = question,
                imageBase64 = imageBase64,
                providerLabel = "OpenRouter vision"
            )
            result.onSuccess { return result }
            attempts.add("OpenRouter vision ($model): ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        if (attempts.isEmpty()) {
            return Result.failure(IllegalStateException(
                "No API keys saved for vision - add Gemini, Groq, or OpenRouter in Settings."
            ))
        }

        return Result.failure(Exception("All vision providers failed:\n" + attempts.joinToString("\n")))
    }
}
