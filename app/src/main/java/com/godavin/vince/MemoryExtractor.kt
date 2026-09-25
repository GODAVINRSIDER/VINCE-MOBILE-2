package com.godavin.vince

import android.content.Context

/**
 * Fix - "carrying memory over chats" was already half-true: StructuredMemory
 * IS shared across every thread already, but it only ever captured a fact
 * when Vincent explicitly typed "remember that X". Anything he just
 * mentioned in passing (a goal, a preference, a detail about his setup)
 * was gone the moment that thread scrolled past it - which is exactly why
 * manual Backup/Restore (a static export snapshot you have to remember to
 * run and restore yourself) never actually felt like it solved this.
 *
 * This adds automatic, passive fact-capture: after a user message that
 * looks like it might contain a durable personal fact, a small dedicated
 * AI call extracts anything genuinely worth remembering and saves it
 * through StructuredMemory.addFact - same dedup/cap rules as the explicit
 * "remember that" path, so this can't duplicate entries or grow unbounded.
 *
 * Deliberately narrow trigger (looksWorthChecking) and a single small
 * extraction call, not run on every message - keeps this fast/cheap and
 * out of the way of ordinary back-and-forth chat, and never runs at all
 * for a message the explicit "remember that"/"forget that" commands
 * already handle in StructuredMemory.
 */
object MemoryExtractor {

    private val SKIP_IF_CONTAINS = listOf(
        "remember that", "remember this", "what do you remember", "what do you know about me",
        "what have i told you", "forget that", "forget about"
    )

    private val PERSONAL_MARKERS = listOf(
        "i'm ", "im ", "i am ", "my ", "i've ", "ive ", "i work", "i trade", "i use",
        "i live", "i own", "i built", "i run", "i prefer", "i want to", "i plan",
        "i'm trying", "im trying", "i left", "i started", "i switched", "i moved",
        "my goal", "my target", "my plan", "i usually", "i always", "i never"
    )

    /** Cheap keyword check for whether this message is even worth spending
     * an extraction call on - not the extraction itself. */
    fun looksWorthChecking(text: String): Boolean {
        val lower = text.lowercase()
        if (lower.length < 8) return false
        if (SKIP_IF_CONTAINS.any { lower.contains(it) }) return false // explicit path already covers these
        return PERSONAL_MARKERS.any { lower.contains(it) }
    }

    /** Best-effort: runs a tiny extraction call and saves anything genuinely
     * new and durable. Safe to call from a fire-and-forget coroutine -
     * never throws past itself, and does nothing if no fact-worthy
     * language was in the message (skips the API call entirely). */
    suspend fun extractAndSave(context: Context, userMessage: String) {
        if (!looksWorthChecking(userMessage)) return

        val existing = StructuredMemory.getFacts(context)
        val existingBlock = if (existing.isEmpty()) "(none yet)" else existing.joinToString("; ")

        val prompt = "The user just said: \"$userMessage\"\n\n" +
            "Facts already saved about them: $existingBlock\n\n" +
            "If this message states a NEW durable personal fact worth remembering long-term " +
            "(their goals, preferences, setup, situation, plans - NOT small talk, NOT a " +
            "question, NOT something already saved above, NOT a one-off trading price or " +
            "market event), reply with ONLY that fact as one short plain sentence under 15 " +
            "words, no quotes, no preamble, no extra commentary. If there is nothing new and " +
            "durable worth saving, reply with exactly: NONE"

        val result = quickAsk(context, prompt)?.trim() ?: return
        if (result.isBlank() || result.equals("NONE", ignoreCase = true) || result.length > 200) return

        StructuredMemory.addFact(context, result)
    }

    /** Same minimal Gemini->Groq->OpenRouter fallback as DeepResearch's
     * quickAsk / TitleGenerator - a trivial single-shot call that doesn't
     * need persona tone or conversation history. */
    private suspend fun quickAsk(context: Context, prompt: String): String? {
        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            GeminiClient.sendMessage(geminiKey, prompt).getOrNull()?.let { return it }
        }
        val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
        if (groqKey.isNotBlank()) {
            OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "openai/gpt-oss-120b",
                userMessage = prompt,
                providerLabel = "Groq"
            ).getOrNull()?.let { return it }
        }
        val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (openRouterKey.isNotBlank()) {
            OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                model = "openrouter/free",
                userMessage = prompt,
                providerLabel = "OpenRouter"
            ).getOrNull()?.let { return it }
        }
        return null
    }
}
