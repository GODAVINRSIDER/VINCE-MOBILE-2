package com.godavin.vince

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/**
 * Wraps Android's built-in TextToSpeech engine. Kept as a single
 * lazily-initialized instance (not re-created per screen) since spinning
 * up a fresh TTS engine is slow and only one should ever be speaking at
 * once. Uses applicationContext specifically to avoid holding a
 * reference to an Activity, which would leak memory across screen
 * rotations/navigation.
 *
 * VINCE VOICE - Vincent confirmed "en-us-x-iom-local" is a real male
 * voice on his device via the (now-removed) voice picker, so that's
 * hardcoded as VINCE's voice below. No more manual picker in Settings -
 * if this exact voice isn't installed on a given device (rare, but
 * possible on some OEM builds), it falls back to the same lower-pitch
 * treatment as before rather than crashing or silently using the wrong
 * voice.
 */
object VoiceOutput {
    private const val VINCE_VOICE_NAME = "en-us-x-iom-local"

    private var tts: TextToSpeech? = null
    private var appContext: Context? = null
    private var ready = false
    private var defaultVoice: Voice? = null
    private var vinceVoice: Voice? = null

    // v2.3 - lets the continuous voice session wait until VINCE has actually
    // finished talking (or was cut off) before listening again.
    @Volatile private var pending: CompletableDeferred<Unit>? = null
    private var utteranceCounter = 0

    val isSpeaking: Boolean get() = tts?.isSpeaking == true || ElevenVoice.isPlaying

    @Volatile private var pendingId: String? = null

    /** [id] null = force-complete (used by stop()). Otherwise only the utterance
     * we are waiting on may complete it, so a flushed older utterance cannot. */
    private fun finishPending(id: String? = null) {
        if (id == null || id == pendingId) pending?.complete(Unit)
    }

    fun init(context: Context) {
        if (tts != null) return
        appContext = context.applicationContext
        ElevenVoice.init(context)   // v2.6 - ElevenLabs is the main voice when a key is saved
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                defaultVoice = tts?.voice
                vinceVoice = tts?.voices?.find { it.name == VINCE_VOICE_NAME }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) { finishPending(utteranceId) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { finishPending(utteranceId) }
                    override fun onError(utteranceId: String?, errorCode: Int) { finishPending(utteranceId) }
                    override fun onStop(utteranceId: String?, interrupted: Boolean) { finishPending(utteranceId) }
                })
                ready = true
            }
        }
    }

    private fun elevenReady(persona: Persona): Boolean {
        val c = appContext ?: return false
        return ElevenVoice.available(c, persona)
    }

    /** For SentenceSpeaker: will this persona's reply be spoken by ElevenLabs? */
    fun elevenWillSpeak(persona: Persona): Boolean = elevenReady(persona)

    fun speak(text: String, persona: Persona = Persona.VINCE) {
        if (text.isBlank()) return
        if (elevenReady(persona)) {
            tts?.stop()
            ElevenVoice.speak(stripMarkdownForSpeech(text).take(3900), persona)
            return
        }
        ElevenVoice.stop()
        speakInternal(text, persona, TextToSpeech.QUEUE_FLUSH)
    }

    /** v2.5 - for streamed replies: the first piece interrupts anything old
     * ([first] = true), later pieces queue up behind it, so VINCE starts talking
     * on the first finished sentence while the rest is still being written. */
    fun speakChunk(text: String, persona: Persona, first: Boolean) {
        if (text.isBlank()) return
        if (elevenReady(persona)) {
            if (first) tts?.stop()
            ElevenVoice.speakChunk(stripMarkdownForSpeech(text).take(3900), persona, first)
            return
        }
        if (first) ElevenVoice.stop()
        speakInternal(text, persona, if (first) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD)
    }

    private fun speakInternal(text: String, persona: Persona, queueMode: Int) {
        if (!ready || text.isBlank()) return

        if (persona == Persona.VINCE) {
            if (vinceVoice != null) {
                tts?.voice = vinceVoice
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
            } else {
                // Confirmed voice isn't installed on this device - fall
                // back to the default voice at a lower pitch so VINCE
                // still reads as male.
                tts?.voice = defaultVoice
                tts?.setPitch(0.75f)
                tts?.setSpeechRate(0.95f)
            }
        } else {
            // CLARA / DAVINA - unchanged: default voice, persona pitch/rate.
            // Explicitly reset to defaultVoice in case VINCE's voice was
            // set on the previous turn (TTS.voice is sticky otherwise).
            tts?.voice = defaultVoice
            tts?.setPitch(persona.pitch)
            tts?.setSpeechRate(persona.rate)
        }

        // The engine rejects input over ~4000 characters outright.
        val clean = stripMarkdownForSpeech(text).take(3900)
        utteranceCounter++
        if (clean.isBlank()) return
        tts?.speak(clean, queueMode, null, "vince_utt_$utteranceCounter")
    }

    /** Speaks and suspends until the speech ends, is interrupted, or a safety
     * timeout passes. Used by the continuous voice session. */
    suspend fun speakAwait(text: String, persona: Persona = Persona.VINCE) {
        if (text.isBlank()) return
        if (elevenReady(persona)) {
            tts?.stop()
            ElevenVoice.speakAwait(stripMarkdownForSpeech(text).take(3900), persona)
            return
        }
        ElevenVoice.stop()
        speakSystemAwait(text, persona)
    }

    /** The phone's own voice, waiting until it is done. Also the fallback for ElevenVoice. */
    suspend fun speakSystemAwait(text: String, persona: Persona = Persona.VINCE) {
        if (!ready || text.isBlank()) return
        val d = CompletableDeferred<Unit>()
        pendingId = "vince_utt_${utteranceCounter + 1}"
        pending = d
        speakInternal(text, persona, TextToSpeech.QUEUE_FLUSH)
        withTimeoutOrNull(maxOf(10_000L, text.length * 110L)) { d.await() }
        if (pending === d) pending = null
    }

    /** Cleans text before handing it to the speech engine - TTS has no
     * idea Markdown symbols are formatting, so it was literally reading
     * them aloud mid-sentence ("hash hash", "dash dash dash..." for a
     * whole table-separator line). Also fixes trading-timeframe
     * shorthand ("15m", "1h") being read as units of distance/other
     * words instead of minutes/hours - genuinely different meaning in
     * this app's context, worth spelling out for speech specifically
     * (the on-screen text is untouched either way). */
    private fun stripMarkdownForSpeech(text: String): String {
        return text
            // Whole lines that are just a Markdown table separator
            // ("|---|---|" etc) or a horizontal rule ("---") - these
            // have nothing worth speaking, drop the entire line rather
            // than reading out a string of dashes.
            .lines()
            .filterNot { line ->
                val trimmed = line.trim()
                trimmed.isNotEmpty() && trimmed.all { it == '-' || it == '|' || it == ':' || it == ' ' }
            }
            .joinToString("\n")
            // Trading timeframe shorthand -> spoken form. Word-boundary
            // + digit-immediately-before-letter keeps this from matching
            // unrelated words (won't touch "room" or "channel", only a
            // number directly followed by m/h/d/w).
            .replace(Regex("\\b(\\d+)m\\b")) { "${it.groupValues[1]} minute" }
            .replace(Regex("\\b(\\d+)h\\b")) { "${it.groupValues[1]} hour" }
            .replace(Regex("\\b(\\d+)d\\b")) { "${it.groupValues[1]} day" }
            .replace(Regex("\\b(\\d+)w\\b")) { "${it.groupValues[1]} week" }
            // Table pipes and remaining Markdown symbols - strip, not speak.
            .replace(Regex("[|*_#`]"), "")
            // v2.6 - no more long rests: a line break after punctuation is just a space,
            // any other line break is a light comma pause, and "..." is a comma. Before,
            // paragraph breaks became ". " right after an existing full stop (".. "), which
            // the engine read as a long pause.
            .replace(Regex("([.!?:;,])[ \\t]*\\n+"), "$1 ")
            .replace(Regex("\\n+"), ", ")
            .replace(Regex("\\.{2,}|\u2026"), ",")
            .replace(Regex("\\s{2,}"), " ")
            .trim()
    }

    fun stop() {
        tts?.stop()
        ElevenVoice.stop()
        finishPending()
    }
}

/**
 * v2.5 - feeds streamed text to the voice one finished sentence at a time.
 * Call [feed] with each piece as it arrives and [finish] when the reply is done.
 * Boundaries are ". ", "! ", "? " or a line break, so numbers like 2.5 or
 * 3,250.40 are never cut in half. Very short first sentences are held back so
 * the voice does not stutter out one-word chunks.
 */
class SentenceSpeaker(private val persona: Persona, private val gate: () -> Boolean = { true }) {
    private val buf = StringBuilder()
    private val full = StringBuilder()
    private var spokeAny = false
    private var chunked: Boolean? = null

    fun feed(delta: String) {
        if (!gate()) { buf.clear(); return }   // v2.12.3 - voice switched off mid-reply
        if (chunked == null) chunked = VoiceOutput.elevenWillSpeak(persona)
        full.append(delta)
        // The phone's own voice leaves a long gap between queued sentences, so it gets the
        // whole reply as ONE utterance when the reply is finished (as before v2.5).
        // Sentence-by-sentence is only for ElevenLabs, which has no such gap.
        if (chunked != true) return
        buf.append(delta)
        flush(final = false)
    }

    fun finish() {
        if (!gate()) return
        if (chunked != true) {
            if (full.isNotBlank()) VoiceOutput.speak(full.toString(), persona)
            return
        }
        flush(final = true)
    }

    private fun flush(final: Boolean) {
        if (!gate()) { buf.clear(); return }
        while (true) {
            val cut = findCut(final)
            if (cut <= 0) break
            val piece = buf.substring(0, cut).trim()
            buf.delete(0, cut)
            if (piece.isNotBlank()) {
                VoiceOutput.speakChunk(piece, persona, first = !spokeAny)
                spokeAny = true
            }
        }
        if (final && buf.isNotBlank()) {
            VoiceOutput.speakChunk(buf.toString().trim(), persona, first = !spokeAny)
            spokeAny = true
            buf.clear()
        }
    }

    /** Index just after the last sentence end in the buffer, or -1. */
    private fun findCut(final: Boolean): Int {
        // v2.7 - longer pieces after the first one: each piece is a separate ElevenLabs request,
        // and many tiny ones sounded choppy.
        val minLen = if (spokeAny) 45 else 26
        var i = 0
        while (i < buf.length) {
            val c = buf[i]
            val atEnd = i == buf.length - 1
            val isBreak = c == '\n' ||
                ((c == '.' || c == '!' || c == '?') && (if (atEnd) final else buf[i + 1].isWhitespace()))
            if (isBreak && i + 1 >= minLen) return i + 1
            i++
        }
        return -1
    }
}
