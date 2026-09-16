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

    // Fix - OpenRouter's specific free-model slugs (and Groq's, to a
    // lesser extent) get retired/renamed often enough that hardcoding
    // one is genuinely fragile - already proven twice (nvidia/nemotron-
    // nano-12b-v2-vl:free simply didn't exist). Instead of guessing a
    // third name, this asks OpenRouter's own live model list which
    // vision-capable model is currently free, and uses whichever one
    // comes back - self-correcting instead of hardcoded. Cached for an
    // hour so this doesn't add a request to every single vision call.
    private var cachedFreeVisionModel: String? = null
    private var cacheTimestamp: Long = 0
    private const val CACHE_TTL_MS = 60 * 60 * 1000L

    suspend fun findFreeVisionModel(apiKey: String): String? {
        val now = System.currentTimeMillis()
        if (cachedFreeVisionModel != null && (now - cacheTimestamp) < CACHE_TTL_MS) {
            return cachedFreeVisionModel
        }
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("https://openrouter.ai/api/v1/models")
                    .addHeader("Authorization", "Bearer $apiKey")
                    .get()
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext null
                    val body = response.body?.string().orEmpty()
                    val data = JSONObject(body).optJSONArray("data") ?: return@withContext null
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
                        if (hasImage) {
                            cachedFreeVisionModel = id
                            cacheTimestamp = now
                            return@withContext id
                        }
                    }
                    null
                }
            } catch (e: Exception) {
                null
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

        // Fix - retry once on a genuinely transient failure (timeout,
        // dropped connection) before giving up on this provider and
        // falling through to the next one. A flaky half-second network
        // hiccup shouldn't cost a whole provider attempt the way a real
        // quota/auth error should.
        var lastError: Exception? = null
        repeat(2) { attempt ->
            try {
                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        return Result.failure(
                            Exception("$providerLabel error (${response.code}): ${responseBody.take(200)}")
                        )
                    }

                    val reply = parseReply(responseBody)
                    return if (reply != null) {
                        Result.success(reply)
                    } else {
                        Result.failure(Exception("$providerLabel responded with no readable text."))
                    }
                }
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
