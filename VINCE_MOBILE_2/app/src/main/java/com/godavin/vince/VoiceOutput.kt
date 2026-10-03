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
    private var ready = false
    private var defaultVoice: Voice? = null
    private var vinceVoice: Voice? = null

    // v2.3 - lets the continuous voice session wait until VINCE has actually
    // finished talking (or was cut off) before listening again.
    @Volatile private var pending: CompletableDeferred<Unit>? = null
    private var utteranceCounter = 0

    val isSpeaking: Boolean get() = tts?.isSpeaking == true

    @Volatile private var pendingId: String? = null

    /** [id] null = force-complete (used by stop()). Otherwise only the utterance
     * we are waiting on may complete it, so a flushed older utterance cannot. */
    private fun finishPending(id: String? = null) {
        if (id == null || id == pendingId) pending?.complete(Unit)
    }

    fun init(context: Context) {
        if (tts != null) return
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

    fun speak(text: String, persona: Persona = Persona.VINCE) {
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
        tts?.speak(clean, TextToSpeech.QUEUE_FLUSH, null, "vince_utt_$utteranceCounter")
    }

    /** Speaks and suspends until the speech ends, is interrupted, or a safety
     * timeout passes. Used by the continuous voice session. */
    suspend fun speakAwait(text: String, persona: Persona = Persona.VINCE) {
        if (!ready || text.isBlank()) return
        val d = CompletableDeferred<Unit>()
        pendingId = "vince_utt_${utteranceCounter + 1}"
        pending = d
        speak(text, persona)
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
            .replace(Regex("\\n{2,}"), ". ")
            .trim()
    }

    fun stop() {
        tts?.stop()
        finishPending()
    }
}
