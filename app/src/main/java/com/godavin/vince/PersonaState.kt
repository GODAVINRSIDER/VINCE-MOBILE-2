package com.godavin.vince

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.godavin.vince.ui.theme.ClaraPink
import com.godavin.vince.ui.theme.DavinaPurple
import com.godavin.vince.ui.theme.VinceTeal

/**
 * The three personas - each gets a genuinely different system-prompt
 * tone AND a different TTS voice (pitch/rate), not just a different
 * color. Voice differentiation uses pitch/rate rather than picking named
 * voices by string, since which named voices exist varies a lot by
 * device/TTS engine - pitch/rate adjustments work identically on every
 * device with any TTS engine installed, same "don't depend on
 * device-specific voice availability" caution PC-VINCE's own SAPI5
 * fallback code already applied.
 *
 * roleDescription becomes part of what's sent to the AI (see BrainRouter)
 * so each persona actually reasons and responds differently, not just
 * LOOKS different - this is what resolves "call me differently per
 * persona": each persona's own instructions shape how it naturally
 * addresses the user, rather than a single fixed nickname forced onto
 * all three.
 */
enum class Persona(
    val displayName: String,
    val roleDescription: String,
    val pitch: Float,
    val rate: Float
) {
    VINCE(
        "VINCE",
        "You are VINCE, the analytical builder persona - focused, direct, technical, like a sharp chief of " +
            "staff and engineer. Your lane: trading and markets, systems and code, building and debugging the " +
            "user's own apps and tools, strategy and planning. Say what you actually think, challenge weak " +
            "plans, and offer the next move before being asked. Address the user efficiently, like a sharp " +
            "technical colleague.",
        pitch = 1.0f,
        rate = 1.0f
    ),
    CLARA(
        "CLARA",
        "You are CLARA, the warm, conversational persona. Your lane: people and everyday life, the user's " +
            "personal brand and business (content, customers, ideas), communication, motivation and how the " +
            "user is doing. A good listener who also gives a real opinion and a gentle nudge. Address the user " +
            "warmly and personally, like a trusted friend who tells the truth kindly.",
        pitch = 1.15f,
        rate = 0.95f
    ),
    DAVINA(
        "DAVINA",
        "You are DAVINA, the calm, intelligent, creative persona. Your lane: planning and focus, learning " +
            "things quickly (explain simply, quiz the user), creative ideas and naming, brainstorming and " +
            "playing devil's advocate on the user's plans, and reflective reviews. Curious and composed. " +
            "Offer alternative angles and a clear recommendation, like a sharp creative collaborator.",
        pitch = 0.9f,
        rate = 1.05f
    );

    fun color(): Color = when (this) {
        VINCE -> VinceTeal
        CLARA -> ClaraPink
        DAVINA -> DavinaPurple
    }

    companion object {
        fun fromName(name: String): Persona = values().find { it.name == name } ?: VINCE
    }
}

/**
 * Persists which persona is currently active - plain (non-encrypted)
 * SharedPreferences since this isn't sensitive, just a UI/behavior
 * choice, unlike the API keys in ApiKeyStore.
 */
object PersonaState {
    private const val PREFS_NAME = "vince_persona_prefs"
    private const val KEY_ACTIVE = "active_persona"

    fun getActive(context: Context): Persona {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val name = prefs.getString(KEY_ACTIVE, Persona.VINCE.name) ?: Persona.VINCE.name
        return Persona.fromName(name)
    }

    fun setActive(context: Context, persona: Persona) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACTIVE, persona.name)
            .apply()
    }
}
