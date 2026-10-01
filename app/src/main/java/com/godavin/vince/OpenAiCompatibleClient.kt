package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Groq and OpenRouter both implement the same "OpenAI-compatible chat
 * completions" request/response shape, just at different base URLs with
 * different model names - so one implementation covers both rather than
 * duplicating near-identical OkHttp/JSON code twice.
 */
object OpenAiCompatibleClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Vision model discovery. Hardcoded model names go stale (OpenRouter's
    // free slugs rotate, and Groq shut down llama-4-scout in July 2026 -
    // that exact name is what produced the 404 on-device). Both providers
    // are asked for their live model list instead, and several candidates
    // are returned so one rate-limited or retired model doesn't kill the
    // whole vision attempt. Cached for 30 minutes.
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private var cachedOrVision: List<String>? = null
    private var orCacheTime: Long = 0
    private var cachedGroqVision: List<String>? = null
    private var groqCacheTime: Long = 0

    /** Up to [limit] free OpenRouter models that accept image input. */
    suspend fun findFreeVisionModels(apiKey: String, limit: Int = 4): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedOrVision
        if (cached != null && (now - orCacheTime) < CACHE_TTL_MS) return cached
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<String>()
                    val body = response.body?.string().orEmpty()
                    val data = JSONObject(body).optJSONArray("data") ?: return@use emptyList<String>()
                    val found = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val model = data.getJSONObject(i)
                        val id = model.optString("id")
                        if (!id.endsWith(":free")) continue
                        val modalities = model.optJSONObject("architecture")
                            ?.optJSONArray("input_modalities")
                        var hasImage = false
                        if (modalities != null) {
                            for (j in 0 until modalities.length()) {
                                if (modalities.optString(j) == "image") hasImage = true
                            }
                        }
                        if (hasImage) found.add(id)
                    }
                    val picked = found.take(limit)
                    if (picked.isNotEmpty()) {
                        cachedOrVision = picked
                        orCacheTime = now
                    }
                    picked
                }
            } catch (e: Exception) {
                emptyList<String>()
            }
        }
    }

    /**
     * Groq's /models list has no modality field, so vision-capable models are
     * recognised by name (scout/maverick/vision/vl first, then qwen as a
     * longer shot - a non-vision model just answers 400 and the router moves
     * on). Empty list = Groq has nothing usable for vision right now, and the
     * router skips it cleanly instead of hitting a dead hardcoded name.
     */
    suspend fun findGroqVisionModels(apiKey: String, limit: Int = 2): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedGroqVision
        if (cached != null && (now - groqCacheTime) < CACHE_TTL_MS) return cached
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://api.groq.com/openai/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@use emptyList<String>()
                    val body = response.body?.string().orEmpty()
                    val data = JSONObject(body).optJSONArray("data") ?: return@use emptyList<String>()
                    val skip = listOf("whisper", "tts", "guard", "safeguard", "orpheus", "embed", "compound", "distil")
                    val ids = mutableListOf<String>()
                    for (i in 0 until data.length()) {
                        val m = data.getJSONObject(i)
                        if (!m.optBoolean("active", true)) continue
                        val id = m.optString("id")
                        if (id.isBlank() || skip.any { id.lowercase().contains(it) }) continue
                        ids.add(id)
                    }
                    val primary = ids.filter { id ->
                        val l = id.lowercase()
                        l.contains("scout") || l.contains("maverick") || l.contains("vision") ||
                            l.contains("-vl") || l.contains("vl-")
                    }
                    val secondary = ids.filter { it.lowercase().contains("qwen") }
                    val picked = (primary + secondary).distinct().take(limit)
                    // Cache even an empty answer so a Groq with no vision model
                    // doesn't cost an extra request on every single image.
                    cachedGroqVision = picked
                    groqCacheTime = now
                    picked
                }
            } catch (e: Exception) {
                emptyList<String>()
            }
        }
    }

    suspend fun sendMessage(
        baseUrl: String,
        apiKey: String,
        model: String,
        userMessage: String,
        providerLabel: String
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("No $providerLabel API key saved."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", userMessage)
                        }
                    ))
                }

                sendRequest(baseUrl, apiKey, requestJson, providerLabel)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * Vision fallback chain - same "OpenAI-compatible" request shape as
     * text, but using the standard multimodal content array (text part +
     * base64 image_url part) that vision-capable models on Groq and
     * OpenRouter both accept. This is what lets photo/screen analysis
     * fall back to Groq/OpenRouter when Gemini's quota is hit, instead
     * of vision having zero fallback the way it used to.
     */
    suspend fun sendMessageWithImage(
        baseUrl: String,
        apiKey: String,
        model: String,
        question: String,
        imageBase64: String,
        providerLabel: String
    ): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("No $providerLabel API key saved."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val contentArray = JSONArray().apply {
                    put(JSONObject().apply {
                        put("type", "text")
                        put("text", question)
                    })
                    put(JSONObject().apply {
                        put("type", "image_url")
                        put("image_url", JSONObject().put(
                            "url", "data:image/jpeg;base64,$imageBase64"
                        ))
                    })
                }
                val requestJson = JSONObject().apply {
                    put("model", model)
                    put("messages", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("content", contentArray)
                        }
                    ))
                }

                sendRequest(baseUrl, apiKey, requestJson, providerLabel)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private val TRANSIENT_CODES = setOf(500, 502, 503, 504)

    private fun sendRequest(
        baseUrl: String,
        apiKey: String,
        requestJson: JSONObject,
        providerLabel: String
    ): Result<String> {
        val body = requestJson.toString()
            .toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(baseUrl)
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body)
            .build()

        // Retry once on a genuinely transient failure (timeout, dropped
        // connection, or a 5xx "provider overloaded") before giving up on
        // this provider. Quota/rate-limit/auth errors (4xx) are NOT retried
        // here - the router moves on to the next model/provider instead.
        var lastError: Exception? = null
        for (attempt in 0..1) {
            try {
                val outcome: Result<String>? = client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val reply = parseReply(responseBody)
                            if (reply != null) Result.success(reply)
                            else Result.failure<String>(Exception("$providerLabel responded with no readable text."))
                        }
                        response.code in TRANSIENT_CODES && attempt == 0 -> {
                            lastError = Exception(
                                "$providerLabel error (${response.code}): ${ApiErrors.short(responseBody)}"
                            )
                            null
                        }
                        else -> Result.failure<String>(
                            Exception("$providerLabel error (${response.code}): ${ApiErrors.short(responseBody)}")
                        )
                    }
                }
                if (outcome != null) return outcome
                Thread.sleep(1500)
            } catch (e: java.io.IOException) {
                lastError = e
                if (attempt == 0) Thread.sleep(500)
            }
        }
        return Result.failure(lastError ?: Exception("$providerLabel request failed."))
    }

    private fun parseReply(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
