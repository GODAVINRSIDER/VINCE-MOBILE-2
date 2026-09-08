package com.godavin.vince

import kotlinx.coroutines.Dispatchers
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
    private const val MODEL = "gemini-2.5-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Returns the reply text on success, or a user-facing error string
     * prefixed so the chat screen can show it inline like any other
     * message rather than needing a separate error UI. */
    suspend fun sendMessage(apiKey: String, userMessage: String): String {
        if (apiKey.isBlank()) {
            return "No Gemini API key saved yet - add one in Settings first."
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
                        return@withContext "Gemini returned an error (${response.code}): " +
                            responseBody.take(200)
                    }

                    parseReply(responseBody)
                        ?: "Gemini responded but I couldn't find any text in the reply."
                }
            } catch (e: IOException) {
                "Couldn't reach Gemini - check your internet connection. (${e.message})"
            } catch (e: Exception) {
                "Something went wrong talking to Gemini. (${e.message})"
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
