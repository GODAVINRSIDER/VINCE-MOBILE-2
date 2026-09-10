package com.godavin.vince

import android.util.Base64
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
 * Sends a photo plus a question to Gemini's multimodal endpoint - the
 * same model family and request shape PC-VINCE's vision.py already uses
 * for screenshots, just fed a camera photo instead. Uses Gemini
 * specifically (not the Groq/OpenRouter fallback chain) since Gemini is
 * the provider VINCE Mobile already has reliable multimodal access
 * through; vision isn't routed through BrainRouter's text-only fallback.
 */
object GeminiVision {
    private const val MODEL = "gemini-3.6-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun describeImage(apiKey: String, imageBytes: ByteArray, question: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure(IllegalStateException("No Gemini API key saved - vision needs Gemini specifically."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)

                val requestJson = JSONObject().apply {
                    put("contents", JSONArray().put(
                        JSONObject().apply {
                            put("role", "user")
                            put("parts", JSONArray().apply {
                                put(JSONObject().put("text", question))
                                put(JSONObject().put(
                                    "inline_data",
                                    JSONObject().apply {
                                        put("mime_type", "image/jpeg")
                                        put("data", base64Image)
                                    }
                                ))
                            })
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
                            Exception("Gemini vision error (${response.code}): ${responseBody.take(200)}")
                        )
                    }

                    val reply = parseReply(responseBody)
                    if (reply != null) {
                        Result.success(reply)
                    } else {
                        Result.failure(Exception("Gemini vision responded with no readable text."))
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
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            if (parts.length() == 0) return null
            parts.getJSONObject(0).optString("text").ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
