package com.godavin.vince

import android.util.Base64
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
 * Sends a photo plus a question to Gemini's multimodal endpoint.
 *
 * Fix - this used to try ONE hardcoded model once. A Gemini "503 high
 * demand" is per-model and temporary, so now it (1) retries a transient
 * 5xx once after a short pause, and (2) falls through to up to two other
 * Gemini flash models, found from Gemini's own live model list rather than
 * guessed names (hardcoded names going stale is what broke Groq).
 */
object GeminiVision {
    private const val PREFERRED_MODEL = "gemini-3.6-flash"
    private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
    private const val CACHE_TTL_MS = 30 * 60 * 1000L
    private val TRANSIENT_CODES = setOf(500, 502, 503, 504)
    private val EXCLUDED_NAME_PARTS =
        listOf("tts", "image", "live", "audio", "embed", "robotics", "computer")

    private var cachedModels: List<String>? = null
    private var cacheTime: Long = 0

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    suspend fun describeImage(apiKey: String, imageBytes: ByteArray, question: String): Result<String> {
        if (apiKey.isBlank()) {
            return Result.failure<String>(IllegalStateException("No Gemini API key saved - vision needs Gemini specifically."))
        }

        return withContext(Dispatchers.IO) {
            try {
                val base64Image = Base64.encodeToString(imageBytes, Base64.NO_WRAP)
                val models = candidateModels(apiKey)
                var lastError = "failed"
                for (model in models) {
                    val result = callModel(apiKey, model, base64Image, question)
                    if (result.isSuccess) return@withContext result
                    lastError = result.exceptionOrNull()?.message ?: "failed"
                }
                val tried = if (models.size > 1) " (tried ${models.size} models)" else ""
                Result.failure<String>(Exception("$lastError$tried"))
            } catch (e: Exception) {
                Result.failure<String>(e)
            }
        }
    }

    private suspend fun callModel(
        apiKey: String,
        model: String,
        base64Image: String,
        question: String
    ): Result<String> {
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
        val body = requestJson.toString().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$BASE_URL/$model:generateContent?key=$apiKey")
            .post(body)
            .build()

        var lastFailure: Exception? = null
        for (attempt in 0..1) {
            try {
                val outcome: Result<String>? = client.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful -> {
                            val reply = parseReply(responseBody)
                            if (reply != null) Result.success(reply)
                            else Result.failure<String>(Exception("Gemini vision ($model) responded with no readable text."))
                        }
                        response.code in TRANSIENT_CODES && attempt == 0 -> {
                            lastFailure = Exception(
                                "Gemini vision error (${response.code}): ${ApiErrors.short(responseBody)}"
                            )
                            null
                        }
                        else -> Result.failure<String>(
                            Exception("Gemini vision error (${response.code}): ${ApiErrors.short(responseBody)}")
                        )
                    }
                }
                if (outcome != null) return outcome
                delay(1500)
            } catch (e: java.io.IOException) {
                lastFailure = e
                if (attempt == 0) delay(500)
            }
        }
        return Result.failure<String>(lastFailure ?: Exception("Gemini vision request failed."))
    }

    /** Preferred model first (if it still exists), then up to two others, stable ones before preview/lite. */
    private fun candidateModels(apiKey: String): List<String> {
        val now = System.currentTimeMillis()
        val cached = cachedModels
        if (cached != null && (now - cacheTime) < CACHE_TTL_MS) return cached

        val discovered = discoverFlashModels(apiKey)
        val result = if (discovered == null) {
            listOf(PREFERRED_MODEL) // discovery itself failed - fall back to the known name
        } else {
            val ordered = mutableListOf<String>()
            if (PREFERRED_MODEL in discovered) ordered.add(PREFERRED_MODEL)
            ordered.addAll(discovered.filter { it != PREFERRED_MODEL }.sortedBy { rank(it) })
            ordered.take(3).ifEmpty { listOf(PREFERRED_MODEL) }
        }
        if (discovered != null) {
            cachedModels = result
            cacheTime = now
        }
        return result
    }

    private fun rank(id: String): Int {
        var score = 0
        if (id.contains("preview") || id.contains("exp")) score += 2
        if (id.contains("lite")) score += 1
        return score
    }

    private fun discoverFlashModels(apiKey: String): List<String>? {
        return try {
            val request = Request.Builder()
                .url("$BASE_URL?key=$apiKey&pageSize=200")
                .get()
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val models = JSONObject(response.body?.string().orEmpty()).optJSONArray("models")
                    ?: return@use null
                val found = mutableListOf<String>()
                for (i in 0 until models.length()) {
                    val m = models.getJSONObject(i)
                    val id = m.optString("name").removePrefix("models/")
                    if (!id.contains("flash")) continue
                    if (EXCLUDED_NAME_PARTS.any { id.contains(it) }) continue
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var canGenerate = false
                    for (j in 0 until methods.length()) {
                        if (methods.optString(j) == "generateContent") canGenerate = true
                    }
                    if (canGenerate) found.add(id)
                }
                found
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun parseReply(responseBody: String): String? {
        return try {
            val root = JSONObject(responseBody)
            val candidates = root.optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val content = candidates.getJSONObject(0).optJSONObject("content") ?: return null
            val parts = content.optJSONArray("parts") ?: return null
            val text = StringBuilder()
            for (i in 0 until parts.length()) {
                val part = parts.getJSONObject(i)
                if (part.optBoolean("thought", false)) continue
                text.append(part.optString("text"))
            }
            text.toString().ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
