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
 */
object VoiceOutput {
    private var tts: TextToSpeech? = null
    private var ready = false
    private var defaultVoice: Voice? = null
    // Cached once the engine is ready - a real male-labeled device voice
    // for VINCE, if this device's TTS engine exposes one. Named voices
    // vary by device/OEM, so this can legitimately be null.
    private var vinceMaleVoice: Voice? = null

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                defaultVoice = tts?.voice
                vinceMaleVoice = findMaleVoice()
                ready = true
            }
        }
    }

    private fun findMaleVoice(): Voice? {
        val voices = tts?.voices ?: return null
        return voices.firstOrNull {
            it.locale.language == "en" &&
                !it.isNetworkConnectionRequired &&
                it.name.contains("male", ignoreCase = true) &&
                !it.name.contains("female", ignoreCase = true)
        }
    }

    fun speak(text: String, persona: Persona = Persona.VINCE) {
        if (!ready || text.isBlank()) return

        if (persona == Persona.VINCE) {
            if (vinceMaleVoice != null) {
                // Real male-labeled device voice available - use it as-is,
                // persona.pitch/rate stays 1.0/1.0 (no need to distort a
                // voice that's already correct).
                tts?.voice = vinceMaleVoice
                tts?.setPitch(persona.pitch)
                tts?.setSpeechRate(persona.rate)
            } else {
                // No male-labeled voice on this device - fall back to the
                // default voice at a noticeably lower pitch, which is what
                // makes VINCE read as male even without one.
                tts?.voice = defaultVoice
                tts?.setPitch(0.75f)
                tts?.setSpeechRate(0.95f)
            }
        } else {
            // CLARA / DAVINA - unchanged: default voice, persona pitch/rate.
            // Explicitly reset to defaultVoice in case VINCE's male voice
            // was set on the previous turn (TTS.voice is sticky otherwise).
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
