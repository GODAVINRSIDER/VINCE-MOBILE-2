package com.godavin.vince

import android.content.Context

/**
 * Tries Gemini first, then Groq, then OpenRouter - falling through
 * automatically on a quota error, timeout, or any other failure, instead
 * of surfacing the first provider's error straight to the chat. This is
 * what actually fixes the "hit my quota limit" problem: one provider
 * being unavailable no longer stops VINCE from answering.
 *
 * A provider with no key saved is skipped silently (not treated as a
 * failure) - Settings decides which providers are even in the rotation.
 * Only if every provider with a saved key fails does this return a
 * combined error, so the chat screen can show something honest instead
 * of pretending everything's fine.
 */
object BrainRouter {

    // Response style fix - Vincent's feedback: replies were too long by
    // default, used Markdown syntax (##, **, tables with | and ---) that
    // just shows up as literal symbols in a plain chat bubble (this app
    // has no Markdown renderer), and the TTS voice was reading those
    // symbols/table dashes aloud too. Applied to every persona/provider
    // call, not just VINCE, since the complaint applies everywhere.
    private const val RESPONSE_STYLE_INSTRUCTION =
        "Formatting rules for your reply: keep it concise and to the point by default - " +
            "give the key takeaway in a few sentences, then ask if the user wants more " +
            "detail rather than front-loading everything. Never use Markdown syntax " +
            "(no #, ##, **, tables with | or ---, bullet dashes) since this is a plain " +
            "chat bubble with no Markdown rendering - write in plain natural sentences " +
            "instead, using line breaks for separate points if needed."

    // Stage 15 fix - real conversational memory. Every call to sendMessage
    // was previously completely stateless: only the current typed message
    // was ever sent, with zero awareness of anything said earlier in the
    // same thread - which is exactly why CLARA (and every persona) kept
    // losing track of what "that answer" or "multiply it by X" referred
    // to just one message later. Each provider call here is genuinely
    // single-shot (no server-side session), so the fix is to build the
    // recent conversation into the prompt text itself every time - the
    // model then has the actual back-and-forth to reason from, not just
    // the newest line in isolation.
    //
    // Bumped from 20 to 60 per Vincent's ask for it to remember "the
    // entire chat" - 60 is a real, meaningful increase (most
    // conversations never get that long), but not literally unbounded:
    // every message here rides along in the prompt text on EVERY single
    // API call, so an uncapped history in a very long-lived thread would
    // keep growing the prompt forever - slower replies, higher API cost,
    // and eventually hitting the model's actual context-window limit
    // outright. 60 messages is the practical ceiling for "remembers
    // basically everything relevant" without any of that. The separate,
    // real answer to "never lose anything, even years later" is the
    // Backup/export feature (BackupManager.kt) - that keeps the FULL,
    // uncapped history forever on-device (and restorable on a new
    // phone); this cap only governs how much rides along in a single AI
    // call, not what's actually stored.
    private const val MAX_HISTORY_MESSAGES = 60

    private fun buildTranscript(history: List<ChatMessage>): String {
        if (history.isEmpty()) return ""
        return history.takeLast(MAX_HISTORY_MESSAGES).joinToString("\n") { msg ->
            val label = if (msg.fromUser) "User" else Persona.fromName(msg.persona).displayName
            "$label: ${msg.text}"
        }
    }

    // Fix - Vincent noticed VINCE reasoning as if it were 2024/2025 in
    // regular conversation (not the explicit "what's the date" command,
    // which was always correct - RealTimeTools handles that locally).
    // The AI MODEL itself only knows whatever year its training data
    // ended around, and has no built-in awareness of real time - unless
    // told the actual current date, it defaults to that stale internal
    // assumption whenever a date/year comes up in ordinary reasoning
    // (market outlooks, "as of today," etc). This computes the real
    // date fresh off the phone's own clock on every single call and
    // states it plainly, so the model always has real ground truth
    // regardless of provider or how out of date its training is.
    private fun currentDateGrounding(): String {
        val fmt = java.text.SimpleDateFormat("EEEE, MMMM d, yyyy", java.util.Locale.getDefault())
        val now = java.util.Calendar.getInstance()
        val hour = now.get(java.util.Calendar.HOUR_OF_DAY)
        // Real local time-of-day off the phone's own clock - lets the
        // model naturally say "good morning"/"good evening" etc. when a
        // greeting is actually called for, instead of a canned one-size
        // greeting or none at all. Deliberately phrased as awareness,
        // not an instruction to greet every single reply with it.
        val timeOfDay = when (hour) {
            in 5..11 -> "morning"
            in 12..16 -> "afternoon"
            in 17..20 -> "evening"
            else -> "late night"
        }
        return "The real current date is ${fmt.format(java.util.Date())} - treat this as fact " +
            "regardless of what your own training data suggests the current date/year is, " +
            "since your training has a cutoff and this is the phone's actual live clock. " +
            "It's currently $timeOfDay where the user is - use a natural greeting matching " +
            "that time of day when a greeting is actually called for (e.g. the start of a " +
            "conversation), not forced into every single reply."
    }

    suspend fun sendMessage(
        context: Context,
        userMessage: String,
        persona: Persona = Persona.VINCE,
        history: List<ChatMessage> = emptyList()
    ): String {
        val attempts = mutableListOf<String>()

        // Stage 13 - durable memory. Stage 14 - persona tone, so the
        // active persona actually reasons/responds differently, not just
        // displays a different name and color.
        val memoryBlock = StructuredMemory.buildContextBlock(context)
        val contextBlock = listOf(persona.roleDescription, RESPONSE_STYLE_INSTRUCTION, currentDateGrounding(), memoryBlock)
            .filter { it.isNotBlank() }
            .joinToString(" ")

        val transcript = buildTranscript(history)

        val fullMessage = buildString {
            if (contextBlock.isNotBlank()) {
                append(contextBlock)
                append("\n\n")
            }
            if (transcript.isNotBlank()) {
                append("Conversation so far in this thread (most recent messages):\n")
                append(transcript)
                append("\n\n")
            }
            append("User: $userMessage")
        }

        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            val result = GeminiClient.sendMessage(geminiKey, fullMessage)
            result.onSuccess { return it }
            attempts.add("Gemini: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
        if (groqKey.isNotBlank()) {
            val result = OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://api.groq.com/openai/v1/chat/completions",
                apiKey = groqKey,
                model = "openai/gpt-oss-120b",
                userMessage = fullMessage,
                providerLabel = "Groq"
            )
            result.onSuccess { return it }
            attempts.add("Groq: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (openRouterKey.isNotBlank()) {
            val result = OpenAiCompatibleClient.sendMessage(
                baseUrl = "https://openrouter.ai/api/v1/chat/completions",
                apiKey = openRouterKey,
                // "openrouter/free" is OpenRouter's own router model - it always
                // resolves to whichever specific free model is currently available
                // on their end, rather than us hardcoding one exact free model
                // name that goes stale whenever THAT model gets rotated out
                // (which is exactly what broke here the first time).
                model = "openrouter/free",
                userMessage = fullMessage,
                providerLabel = "OpenRouter"
            )
            result.onSuccess { return it }
            attempts.add("OpenRouter: ${result.exceptionOrNull()?.message ?: "failed"}")
        }

        if (attempts.isEmpty()) {
            return "No API keys saved yet - add at least one (Gemini, Groq, or OpenRouter) in Settings."
        }

        return "All providers failed:\n" + attempts.joinToString("\n")
    }
}
