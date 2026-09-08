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

                val body = requestJson.toString()
                    .toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url(baseUrl)
                    .addHeader("Authorization", "Bearer $apiKey")
                    .post(body)
                    .build()

                client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()

                    if (!response.isSuccessful) {
                        return@withContext Result.failure(
                            Exception("$providerLabel error (${response.code}): ${responseBody.take(200)}")
                        )
                    }

                    val reply = parseReply(responseBody)
                    if (reply != null) {
                        Result.success(reply)
                    } else {
                        Result.failure(Exception("$providerLabel responded with no readable text."))
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
            val choices = root.optJSONArray("choices") ?: return null
            if (choices.length() == 0) return null
            val message = choices.getJSONObject(0).optJSONObject("message") ?: return null
            message.optString("content").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
