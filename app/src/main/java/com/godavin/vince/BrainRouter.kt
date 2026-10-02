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

    // v2.3 - the user is a working trader: everything is about NOW. Stops the
    // personas dwelling on training cutoffs or dragging in 2024-and-earlier history.
    private const val NOW_ONLY_RULE =
        "Core rule: you are advising a working trader in the present moment. Never mention " +
            "your training data, knowledge cutoff, or lack of real-time access. Do not bring up " +
            "events, years or market conditions from 2024 or earlier unless the user explicitly " +
            "asks for history. Frame all advice, guidance and examples for today and what is " +
            "practical now."

    // Capabilities awareness - the personas were describing themselves as
    // "text-only" with no device access, because nothing in the prompt told
    // them what the app can actually do. Keep this list TRUTHFUL: update it
    // whenever a capability is added or removed (reminders, image generation,
    // etc.), or the personas will start claiming things the app can't do.
    private const val CAPABILITIES_INSTRUCTION =
        "About yourself: you live inside VINCE Mobile, an Android app, and you are NOT a " +
            "text-only chatbot. You can hear and speak (voice in and out), see through the " +
            "phone camera and screen, analyze uploaded images and trading charts (chart " +
            "replies can include a trade-idea card with entry, stop, target and risk-reward " +
            "read off the image), open apps and " +
            "websites, start timers, open maps, switch the flashlight, change volume, " +
            "control music playback, report battery level, open the dialer or a prefilled " +
            "text/WhatsApp draft for the user to send (you can look up a saved contact\'s number by name when contacts access is allowed), jump to Wi-Fi/Bluetooth/other " +
            "settings pages, give the time, date and economic calendar, " +
            "set reminders (e.g. 'remind me at 3pm to ...'), send session-open alerts, " +
            "news heads-ups, a daily briefing and an end-of-day journal check-in, keep the " +
            "user's trading rules and today's plan, search the web and run multi-step " +
            "research (when a search key is set), remember facts across every chat, float " +
            "as a widget over other apps, hold a hands-free continuous voice conversation (tap the mic once, keep talking, say end convo to finish), draw candlestick and smart-money patterns (morning star, engulfing, FVG, order block, BOS, CHoCH, liquidity sweep) accurately by code, and make in-app calls to PC-VINCE. You cannot " +
            "place or manage trades from the phone, cannot generate AI pictures (that was removed - you can only draw chart patterns by code), you are not linked to any trading " +
            "account (so you do not know balance, open trades or profit), you cannot place " +
            "calls or send messages by yourself (the user presses the button), cannot flip " +
            "Wi-Fi or Bluetooth directly, cannot read the user's messages or browse their contacts, cannot " +
            "control what happens inside other apps, and you have no live price feed. Never claim an " +
            "ability that is not listed here, and never describe yourself as lacking the ones " +
            "that are."

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
    private fun currentDateGrounding(mayGreet: Boolean): String {
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
            "and it comes from the phone's live clock, so anything you say about 'now', " +
            "'this year' or 'recent' is relative to it. " +
            "It's currently $timeOfDay where the user is. " + greetingRule(timeOfDay, mayGreet)
    }

    // Fix - replies kept opening with "Good morning/afternoon" every single
    // time. Greeting is now decided in code, not left to the model: the
    // time-of-day greeting is allowed only at the start of a conversation
    // (or after a long gap), and every later reply is told, explicitly, to
    // skip it and open the way a person mid-conversation would.
    private fun greetingRule(timeOfDay: String, mayGreet: Boolean): String {
        return if (mayGreet) {
            "This is the start of the conversation, so you may open with ONE short, natural " +
                "greeting that fits the $timeOfDay (the only greeting of this conversation) " +
                "and then answer."
        } else {
            "You are mid-conversation: do NOT open with any greeting or time-of-day phrase " +
                "(no good morning, good afternoon, good evening, hello, hey there). Go straight " +
                "into the answer like a person already talking, and vary how you start: react to " +
                "what was just said, pick up the thread, or just answer directly. Never open two " +
                "replies in a row the same way."
        }
    }

    // Greet only when the thread is brand new or has been quiet for 6+ hours.
    private fun mayGreet(history: List<ChatMessage>): Boolean {
        val lastReply = history.lastOrNull { !it.fromUser } ?: return true
        val quietMs = System.currentTimeMillis() - lastReply.timestamp
        return quietMs > 6 * 60 * 60 * 1000L
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
        // v2.3 - FORCED SEARCH. The model never decides whether to search.
        // forced = the message contains a search/recency word (search, look up,
        // latest, current, today, news, outlook...). wide = any real question or
        // substantive request. Only tiny small talk skips Tavily. Local commands
        // (time, reminders, phone actions) never reach this function at all.
        val forced = WebSearchTool.mustSearch(userMessage)
        val searchNeeded = forced || WebSearchTool.shouldSearch(userMessage)
        val timeSensitive = forced || WebSearchTool.needsSearch(userMessage)

        fun groundingPrefix() =
            "LIVE WEB RESULTS, fetched seconds ago. This is the newest information available and " +
                "it is the truth about the world right now. Each result is tagged with its publish " +
                "date when known; when results disagree, the most recently published one wins. " +
                "Build your answer from these. Never contradict them from memory. Never mention " +
                "training data, a knowledge cutoff or what you 'used to know'. Speak like someone " +
                "who is fully up to date, and give practical guidance for today, not for some past " +
                "year:\n"

        val searchQuery = WebSearchTool.buildQuery(userMessage, history)
        val recencyDays = if (forced) 120 else SEARCH_RECENCY_DAYS

        var relaxConciseness = false
        val searchBlock = if (tavilyKey.isNotBlank() && DeepResearch.isResearchRequest(userMessage)) {
            val deepResult = DeepResearch.research(context, userMessage)
            if (deepResult != null) {
                relaxConciseness = true
                "LIVE multi-angle web research, fetched seconds ago (several searches across " +
                    "different angles). This is the truth about the world right now; trust it over " +
                    "anything you recall, never mention training data or a cutoff, and synthesize a " +
                    "genuinely thorough answer, since a real research request deserves more than a " +
                    "one-paragraph summary. Use headings in plain text if that helps, still no " +
                    "Markdown symbols:\n$deepResult"
            } else {
                WebSearchTool.liveSearch(tavilyKey, searchQuery, recencyDays)
                    .getOrNull()?.takeIf { it.isNotBlank() }?.let { groundingPrefix() + it } ?: ""
            }
        } else if (tavilyKey.isNotBlank() && searchNeeded) {
            WebSearchTool.liveSearch(tavilyKey, searchQuery, recencyDays)
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { groundingPrefix() + it } ?: ""
        } else {
            ""
        }

        // Search was wanted but produced nothing (no key, network down, zero hits).
        // Only matters for time-sensitive asks; never talks about "training data".
        val uncertaintyDisclaimer = if (searchNeeded && searchBlock.isBlank() && timeSensitive) {
            if (tavilyKey.isBlank()) {
                "Live search is not set up (no Tavily key in Settings), so you could not pull live " +
                    "data for this message. Do not state specific current facts (prices, levels, " +
                    "who holds a post, latest events) as certain. Say in one short phrase that you " +
                    "could not pull live data and that adding a Tavily key in Settings fixes it, then " +
                    "help as far as you sensibly can. Never mention training data or a cutoff."
            } else {
                "The live search for this message just failed or returned nothing. Do not state " +
                    "specific current facts (prices, levels, who holds a post, latest events) as " +
                    "certain. Say in one short phrase that you could not pull live data just now and " +
                    "offer to try again, then help as far as you sensibly can. Never mention training " +
                    "data or a cutoff."
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
            persona.roleDescription, CAPABILITIES_INSTRUCTION, NOW_ONLY_RULE, styleInstruction,
            currentDateGrounding(mayGreet(history)), memoryBlock, searchBlock, uncertaintyDisclaimer
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
