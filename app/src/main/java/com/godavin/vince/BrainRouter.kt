package com.godavin.vince

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    // Fix - passive memory capture (see MemoryExtractor.kt). Runs on the
    // side, off the coroutine that's building the actual reply, so an
    // extraction call never adds latency to the chat itself and a failure
    // in it can never affect the visible answer.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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

        // Fix - real up-to-date knowledge, not just date/time awareness.
        // Knowing today's date doesn't teach any model what's actually
        // happened since its training cutoff - that needs an actual
        // fetch. When the message sounds like it needs current
        // information, this pulls real web results (Tavily) and hands
        // them to the AI as grounding, same retrieval pattern that lets
        // Claude itself answer current-events questions. Silently
        // skipped if no Tavily key is saved, or if the message doesn't
        // look like it needs live info - kept narrow on purpose so
        // ordinary conversation stays fast and doesn't hit the network
        // for no reason.
        //
        // Fix - genuine multi-angle research for an explicit "research
        // X" style request (DeepResearch.kt), checked FIRST since it's a
        // stronger signal than the general needsSearch triggers below -
        // falls back to an ordinary single search if deep research comes
        // back empty (bad sub-queries, all searches failed) rather than
        // answering with nothing.
        val tavilyKey = ApiKeyStore.getKey(context, Provider.TAVILY)

        // Fix - real case that broke even WITH a valid Tavily key firing:
        // "when did they meet recently?" ran with topic "general" (SEO-
        // ranked, no recency filter) because "meet"/"recently" weren't on
        // the narrow NEWS_KEYWORDS list that decided topic - so Tavily
        // happily returned well-established 2017-2019 pages that outrank
        // a few-days-old article on pure relevance. Every ordinary search
        // in this file only ever fires because searchNeeded is true,
        // which by definition means the question is about something
        // current - so topic "news" + a hard 365-day recency cutoff
        // (days, see WebSearchTool.search) is now just how ALL of these
        // searches run, not a special case for a narrower keyword subset.
        val SEARCH_RECENCY_DAYS = 365

        // Fix - you asked not to have this depend on wording, and the
        // previous fix (asking the AI to self-judge "do I need a search")
        // still did, because that self-check has the same blind spot as
        // the original wrong answer: a model can't flag a knowledge gap
        // it doesn't know it has. So this no longer asks the model to
        // decide - any message that's phrased as a real question
        // (looksLikeQuestion: has "?" or starts with who/what/when/did/
        // etc) just searches directly, full stop, alongside the instant
        // keyword fast-path. You said you'd rather spend Tavily credits
        // than risk wrong info, so this is deliberately wide now - the
        // tradeoff is every question-shaped message spends a credit
        // (including ones that didn't strictly need one, e.g. straight
        // math or opinion questions), not just current-events-sounding
        // ones. At 1,000 free credits/month this isn't a real limit at
        // your current usage - if it ever needs narrowing back down,
        // that's a one-line change here.
        val keywordSearchNeeded = WebSearchTool.needsSearch(userMessage)
        val searchNeeded = keywordSearchNeeded ||
            (tavilyKey.isNotBlank() && WebSearchTool.looksLikeQuestion(userMessage))

        // Fix - the old grounding text ("use these instead of relying on
        // your training data") was a suggestion, not an instruction, so
        // the model could still blend in stale facts it "remembered"
        // alongside real search results (e.g. an old promo code mixed in
        // with current ones). This version explicitly tells it search
        // results OVERRIDE conflicting training-data recall, and now also
        // spells out how to read the [published: ...] date tag on each
        // result - so if an older result still slips through, the model
        // weighs it as stale instead of treating every result as equally
        // current.
        fun groundingPrefix(label: String) =
            "Real, $label web search results for this question, fetched just now - these are " +
                "ground truth. Each result is tagged with its actual publish date where known - " +
                "pay attention to those dates: a result published years ago does NOT override a " +
                "more recently published one, and if the most recent result already answers the " +
                "question, that is the answer, even if it contradicts an older result or your " +
                "own training-data recall. Do NOT blend in an older version of events you recall " +
                "independently when these results (especially the most recent ones) say " +
                "otherwise:\n"

        var relaxConciseness = false
        val searchBlock = if (tavilyKey.isNotBlank() && DeepResearch.isResearchRequest(userMessage)) {
            val deepResult = DeepResearch.research(context, userMessage)
            if (deepResult != null) {
                relaxConciseness = true
                "Real, multi-angle web research results for this question (several searches " +
                    "run across different angles of the topic - these are ground truth, trust " +
                    "them over conflicting training-data recall - synthesize a genuinely " +
                    "thorough answer from these, since a real research request deserves " +
                    "more than a one-paragraph summary; use headings/sections in plain text " +
                    "if that helps organize it, still no Markdown symbols):\n$deepResult"
            } else if (searchNeeded) {
                WebSearchTool.search(
                    tavilyKey, userMessage, topic = "news", days = SEARCH_RECENCY_DAYS
                ).getOrNull()?.takeIf { it.isNotBlank() }?.let {
                    groundingPrefix("current") + it
                } ?: ""
            } else {
                ""
            }
        } else if (tavilyKey.isNotBlank() && searchNeeded) {
            WebSearchTool.search(
                tavilyKey, userMessage, topic = "news", days = SEARCH_RECENCY_DAYS
            ).getOrNull()?.takeIf { it.isNotBlank() }?.let {
                groundingPrefix("current") + it
            } ?: ""
        } else {
            ""
        }

        // Fix - the actual bug that let "when did they meet recently?"
        // (a message that MATCHED the keyword trigger list outright)
        // still answer wrong with zero warning: this disclaimer used to
        // only fire when searchAttempted was true, and searchAttempted
        // was ONLY ever set inside a tavilyKey.isNotBlank() branch. So if
        // no Tavily key is saved in Settings at all, search is skipped,
        // searchAttempted silently stays false, and this disclaimer never
        // fires either - VINCE fell straight through to a fully confident
        // answer from frozen training data with no caveat whatsoever,
        // regardless of how obviously the question needed a live check.
        // Now this is driven by searchNeeded (whether the QUESTION needed
        // a search) rather than by whether a key happened to be present,
        // so a missing/failed key can no longer disable the safety net
        // that's supposed to catch exactly this case.
        val uncertaintyDisclaimer = if (searchNeeded && searchBlock.isBlank()) {
            if (tavilyKey.isBlank()) {
                "This question is about something current/recent, but no web search is " +
                    "configured at all right now (no Tavily API key saved in Settings) - do " +
                    "NOT confidently state facts about current officeholders, recent events, " +
                    "meetings, deaths, or anything that may have changed since your training " +
                    "cutoff. Say plainly you have no way to confirm the latest without a live " +
                    "search, and if useful, give your best training-data answer with a clear " +
                    "caveat that it may be outdated, rather than stating it as settled fact."
            } else {
                "This question is about something current/recent, but the live web check just " +
                    "now failed or timed out - do NOT confidently state facts about current " +
                    "officeholders, recent events, meetings, deaths, or anything that may have " +
                    "changed since your training cutoff. Say plainly you can't confirm the " +
                    "latest right now and, if useful, give your best training-data answer with " +
                    "a clear caveat that it may be outdated, rather than stating it as settled " +
                    "fact."
            }
        } else {
            ""
        }

        val styleInstruction = if (relaxConciseness) {
            "Formatting rules for your reply: no Markdown syntax (no #, ##, **, tables with " +
                "| or ---) since this is a plain chat bubble with no Markdown rendering - " +
                "write in plain natural sentences/paragraphs instead. This one IS an explicit " +
                "research request, so a genuinely thorough, well-organized answer is what's " +
                "wanted here - don't artificially shorten it."
        } else {
            RESPONSE_STYLE_INSTRUCTION
        }

        val contextBlock = listOf(
            persona.roleDescription, styleInstruction, currentDateGrounding(),
            memoryBlock, searchBlock, uncertaintyDisclaimer
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")

        // Fix - passive cross-chat memory capture (MemoryExtractor.kt).
        // Fire-and-forget on the side: never awaited, never blocks the
        // reply below, and any failure inside is swallowed - best-effort
        // only, exactly like StructuredMemory's own save() already is.
        backgroundScope.launch {
            try {
                MemoryExtractor.extractAndSave(context, userMessage)
            } catch (e: Exception) {
                // best-effort - a failed extraction must never affect the chat
            }
        }

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
