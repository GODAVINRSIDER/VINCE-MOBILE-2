package com.godavin.vince

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Text-to-image, using the keys already saved in Settings - no new key.
 * Chain: Gemini image models (discovered live from Google's model list,
 * so a renamed or retired model doesn't break it) -> OpenRouter image
 * models (live list, free ones first) -> Pollinations, a keyless last
 * resort that may be rate-limited or require sign-in. Each provider's
 * failure is summarised in one line instead of dumping raw JSON.
 */
object ImageGenerator {
    private const val GEMINI_BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private val PREFERRED_GEMINI = listOf("gemini-2.5-flash-image", "gemini-3.1-flash-image-preview", "gemini-3-pro-image-preview")
    private val TRANSIENT = setOf(500, 502, 503, 504)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .callTimeout(150, TimeUnit.SECONDS)
        .build()

    /** v2.3 - says WHICH provider made the image and why better ones were skipped,
     * so a silent fall-through to the weak keyless fallback is no longer invisible. */
    data class ImageResult(val path: String, val provider: String, val notes: List<String>) {
        fun summary(): String {
            val head = "Here's your image (made with $provider). Use Copy, Save or Share under it."
            return if (notes.isEmpty()) head
            else head + "\nBetter models were skipped:\n" + notes.joinToString("\n") { "- " + it.take(140) }
        }
    }

    /** Turns a casual request into a detailed visual prompt (one small text call).
     * Falls back to the original wording if no text model answers. */
    private suspend fun enhancePrompt(context: Context, userPrompt: String): String {
        val ask = "Rewrite this as one detailed prompt for an AI image generator. Describe exactly what " +
            "should be visible: subject, composition, lighting, colors, style. 40 to 70 words. " +
            "No text or letters inside the image unless the request needs them. Output only the " +
            "rewritten prompt, nothing else.\n\nRequest: $userPrompt"
        return try {
            val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
            val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
            var out: String? = null
            if (geminiKey.isNotBlank()) out = GeminiClient.sendMessage(geminiKey, ask).getOrNull()
            if (out == null && groqKey.isNotBlank()) {
                out = OpenAiCompatibleClient.sendMessage(
                    baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                    apiKey = groqKey,
                    model = "openai/gpt-oss-120b",
                    userMessage = ask,
                    providerLabel = "Groq"
                ).getOrNull()
            }
            val cleaned = out?.trim()?.trim('"')
            if (cleaned != null && cleaned.length in 20..900) cleaned else userPrompt
        } catch (e: Exception) {
            userPrompt
        }
    }

    suspend fun generate(context: Context, rawPrompt: String): Result<ImageResult> = withContext(Dispatchers.IO) {
        val attempts = mutableListOf<String>()
        val prompt = enhancePrompt(context, rawPrompt)

        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            val r = viaGemini(geminiKey, prompt)
            val bytes = r.getOrNull()
            if (bytes != null) {
                val path = saveBytes(context, bytes)
                if (path != null) return@withContext Result.success(ImageResult(path, "Gemini", attempts))
            }
            attempts.add("Gemini: ${r.exceptionOrNull()?.message ?: "returned no image"}")
        } else {
            attempts.add("Gemini: no key saved")
        }

        val orKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (orKey.isNotBlank()) {
            val r = viaOpenRouter(orKey, prompt)
            val bytes = r.getOrNull()
            if (bytes != null) {
                val path = saveBytes(context, bytes)
                if (path != null) return@withContext Result.success(ImageResult(path, "OpenRouter", attempts))
            }
            attempts.add("OpenRouter: ${r.exceptionOrNull()?.message ?: "returned no image"}")
        }

        val r = viaPollinations(prompt)
        val bytes = r.getOrNull()
        if (bytes != null) {
            val path = saveBytes(context, bytes)
            if (path != null) {
                return@withContext Result.success(
                    ImageResult(path, "Pollinations (basic free fallback, quality is limited)", attempts)
                )
            }
        }
        attempts.add("Pollinations: ${r.exceptionOrNull()?.message ?: "returned no image"}")

        Result.failure(Exception(
            "Couldn't generate the image right now. Image models are often busy or rate-limited - " +
                "try again in a minute.\n" + attempts.joinToString("\n") { "- " + it.take(150) }
        ))
    }

    private fun saveBytes(context: Context, bytes: ByteArray): String? {
        val bmp: Bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        return ImageStore.save(context, bmp)
    }

    // ---------------- Gemini ----------------

    private fun geminiImageModels(apiKey: String): List<String> {
        val discovered = try {
            val req = Request.Builder().url("$GEMINI_BASE?key=$apiKey&pageSize=200").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val arr = JSONObject(resp.body?.string().orEmpty()).optJSONArray("models") ?: return@use null
                val found = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val m = arr.getJSONObject(i)
                    val id = m.optString("name").removePrefix("models/")
                    if (!id.contains("image")) continue
                    if (id.contains("imagen")) continue // Imagen uses a different endpoint (":predict")
                    val methods = m.optJSONArray("supportedGenerationMethods") ?: continue
                    var ok = false
                    for (j in 0 until methods.length()) if (methods.optString(j) == "generateContent") ok = true
                    if (ok) found.add(id)
                }
                found
            }
        } catch (e: Exception) { null }

        if (discovered == null) return PREFERRED_GEMINI.take(2)
        val ordered = PREFERRED_GEMINI.filter { it in discovered } + discovered.filter { it !in PREFERRED_GEMINI }.sorted()
        return ordered.take(3)
    }

    private fun viaGemini(apiKey: String, prompt: String): Result<ByteArray> {
        val models = geminiImageModels(apiKey)
        var last = "no image model available on this key"
        for (model in models) {
            val body = JSONObject().apply {
                put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                put("generationConfig", JSONObject().put("responseModalities", JSONArray().put("TEXT").put("IMAGE")))
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("$GEMINI_BASE/$model:generateContent?key=$apiKey").post(body).build()

            for (attempt in 0..1) {
                try {
                    var retry = false
                    client.newCall(req).execute().use { resp ->
                        val text = resp.body?.string().orEmpty()
                        if (resp.isSuccessful) {
                            val bytes = extractGeminiImage(text)
                            if (bytes != null) return Result.success(bytes)
                            last = "$model replied with text only (the prompt may have been declined)"
                        } else if (resp.code in TRANSIENT && attempt == 0) {
                            last = "$model error (${resp.code}): ${ApiErrors.short(text)}"
                            retry = true
                        } else {
                            last = "$model error (${resp.code}): ${ApiErrors.short(text)}"
                        }
                    }
                    if (!retry) break
                    Thread.sleep(1500)
                } catch (e: Exception) {
                    last = "$model: ${e.message?.take(80)}"
                    break
                }
            }
        }
        return Result.failure(Exception(last))
    }

    private fun extractGeminiImage(responseBody: String): ByteArray? {
        return try {
            val candidates = JSONObject(responseBody).optJSONArray("candidates") ?: return null
            if (candidates.length() == 0) return null
            val parts = candidates.getJSONObject(0).optJSONObject("content")?.optJSONArray("parts") ?: return null
            for (i in 0 until parts.length()) {
                val p = parts.getJSONObject(i)
                val inline = p.optJSONObject("inlineData") ?: p.optJSONObject("inline_data") ?: continue
                val data = inline.optString("data")
                if (data.isNotBlank()) return Base64.decode(data, Base64.DEFAULT)
            }
            null
        } catch (e: Exception) { null }
    }

    // ---------------- OpenRouter ----------------

    private fun openRouterImageModels(apiKey: String): List<String> {
        return try {
            val req = Request.Builder().url("https://openrouter.ai/api/v1/models")
                .addHeader("Authorization", "Bearer $apiKey").get().build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList<String>()
                val data = JSONObject(resp.body?.string().orEmpty()).optJSONArray("data") ?: return@use emptyList<String>()
                val free = mutableListOf<String>()
                val paid = mutableListOf<String>()
                for (i in 0 until data.length()) {
                    val m = data.getJSONObject(i)
                    val outs = m.optJSONObject("architecture")?.optJSONArray("output_modalities") ?: continue
                    var hasImage = false
                    for (j in 0 until outs.length()) if (outs.optString(j) == "image") hasImage = true
                    if (!hasImage) continue
                    val id = m.optString("id")
                    if (id.endsWith(":free")) free.add(id) else paid.add(id)
                }
                // Free models first; paid ones only as a last resort within the same short list.
                (free + paid).take(3)
            }
        } catch (e: Exception) { emptyList<String>() }
    }

    private fun viaOpenRouter(apiKey: String, prompt: String): Result<ByteArray> {
        val models = openRouterImageModels(apiKey)
        if (models.isEmpty()) return Result.failure(Exception("no image-capable model listed"))
        var last = "failed"
        for (model in models) {
            val body = JSONObject().apply {
                put("model", model)
                put("modalities", JSONArray().put("image").put("text"))
                put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", prompt)))
            }.toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder().url("https://openrouter.ai/api/v1/chat/completions")
                .addHeader("Authorization", "Bearer $apiKey").post(body).build()
            try {
                client.newCall(req).execute().use { resp ->
                    val text = resp.body?.string().orEmpty()
                    if (resp.isSuccessful) {
                        val bytes = extractOpenRouterImage(text)
                        if (bytes != null) return Result.success(bytes)
                        last = "$model returned no image"
                    } else {
                        last = "$model error (${resp.code}): ${ApiErrors.short(text)}"
                    }
                }
            } catch (e: Exception) {
                last = "$model: ${e.message?.take(80)}"
            }
        }
        return Result.failure(Exception(last))
    }

    private fun extractOpenRouterImage(responseBody: String): ByteArray? {
        return try {
            val msg = JSONObject(responseBody).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message") ?: return null
            val images = msg.optJSONArray("images") ?: return null
            for (i in 0 until images.length()) {
                val url = images.getJSONObject(i).optJSONObject("image_url")?.optString("url").orEmpty()
                if (url.startsWith("data:") && url.contains("base64,")) {
                    return Base64.decode(url.substringAfter("base64,"), Base64.DEFAULT)
                }
            }
            null
        } catch (e: Exception) { null }
    }

    // ---------------- Pollinations (keyless fallback) ----------------

    private fun viaPollinations(prompt: String): Result<ByteArray> {
        val encoded = URLEncoder.encode(prompt.take(700), "UTF-8").replace("+", "%20")
        val seed = (System.currentTimeMillis() % 100000).toInt()
        val urls = listOf(
            "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&model=flux&seed=$seed",
            "https://image.pollinations.ai/prompt/$encoded?width=1024&height=1024&nologo=true&seed=$seed"
        )
        var last = "request failed"
        for (url in urls) {
            try {
                val req = Request.Builder().url(url).addHeader("User-Agent", "Mozilla/5.0").get().build()
                val got: ByteArray? = client.newCall(req).execute().use { resp ->
                    val ct = resp.header("Content-Type").orEmpty()
                    if (!resp.isSuccessful) {
                        last = "error (${resp.code})"
                        null
                    } else if (!ct.startsWith("image")) {
                        last = "service didn't return an image"
                        null
                    } else {
                        resp.body?.bytes()
                    }
                }
                if (got != null) return Result.success(got)
            } catch (e: Exception) {
                last = e.message?.take(80) ?: "request failed"
            }
        }
        return Result.failure(Exception(last))
    }
}

/** Recognises image requests in many phrasings. Returns the image description, or null. */
object ImageCommands {
    private val OPTS = setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)

    private const val LEAD = "^(?:(?:hey|hi|ok|okay|so|please|can you|could you|would you|will you|" +
        "i want you to|i need you to|i'd like you to|i would like you to|go ahead and|vince|clara|davina)[,]?\\s+)*"
    private const val NOUN = "(?:ai\\s+)?(?:image|picture|photo|illustration|logo|wallpaper|poster|artwork|icon|" +
        "banner|thumbnail|drawing|sketch|render|graphic)s?"

    private val STRONG = Regex(
        LEAD + "(?:generate|create|make|produce|render|design|draw|paint|sketch|build)\\s+(?:me\\s+)?(?:an?\\s+|the\\s+|some\\s+)?" +
            NOUN + "\\s*(?:of|for|showing|with|about|:|-)?\\s*(.+)$", OPTS
    )
    private val SOFT = Regex(
        LEAD + "(?:i want|i need|i'd like|i would like|give me|show me|get me|can i get|can i have|let me see)\\s+" +
            "(?:an?\\s+|the\\s+|some\\s+)?" + NOUN + "\\s*(?:of|showing|with|about|:)\\s*(.+)$", OPTS
    )
    private val DRAW = Regex(
        LEAD + "(?:draw|paint|sketch)\\s+(?:me\\s+)?(?:an?\\s+|the\\s+)(.+)$", OPTS
    )

    fun extractPrompt(text: String): String? {
        val t = text.replace('\u2019', '\'').trim().trimEnd('.', '!', '?')
        STRONG.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        SOFT.find(t)?.let { return it.groupValues[1].trim().ifBlank { null } }
        DRAW.find(t)?.let {
            val what = it.groupValues[1].trim()
            // "draw a conclusion / line / comparison" are figures of speech, not image requests
            val abstract = listOf("conclusion", "comparison", "parallel", "distinction", "line", "blank", "inference", "analogy")
            if (abstract.any { w -> what.lowercase().startsWith(w) }) return null
            return what.ifBlank { null }
        }
        return null
    }
}
