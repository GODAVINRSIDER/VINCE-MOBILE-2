package com.godavin.vince

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
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

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                defaultVoice = tts?.voice
                vinceVoice = tts?.voices?.find { it.name == VINCE_VOICE_NAME }
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

        tts?.speak(stripMarkdownForSpeech(text), TextToSpeech.QUEUE_FLUSH, null, null)
    }

    /** Strips Markdown formatting characters (**, *, #, `, _) before
     * handing text to the speech engine - TTS has no idea these are
     * formatting symbols, so it was literally reading them aloud
     * mid-sentence ("asterisk asterisk...", "hash hash..."), breaking up
     * the flow. The on-screen chat text is untouched - only what's
     * spoken gets cleaned. */
    private fun stripMarkdownForSpeech(text: String): String {
        return text
            .replace(Regex("[*_#`]"), "")
            .replace(Regex("\\n{2,}"), ". ")
            .trim()
    }

    fun stop() {
        tts?.stop()
    }
}
