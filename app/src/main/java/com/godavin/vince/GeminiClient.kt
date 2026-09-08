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
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Talks straight to Google's Gemini API using the key saved in ApiKeyStore.
 * This is the standalone-phone-VINCE brain (Stage 2) - no PC endpoint
 * involved anywhere in this file, unlike the old abandoned VinceApiClient
 * that called the PC's /chat endpoint.
 *
 * Kept intentionally simple for this first slice: one-shot message in,
 * text reply out, no conversation history and no tool-calling yet -
 * those get layered on in a later stage once this basic round trip is
 * confirmed working end to end.
 */
object GeminiClient {
    private const val MODEL = "gemini-3.6-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val MAX_ATTEMPTS = 2

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    /** Returns the reply text on success, or a user-facing error string
     * prefixed so the chat screen can show it inline like any other
     * message rather than needing a separate error UI. Retries once on a
     * network-level failure (timeout, dropped connection) before giving
     * up - a single flaky moment on mobile data shouldn't fail the whole
     * message when a second try would likely succeed. */
    suspend fun sendMessage(apiKey: String, userMessage: String): String {
        if (apiKey.isBlank()) {
            return "No Gemini API key saved yet - add one in Settings first."
        }

        var lastNetworkError: String? = null

        for (attempt in 1..MAX_ATTEMPTS) {
            val result = withContext(Dispatchers.IO) {
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
                            return@withContext Result.success(
                                "Gemini returned an error (${response.code}): " +
                                    responseBody.take(200)
                            )
                        }

                        Result.success(
                            parseReply(responseBody)
                                ?: "Gemini responded but I couldn't find any text in the reply."
                        )
                    }
                } catch (e: IOException) {
                    Result.failure<String>(e)
                } catch (e: Exception) {
                    return@withContext Result.success("Something went wrong talking to Gemini. (${e.message})")
                }
            }

            if (result.isSuccess) {
                return result.getOrThrow()
            }

            lastNetworkError = result.exceptionOrNull()?.message
            if (attempt < MAX_ATTEMPTS) {
                delay(1500)
            }
        }

        return "Couldn't reach Gemini after $MAX_ATTEMPTS tries - check your internet connection. " +
            "(${lastNetworkError ?: "timeout"})"
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
