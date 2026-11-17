package com.godavin.vince

import android.content.Context
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
 * v2.17 - LOCAL BRAIN: last-resort fallback that is NOT tied to any phone.
 *
 * It talks to ANY server that speaks the OpenAI-style /v1/chat/completions format:
 * Ollama (default port 11434), llama.cpp server, LM Studio, vLLM, or - later - the
 * VINCE hub/VPS. Switching phones changes nothing: only the address in Settings matters.
 *
 * Used only AFTER every cloud brain has failed or timed out (see BrainRouter.route),
 * so it never slows a normal reply down.
 */
object LocalBrain {
    // Local CPU models are slow: long read timeout, short connect (a dead address is noticed fast).
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    // Ollama silently cuts a prompt that is longer than its context window (2-4k tokens by default),
    // and it cuts the START (the persona rules). So the prompt is trimmed here: head + tail.
    private const val HEAD_CHARS = 1500
    private const val TAIL_CHARS = 4000

    fun configured(c: Context): Boolean =
        BrainPrefs.localUrl(c).isNotBlank() && BrainPrefs.localModel(c).isNotBlank()

    /** "192.168.1.50:11434", "http://pc:11434/", "http://pc:11434/v1" all become the full endpoint. */
    fun endpoint(raw: String): String {
        var u = raw.trim().trimEnd('/')
        if (u.isBlank()) return ""
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "http://$u"
        u = u.replace(Regex("/v1(/chat/completions)?$"), "")
        return "$u/v1/chat/completions"
    }

    fun trim(msg: String): String =
        if (msg.length <= HEAD_CHARS + TAIL_CHARS) msg
        else msg.take(HEAD_CHARS) + "\n...\n" + msg.takeLast(TAIL_CHARS)

    suspend fun chat(c: Context, message: String, maxTokens: Int): Result<String> =
        call(BrainPrefs.localUrl(c), BrainPrefs.localModel(c), BrainPrefs.localKey(c), trim(message), maxTokens.coerceAtMost(700))

    /** Settings "Test" button. */
    suspend fun test(url: String, model: String, key: String): Result<String> =
        call(url, model, key, "Reply with the single word: ready", 20)

    private suspend fun call(url: String, model: String, key: String, message: String, maxTokens: Int): Result<String> =
        withContext(Dispatchers.IO) {
            val ep = endpoint(url)
            if (ep.isBlank()) return@withContext Result.failure<String>(Exception("Local: no address saved."))
            if (model.isBlank()) return@withContext Result.failure<String>(Exception("Local: no model name saved."))
            try {
                val body = JSONObject()
                    .put("model", model.trim())
                    .put("stream", false)
                    .put("max_tokens", maxTokens)
                    .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", message)))
                val req = Request.Builder()
                    .url(ep)
                    .addHeader("Authorization", "Bearer ${key.ifBlank { "local" }}")
                    .post(body.toString().toRequestBody("application/json".toMediaType()))
                    .build()
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (!resp.isSuccessful) {
                        return@withContext Result.failure<String>(Exception("Local error (${resp.code}): ${text.take(160)}"))
                    }
                    val reply = JSONObject(text).optJSONArray("choices")
                        ?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty().trim()
                    if (reply.isBlank()) Result.failure<String>(Exception("Local: the model answered with no text."))
                    else Result.success(reply)
                }
            } catch (e: Exception) {
                Result.failure<String>(Exception("Local: could not reach ${ep.substringBefore("/v1")} (${e.javaClass.simpleName}: ${e.message?.take(80)})"))
            }
        }
}
