package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Talks straight to Google's Gemini API using the key saved in ApiKeyStore.
 * Returns Result<String> rather than baking error text into the reply -
 * BrainRouter (see BrainRouter.kt) decides what to do with a failure
 * (fall through to the next provider), rather than this class deciding
 * that on its own like the old single-provider version did.
 */
object GeminiClient {
    private const val MODEL = "gemini-3.6-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Fix - a Gemini 503 "high demand" is usually gone seconds later, so one
    // short retry keeps the answer on Gemini instead of dropping to Groq.
    suspend fun sendMessage(apiKey: String, userMessage: String): Result<String> {
        val first = sendOnce(apiKey, userMessage)
        if (first.isSuccess) return first
        val msg = first.exceptionOrNull()?.message.orEmpty()
        val transient = listOf("(500)", "(502)", "(503)", "(504)").any { msg.contains(it) }
        if (!transient) return first
        delay(1500)
        return sendOnce(apiKey, userMessage)
    }

    private suspend fun sendOnce(apiKey: String, userMessage: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("No Gemini API key saved."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().put(
                                JSONObject().put("text", userMessage)
                            ))
                        }
                    ))
                }

                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$BASE_URL/$MODEL:generateContent?key=$apiKey")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("Gemini error (${response.code}): ${ApiErrors.short(responseBody)}")
                        )
                    }

                    val reply = parseReply(responseBody)
                    if (reply != null) {
                        Result.success(reply)
                    } else {
                        Result.failure(Exception("Gemini responded with no readable text."))
                    }
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }


    // ------------------------------------------------------------------
    // v2.5 - fast chat path (used by BrainRouter's provider race)
    // ------------------------------------------------------------------

    // Short connect timeout so a dead network or provider is noticed in seconds.
    private val fastClient = client.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    // Gemini 3.6 Flash thinks by default, which is most of the old 4-5 second
    // wait. 0 = thinkingLevel "minimal", 1 = thinkingBudget 0, 2 = no setting.
    // If Google rejects a setting with a 400 that mentions thinking, it steps
    // down to the next one automatically and remembers (never both at once).
    @Volatile private var thinkMode = 0

    private fun fastBody(userMessage: String, maxTokens: Int): String {
        val cfg = JSONObject().put("maxOutputTokens", maxTokens)
        when (thinkMode) {
            0 -> cfg.put("thinkingConfig", JSONObject().put("thinkingLevel", "minimal"))
            1 -> cfg.put("thinkingConfig", JSONObject().put("thinkingBudget", 0))
        }
        return JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(JSONObject().put("text", userMessage)))
                }
            ))
            put("generationConfig", cfg)
        }.toString()
    }

    private fun isThinkingRejection(msg: String, mode: Int): Boolean =
        mode < 2 && msg.contains("(400)") && msg.contains("think", ignoreCase = true)

    /** One fast attempt, no sleep-and-retry. Whole reply at once. */
    suspend fun chat(apiKey: String, userMessage: String, maxTokens: Int = 1500): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("No Gemini API key saved."))
        for (attempt in 0..2) {
            val mode = thinkMode
            val r = chatOnce(apiKey, userMessage, maxTokens)
            if (r.isSuccess) return r
            val msg = r.exceptionOrNull()?.message.orEmpty()
            if (!isThinkingRejection(msg, mode)) return r
            if (thinkMode == mode) thinkMode = mode + 1
        }
        return Result.failure(Exception("Gemini rejected the request settings."))
    }

    private suspend fun chatOnce(apiKey: String, userMessage: String, maxTokens: Int): Result<String> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/$MODEL:generateContent")
                .addHeader("x-goog-api-key", apiKey)
                .post(fastBody(userMessage, maxTokens).toRequestBody("application/json".toMediaType()))
                .build()
            HttpStream.run(fastClient, request) { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    Result.failure<String>(
                        Exception("Gemini error (${response.code}): ${ApiErrors.short(responseBody)}")
                    )
                } else {
                    val reply = parseReply(responseBody)
                    if (reply != null) Result.success(reply)
                    else Result.failure(Exception("Gemini responded with no readable text."))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Streaming version: [onDelta] gets each piece of text as it arrives (from a
     * background thread); the full text is returned at the end. */
    suspend fun chatStream(
        apiKey: String,
        userMessage: String,
        maxTokens: Int = 1500,
        onDelta: (String) -> Unit
    ): Result<String> {
        if (apiKey.isBlank()) return Result.failure(IllegalStateException("No Gemini API key saved."))
        for (attempt in 0..2) {
            val mode = thinkMode
            val r = streamOnce(apiKey, userMessage, maxTokens, onDelta)
            if (r.isSuccess) return r
            val msg = r.exceptionOrNull()?.message.orEmpty()
            if (!isThinkingRejection(msg, mode)) return r
            if (thinkMode == mode) thinkMode = mode + 1
        }
        return Result.failure(Exception("Gemini rejected the request settings."))
    }

    private suspend fun streamOnce(
        apiKey: String,
        userMessage: String,
        maxTokens: Int,
        onDelta: (String) -> Unit
    ): Result<String> {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL/$MODEL:streamGenerateContent?alt=sse")
                .addHeader("x-goog-api-key", apiKey)
                .post(fastBody(userMessage, maxTokens).toRequestBody("application/json".toMediaType()))
                .build()
            HttpStream.run(fastClient, request) { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string().orEmpty()
                    return@run Result.failure<String>(
                        Exception("Gemini error (${response.code}): ${ApiErrors.short(errBody)}")
                    )
                }
                val full = StringBuilder()
                val reader: BufferedReader = response.body!!.byteStream().bufferedReader()
                while (true) {
                    val line = reader.readLine() ?: break
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    val piece = parseStreamPiece(payload)
                    if (piece.isNotEmpty()) {
                        full.append(piece)
                        onDelta(piece)
                    }
                }
                if (full.isBlank()) Result.failure(Exception("Gemini responded with no readable text."))
                else Result.success(full.toString())
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** Text of one streamed chunk, skipping any "thought" parts. */
    private fun parseStreamPiece(payload: String): String {
        return try {
            val parts = JSONObject(payload)
                .optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
            val sb = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.optJSONObject(i) ?: continue
                if (part.optBoolean("thought", false)) continue
                if (part.isNull("text")) continue
                sb.append(part.optString("text"))
            }
            sb.toString()
        } catch (e: Exception) {
            ""
        }
    }

    private fun parseReply(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null

            val firstCandidate = candidates.getJSONObject(0)
            val content = firstCandidate.optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null

            parts.getJSONObject(0).optString("text").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
