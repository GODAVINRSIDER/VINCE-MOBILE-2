package com.godavin.vince

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
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
