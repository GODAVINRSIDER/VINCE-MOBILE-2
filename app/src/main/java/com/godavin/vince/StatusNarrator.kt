package com.godavin.vince

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * v2.6 - JARVIS/FRIDAY-style progress lines. While VINCE works (searching,
 * reading sources, switching to another brain, finding your location) it says
 * what it is doing - on screen and, if replies are spoken, out loud in the
 * active persona's voice - so it never sits silent.
 */
object Narration {
    private fun pick(persona: Persona, vince: String, clara: String, davina: String): String =
        when (persona) {
            Persona.VINCE -> vince
            Persona.CLARA -> clara
            else -> davina
        }

    fun searching(persona: Persona, query: String): String {
        val q = query.trim().replace(Regex("\\s+"), " ").split(" ").take(7).joinToString(" ").trim(',', '.', '?')
        return pick(
            persona,
            "Give me a moment, searching the web for $q.",
            "One second, I'm looking up $q for you.",
            "Hold on, pulling up the latest on $q."
        )
    }

    fun reading(persona: Persona, sites: List<String>): String {
        val who = when (sites.size) {
            0 -> return pick(persona, "Reading the results now.", "Going through what I found.", "Okay, reading it through.")
            1 -> sites[0]
            else -> "${sites[0]} and ${sites[1]}"
        }
        return pick(
            persona,
            "Found it. Reading $who.",
            "Got some results, looking through $who.",
            "Nice, checking $who now."
        )
    }

    fun researching(persona: Persona): String = pick(
        persona,
        "This one needs a proper dig. Checking several sources, give me a moment.",
        "Let me look at this from a few angles, bear with me.",
        "Okay, going deeper on this one, a few sources at once."
    )

    fun locating(persona: Persona): String = pick(
        persona,
        "Checking where you are.",
        "Let me check your location.",
        "Getting your location."
    )

    fun slow(persona: Persona): String = pick(
        persona,
        "That brain is slow, switching to another one.",
        "Taking a little longer, trying another route.",
        "Slight delay, trying a faster one."
    )
}

/**
 * Shows the latest status line through [onText] right away, and (when [speak] is
 * on) says it out loud only if VINCE is still busy after a short delay, at most
 * three times per reply. [close] ends it as soon as the real reply begins.
 */
class StatusNarrator(
    private val scope: CoroutineScope,
    private val persona: Persona,
    private val speak: Boolean,
    private val gate: () -> Boolean = { true },
    private val onText: (String?) -> Unit
) {
    private var job: Job? = null
    private var spoken = 0
    @Volatile private var closed = false

    fun update(text: String) {
        if (closed) return
        onText(text)
        if (!speak || spoken >= 3) return
        job?.cancel()
        val first = spoken == 0
        job = scope.launch {
            delay(if (first) 900L else 600L)
            if (closed || !gate()) return@launch
            VoiceOutput.speakChunk(text, persona, first = first)
            spoken++
        }
    }

    fun close() {
        closed = true
        job?.cancel()
        onText(null)
    }
}
