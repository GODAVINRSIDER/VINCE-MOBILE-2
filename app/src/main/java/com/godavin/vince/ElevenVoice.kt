package com.godavin.vince

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * v2.6 - ElevenLabs as VINCE's main voice, the phone's own voice as fallback.
 *
 * How it stays fast: a reply is cut into sentences; each sentence is requested
 * from ElevenLabs (flash model, raw PCM) and the audio is played the moment the
 * first bytes arrive, while the next sentence is already downloading. The first
 * words are therefore heard after roughly one short request instead of after the
 * whole reply is spoken.
 *
 * Fallback: no key, switched off, a failed request or an used-up quota all hand
 * the sentence to the phone's built-in voice (VoiceOutput.speakSystemAwait), and
 * ElevenLabs is skipped for a while (ProviderHealth) instead of retried every time.
 */
object ElevenVoice {
    private const val NAME = "ElevenLabs"
    private const val MODEL = "eleven_flash_v2_5"
    private const val SAMPLE_RATE = 24000
    private const val PREFS = "vince_voice_prefs"
    private const val KEY_ENABLED = "eleven_enabled"

    /** ElevenLabs' stock voices (work on any plan via the API). Change them in Settings. */
    private val DEFAULT_VOICE_IDS = mapOf(
        Persona.VINCE to "pNInz6obpgDQGcFmaJgB",   // Adam - deep, steady
        Persona.CLARA to "21m00Tcm4TlvDq8ikWAM",   // Rachel - calm, warm
        Persona.DAVINA to "EXAVITQu4vr4xnSDxMaL"   // Sarah/Bella - bright
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var app: Context? = null
    @Volatile private var cachedKey: String = ""

    // ---------------------------------------------------------------- settings

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, on).apply()
        if (!on) stop()
    }

    fun voiceId(context: Context, persona: Persona): String =
        prefs(context).getString("voice_${persona.name}", null)?.trim()?.takeIf { it.isNotEmpty() }
            ?: DEFAULT_VOICE_IDS[persona] ?: DEFAULT_VOICE_IDS.getValue(Persona.VINCE)

    fun setVoiceId(context: Context, persona: Persona, id: String) {
        prefs(context).edit().putString("voice_${persona.name}", id.trim()).apply()
    }

    fun defaultVoiceId(persona: Persona): String = DEFAULT_VOICE_IDS[persona] ?: ""

    /** Re-read the saved key (the key store is slow, so it is cached). Call after saving. */
    fun reloadKey(context: Context) {
        cachedKey = try { ApiKeyStore.getKey(context, Provider.ELEVENLABS) } catch (e: Exception) { "" }
        // a freshly saved key deserves a fresh start
        ProviderHealth.markOk(NAME)
    }

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        reloadKey(context)
        scope.launch { fetcherLoop() }
        scope.launch { playerLoop() }
    }

    /** True when ElevenLabs should be used for the next sentence. */
    fun available(context: Context): Boolean =
        app != null && cachedKey.isNotBlank() && isEnabled(context) && !ProviderHealth.coolingDown(NAME)

    // ---------------------------------------------------------------- queueing

    private class Req(val epoch: Int, val text: String?, val persona: Persona, val signal: CompletableDeferred<Unit>?)

    private sealed interface Pcm { val epoch: Int }
    private class Data(override val epoch: Int, val bytes: ByteArray) : Pcm
    private class End(override val epoch: Int) : Pcm
    private class Fail(override val epoch: Int, val text: String, val persona: Persona) : Pcm
    private class Done(override val epoch: Int, val signal: CompletableDeferred<Unit>?) : Pcm

    private val reqs = Channel<Req>(Channel.UNLIMITED)
    private val pcm = Channel<Pcm>(Channel.UNLIMITED)
    private val active = AtomicInteger(0)

    @Volatile private var epoch = 0
    @Volatile private var currentTrack: AudioTrack? = null

    val isPlaying: Boolean get() = active.get() > 0

    /** Cuts everything off right now (new reply, barge-in, stop button). */
    fun stop() {
        synchronized(this) {
            epoch++
            active.set(0)
            val t = currentTrack
            if (t != null) {
                try { t.pause(); t.flush() } catch (e: Exception) { }
            }
        }
    }

    private fun enqueue(texts: List<String>, persona: Persona, signal: CompletableDeferred<Unit>?) {
        val e = epoch
        for (t in texts) {
            active.incrementAndGet()
            reqs.trySend(Req(e, t, persona, null))
        }
        if (signal != null) {
            active.incrementAndGet()
            reqs.trySend(Req(e, null, persona, signal))
        }
    }

    /** Whole text, replacing whatever was playing. */
    fun speak(text: String, persona: Persona) {
        stop()
        enqueue(splitForSpeech(text), persona, null)
    }

    /** Streamed reply: the first sentence replaces what was playing, the rest queue behind. */
    fun speakChunk(text: String, persona: Persona, first: Boolean) {
        if (first) stop()
        enqueue(splitForSpeech(text), persona, null)
    }

    /** Speaks and waits until the audio has really finished (or was cut off). */
    suspend fun speakAwait(text: String, persona: Persona) {
        stop()
        val signal = CompletableDeferred<Unit>()
        enqueue(splitForSpeech(text), persona, signal)
        val ok = withTimeoutOrNull(maxOf(10_000L, text.length * 130L)) { signal.await() }
        if (ok == null) stop()
    }

    // ---------------------------------------------------------------- splitting

    /** Short first piece (fast start), then sentence-sized pieces. Never cuts a decimal. */
    fun splitForSpeech(text: String): List<String> {
        val raw = text.split(Regex("(?<=[.!?])\\s+|\\n+")).map { it.trim() }.filter { it.isNotEmpty() }
        val out = mutableListOf<String>()
        var cur = StringBuilder()
        for (s in raw) {
            if (cur.isNotEmpty()) cur.append(' ')
            cur.append(s)
            val minLen = if (out.isEmpty()) 24 else 60
            if (cur.length >= minLen) {
                out.add(cur.toString())
                cur = StringBuilder()
            }
        }
        if (cur.isNotEmpty()) {
            if (out.isNotEmpty() && cur.length < 30) out[out.size - 1] = out.last() + " " + cur
            else out.add(cur.toString())
        }
        return out.flatMap { capLength(it, 300) }
    }

    private fun capLength(s: String, max: Int): List<String> {
        if (s.length <= max) return listOf(s)
        val parts = mutableListOf<String>()
        var rest = s
        while (rest.length > max) {
            var cut = rest.lastIndexOf(", ", max)
            if (cut < max / 2) cut = rest.lastIndexOf(' ', max)
            if (cut <= 0) cut = max
            parts.add(rest.substring(0, cut + 1).trim())
            rest = rest.substring(cut + 1).trim()
        }
        if (rest.isNotEmpty()) parts.add(rest)
        return parts
    }

    // ---------------------------------------------------------------- download

    private suspend fun fetcherLoop() {
        for (r in reqs) {
            try {
                if (r.epoch != epoch) {
                    r.signal?.complete(Unit)
                    continue
                }
                if (r.text == null) {
                    pcm.trySend(Done(r.epoch, r.signal))
                    continue
                }
                fetch(r.epoch, r.text, r.persona)
            } catch (e: Exception) {
                r.signal?.complete(Unit)
            }
        }
    }

    private suspend fun fetch(e: Int, text: String, persona: Persona) {
        val ctx = app
        if (ctx == null || cachedKey.isBlank() || ProviderHealth.coolingDown(NAME)) {
            pcm.trySend(Fail(e, text, persona))
            return
        }
        var sentAny = false
        try {
            val body = JSONObject()
                .put("text", text)
                .put("model_id", MODEL)
                .put("voice_settings", JSONObject().put("stability", 0.5).put("similarity_boost", 0.75))
                .toString()
            val request = Request.Builder()
                .url("https://api.elevenlabs.io/v1/text-to-speech/${voiceId(ctx, persona)}/stream?output_format=pcm_24000")
                .addHeader("xi-api-key", cachedKey)
                .addHeader("Accept", "application/octet-stream")
                .post(body.toRequestBody("application/json".toMediaType()))
                .build()
            val ok = HttpStream.run(client, request) { resp ->
                if (!resp.isSuccessful) {
                    val err = resp.body?.string().orEmpty()
                    val fatal = resp.code == 401 || resp.code == 402 || resp.code == 403 ||
                        err.contains("quota", ignoreCase = true)
                    ProviderHealth.markFailed(NAME, if (fatal) 30 * 60_000L else 60_000L)
                    return@run false
                }
                val src = resp.body!!.byteStream()
                val buf = ByteArray(4096)
                var carry = -1
                while (true) {
                    if (e != epoch) return@run true            // cut off by a newer reply
                    val n = src.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    // keep every chunk an even number of bytes (16-bit samples)
                    val total = n + (if (carry >= 0) 1 else 0)
                    val out = ByteArray(total - (total % 2))
                    var o = 0
                    var i = 0
                    if (carry >= 0) { out[o++] = carry.toByte(); carry = -1 }
                    while (o < out.size) out[o++] = buf[i++]
                    if (i < n) carry = buf[i].toInt() and 0xFF
                    if (out.isNotEmpty()) {
                        sentAny = true
                        pcm.trySend(Data(e, out))
                    }
                }
                true
            }
            if (!ok && !sentAny) pcm.trySend(Fail(e, text, persona)) else pcm.trySend(End(e))
        } catch (ex: Exception) {
            if (e != epoch) return
            ProviderHealth.markFailed(NAME)
            if (!sentAny) pcm.trySend(Fail(e, text, persona)) else pcm.trySend(End(e))
        }
    }

    // ---------------------------------------------------------------- playback

    private fun openTrack(): AudioTrack {
        val min = AudioTrack.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        val size = maxOf(min, SAMPLE_RATE)   // about half a second of audio
        return AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(size)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .build()
    }

    private suspend fun playerLoop() {
        var track: AudioTrack? = null
        var frames = 0L
        var trackEpoch = -1

        fun release(t: AudioTrack) {
            try { t.stop() } catch (e: Exception) { }
            try { t.release() } catch (e: Exception) { }
            if (currentTrack === t) currentTrack = null
        }

        suspend fun closeTrack() {
            val t = track ?: return
            // let what is already buffered finish playing (unless cut off)
            val remaining = frames - t.playbackHeadPosition.toLong()
            val deadline = System.currentTimeMillis() + maxOf(0L, remaining) * 1000L / SAMPLE_RATE + 400L
            while (trackEpoch == epoch && t.playbackHeadPosition.toLong() < frames &&
                System.currentTimeMillis() < deadline
            ) delay(30)
            release(t)
            track = null
            frames = 0
        }

        while (true) {
            val item: Pcm? = if (track == null) pcm.receive() else withTimeoutOrNull(2500) { pcm.receive() }
            if (item == null) {            // quiet for a moment: finish and free the speaker
                closeTrack()
                continue
            }
            if (item.epoch != epoch) {     // belongs to a reply that was cut off
                val t = track
                if (t != null && trackEpoch != epoch) {
                    release(t)
                    track = null
                    frames = 0
                }
                if (item is Done) item.signal?.complete(Unit)
                continue
            }
            when (item) {
                is Data -> {
                    if (track == null) {
                        val t = openTrack()
                        track = t
                        currentTrack = t
                        trackEpoch = item.epoch
                        t.play()
                    }
                    val t = track!!
                    var off = 0
                    while (off < item.bytes.size && item.epoch == epoch) {
                        val n = t.write(item.bytes, off, item.bytes.size - off)
                        if (n <= 0) break
                        off += n
                    }
                    frames += off / 2
                }
                is End -> active.decrementAndGet()
                is Fail -> {
                    closeTrack()
                    if (item.epoch == epoch) VoiceOutput.speakSystemAwait(item.text, item.persona)
                    active.decrementAndGet()
                }
                is Done -> {
                    closeTrack()
                    item.signal?.complete(Unit)
                    active.decrementAndGet()
                }
            }
        }
    }
}
