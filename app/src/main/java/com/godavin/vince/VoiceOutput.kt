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
 * VOICE SELECTION FOR VINCE - why this changed from name-matching to a
 * manual picker: the original approach searched installed voice names
 * for the literal word "male", but most engines (including Google's own,
 * the one on almost every Android phone) don't label voices that way at
 * all - they use opaque codes like "en-us-x-iom-local" with no gender
 * word in them. So that search was silently never finding anything and
 * always falling to the lower-pitch fallback. Rather than guess at
 * engine-specific naming schemes (fragile, breaks across OEMs/updates),
 * Settings now has a "VINCE Voice" section that lists every voice this
 * phone's engine actually has installed, lets you preview each one out
 * loud, and saves whichever one you pick. That's a one-time 30-second
 * task per device and it's guaranteed correct, instead of an automatic
 * guess that may or may not land.
 */
object VoiceOutput {
    private const val VOICE_PREFS = "vince_voice_prefs"
    private const val KEY_VINCE_VOICE_OVERRIDE = "vince_voice_override"

    private var tts: TextToSpeech? = null
    private var ready = false
    private var defaultVoice: Voice? = null
    private var appContext: Context? = null

    fun init(context: Context) {
        if (tts != null) return
        appContext = context.applicationContext
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.US
                defaultVoice = tts?.voice
                ready = true
            }
        }
    }

    /** English voice names this device's TTS engine actually has
     * installed - for the Settings "VINCE Voice" picker. Sorted so the
     * list is stable/scrollable rather than reshuffling each time. */
    fun availableVoiceNames(): List<String> {
        val voices = tts?.voices ?: return emptyList()
        return voices
            .filter { it.locale.language == "en" }
            .map { it.name }
            .distinct()
            .sorted()
    }

    /** Speaks [sampleText] using [voiceName] immediately, without saving
     * it - lets the Settings screen preview a voice before committing to
     * it for VINCE. */
    fun previewVoice(voiceName: String, sampleText: String = "This is what VINCE will sound like.") {
        if (!ready) return
        val voice = tts?.voices?.find { it.name == voiceName } ?: return
        tts?.voice = voice
        tts?.setPitch(1.0f)
        tts?.setSpeechRate(1.0f)
        tts?.speak(sampleText, TextToSpeech.QUEUE_FLUSH, null, null)
    }

    fun setVinceVoiceOverride(voiceName: String?) {
        appContext?.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_VINCE_VOICE_OVERRIDE, voiceName)
            ?.apply()
    }

    fun getVinceVoiceOverride(): String? {
        return appContext?.getSharedPreferences(VOICE_PREFS, Context.MODE_PRIVATE)
            ?.getString(KEY_VINCE_VOICE_OVERRIDE, null)
    }

    fun speak(text: String, persona: Persona = Persona.VINCE) {
        if (!ready || text.isBlank()) return

        if (persona == Persona.VINCE) {
            val overrideName = getVinceVoiceOverride()
            val overrideVoice = overrideName?.let { name -> tts?.voices?.find { it.name == name } }
            if (overrideVoice != null) {
                // Vincent manually picked this voice in Settings - use it
                // exactly as previewed, no pitch/rate distortion.
                tts?.voice = overrideVoice
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
            } else {
                // No voice picked yet - fall back to the default voice at
                // a noticeably lower pitch so VINCE still reads as male
                // until a real voice is chosen in Settings.
                tts?.voice = defaultVoice
                tts?.setPitch(0.75f)
                tts?.setSpeechRate(0.95f)
            }
        } else {
            // CLARA / DAVINA - unchanged: default voice, persona pitch/rate.
            // Explicitly reset to defaultVoice in case VINCE's chosen voice
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
