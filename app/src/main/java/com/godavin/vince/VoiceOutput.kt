package com.godavin.vince

import android.content.Context
import android.speech.tts.TextToSpeech
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

    fun init(context: Context) {
        if (tts != null) return
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                ready = true
            }
        }
    }

    fun speak(text: String, persona: Persona = Persona.VINCE) {
        if (!ready || text.isBlank()) return
        tts?.setPitch(persona.pitch)
        tts?.setSpeechRate(persona.rate)
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
