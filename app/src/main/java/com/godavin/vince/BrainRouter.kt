package com.godavin.vince

import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

private typealias DeltaSink = (String) -> Unit

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

    // v2.7 - JARVIS/FRIDAY-style: offers a take and a next step without being asked, pushes back,
    // and is not only about trading.
    private const val PROACTIVE_STYLE =
        "Personality: you are a JARVIS/FRIDAY-style partner, not a passive assistant waiting for orders. " +
            "After you answer, when it genuinely helps, add ONE sharp opinion, a risk you can see, or a concrete " +
            "next step the user did not ask for, then stop. Give your honest take and push back politely when the " +
            "user looks wrong or about to make a mistake; never just agree to please. Do not end replies with " +
            "generic offers like 'let me know if you need anything'. Not everything is about trading: the user " +
            "also builds their own apps and tools, runs a brand and a business, studies, and has a life. Follow " +
            "the topic they are actually on, and keep it natural and brief, one idea at a time."

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
            "as a widget over other apps, hold a hands-free continuous voice conversation (tap the mic once, keep talking, say end convo to finish), operate the phone when Phone control is on (read the screen, tap, type, scroll, open apps, send WhatsApp/SMS or notification replies after the user says yes, and prepare/place MetaTrader 5 orders only from a complete user instruction after an explicit yes, with a lot cap), read incoming message notifications, scan QR codes (Wi-Fi codes it can add to the phone, links it can check, contacts, phone numbers and more; the user says 'scan QR' or taps Scan QR code on Home), play custom sounds for MetaTrader 5 mobile button presses, fills, TP, SL and closes (Settings > MT5 trade sounds), draw candlestick and smart-money patterns (morning star, engulfing, FVG, order block, BOS, CHoCH, liquidity sweep) accurately by code, make in-app calls to PC-VINCE, tell the user their exact location (street and area, coordinates, accuracy) and list the places they have been in the last 48 hours or 7 days when place memory is switched on, and send proactive morning, midday and evening check-ins at times set in Settings. You cannot " +
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
    //
    // v2.5 SPEED: cut from 60 messages, and older ones are shortened. (v2.6: 40.)
    // Fewer prompt tokens = every provider answers faster. The last few
    // messages stay in full; long-range facts live in StructuredMemory, and
    // the full history is still stored on-device. Raise this if recall in a
    // very long thread matters more to you than speed.
    private const val MAX_HISTORY_MESSAGES = 40
    private const val RECENT_FULL_MESSAGES = 4
    private const val RECENT_FULL_CHARS = 1500
    private const val OLDER_CHARS = 350

    // v2.5 speed settings
    private const val CHAT_MAX_TOKENS = 1500
    private const val RESEARCH_MAX_TOKENS = 3500
    private const val HEDGE_SECOND_MS = 1300L   // start the next provider if no reply yet
    private const val HEDGE_THIRD_MS = 3200L
    private const val OVERALL_TIMEOUT_MS = 14_000L
    private const val RESEARCH_TIMEOUT_MS = 45_000L   // long research answers need longer

    // Fix - passive memory capture (see MemoryExtractor.kt). Runs on the
    // side, off the coroutine that's building the actual reply, so an
    // extraction call never adds latency to the chat itself and a failure
    // in it can never affect the visible answer.
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun buildTranscript(history: List<ChatMessage>): String {
        if (history.isEmpty()) return ""
        val recent = history.takeLast(MAX_HISTORY_MESSAGES)
        return recent.mapIndexed { i, msg ->
            val label = if (msg.fromUser) "User" else Persona.fromName(msg.persona).displayName
            val limit = if (i >= recent.size - RECENT_FULL_MESSAGES) RECENT_FULL_CHARS else OLDER_CHARS
            "$label: ${msg.text.take(limit)}"
        }.joinToString("\n")
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

    private suspend fun prepare(
        context: Context,
        userMessage: String,
        persona: Persona,
        history: List<ChatMessage>,
        onStatus: ((String) -> Unit)? = null,
        allowSearch: Boolean = true
    ): Prepared {
        // Stage 13 - durable memory. Stage 14 - persona tone, so the
        // active persona actually reasons/responds differently, not just
        // displays a different name and color.
        val memoryBlock = StructuredMemory.buildContextBlock(context)
        // Shared hub - where the user left off on the PC (local cache, instant; see HubRepository).
        val hubBlock = HubRepository.hubBlock(context)

        // v2.6 - real-time location (and the 48h/7-day places) only when the message
        // is actually about where you are or have been.
        var locationBlock = ""
        var locationArea: String? = null
        if (LocationTools.wantsLocation(userMessage)) {
            onStatus?.invoke(Narration.locating(persona))
            val (block, area) = LocationTools.promptBlock(context, userMessage)
            locationBlock = block
            locationArea = area
        }

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
        val forced = allowSearch && WebSearchTool.mustSearch(userMessage)
        val searchNeeded = allowSearch && (forced || WebSearchTool.shouldSearch(userMessage))
        val timeSensitive = allowSearch && (forced || WebSearchTool.needsSearch(userMessage))

        fun groundingPrefix() =
            "LIVE WEB RESULTS, fetched seconds ago. This is the newest information available and " +
                "it is the truth about the world right now. Each result is tagged with its publish " +
                "date when known; when results disagree, the most recently published one wins. " +
                "Build your answer from these. Never contradict them from memory. Never mention " +
                "training data, a knowledge cutoff or what you 'used to know'. Speak like someone " +
                "who is fully up to date, and give practical guidance for today, not for some past " +
                "year:\n"

        val baseQuery = WebSearchTool.buildQuery(userMessage, history)
        val searchQuery = if (locationArea != null && LocationTools.wantsNearby(userMessage)) "$baseQuery $locationArea" else baseQuery
        val recencyDays = if (forced) 120 else SEARCH_RECENCY_DAYS

        var relaxConciseness = false
        val willSearch = allowSearch && tavilyKey.isNotBlank() && (DeepResearch.isResearchRequest(userMessage) || searchNeeded)
        if (willSearch) {
            onStatus?.invoke(
                if (DeepResearch.isResearchRequest(userMessage)) Narration.researching(persona)
                else Narration.searching(persona, searchQuery)
            )
        }
        val searchBlock = if (allowSearch && tavilyKey.isNotBlank() && DeepResearch.isResearchRequest(userMessage)) {
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
        } else if (allowSearch && tavilyKey.isNotBlank() && searchNeeded) {
            WebSearchTool.liveSearch(tavilyKey, searchQuery, recencyDays)
                .getOrNull()?.takeIf { it.isNotBlank() }?.let { groundingPrefix() + it } ?: ""
        } else {
            ""
        }

        if (willSearch && searchBlock.isNotBlank()) {
            onStatus?.invoke(Narration.reading(persona, WebSearchTool.lastSources))
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
            persona.roleDescription, PROACTIVE_STYLE, CAPABILITIES_INSTRUCTION, NOW_ONLY_RULE, styleInstruction,
            currentDateGrounding(mayGreet(history)), memoryBlock, hubBlock, locationBlock, searchBlock, uncertaintyDisclaimer
        )
            .filter { it.isNotBlank() }
            .joinToString(" ")

        // Fix - passive cross-chat memory capture (MemoryExtractor.kt).
        // Fire-and-forget on the side: never awaited, never blocks the
        // reply below, and any failure inside is swallowed - best-effort
        // only, exactly like StructuredMemory's own save() already is.
        HubRepository.noteExchange(context, userMessage, persona.name)
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

        return Prepared(fullMessage, if (relaxConciseness) RESEARCH_MAX_TOKENS else CHAT_MAX_TOKENS)
    }

    private class Prepared(val fullMessage: String, val maxTokens: Int)

    private class Backend(val name: String, val run: suspend (DeltaSink?) -> Result<String>)

    /** Whole reply at once (voice, widget, dashboard mic). */
    suspend fun sendMessage(
        context: Context,
        userMessage: String,
        persona: Persona = Persona.VINCE,
        history: List<ChatMessage> = emptyList(),
        onStatus: ((String) -> Unit)? = null,
        allowSearch: Boolean = true
    ): String = route(context, userMessage, persona, history, null, onStatus, allowSearch)

    /** v2.5 - same answer, but [onDelta] receives the text piece by piece as it is
     * written (called from a background thread). The full reply is still returned. */
    suspend fun sendMessageStreaming(
        context: Context,
        userMessage: String,
        persona: Persona = Persona.VINCE,
        history: List<ChatMessage> = emptyList(),
        onStatus: ((String) -> Unit)? = null,
        onDelta: (String) -> Unit
    ): String = route(context, userMessage, persona, history, onDelta, onStatus)

    private suspend fun route(
        context: Context,
        userMessage: String,
        persona: Persona,
        history: List<ChatMessage>,
        onDelta: DeltaSink?,
        onStatus: ((String) -> Unit)?,
        allowSearch: Boolean = true
    ): String {
        val prepared = prepare(context, userMessage, persona, history, onStatus, allowSearch)
        val backends = buildBackends(context, prepared)
        if (backends.isEmpty()) {
            return "No API keys saved yet - add at least one (Gemini, Groq, Mistral, Cloudflare or OpenRouter) in Settings."
        }
        val timeout = if (prepared.maxTokens > CHAT_MAX_TOKENS) RESEARCH_TIMEOUT_MS else OVERALL_TIMEOUT_MS
        val onSlow: (() -> Unit)? = if (onStatus == null) null else ({ onStatus(Narration.slow(persona)) })
        val (text, failures) = runRace(backends, onDelta, timeout, onSlow)
        if (text != null) return text
        return "All providers failed:\n" + failures.joinToString("\n")
    }

    /** v2.5 - healthy and fastest providers first. Each provider has its own
     * fast-fail settings; see GeminiClient.chat / OpenAiCompatibleClient.chat. */
    private fun buildBackends(context: Context, p: Prepared): List<Backend> {
        val msg = p.fullMessage
        val max = p.maxTokens
        val list = mutableListOf<Backend>()

        val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
        if (geminiKey.isNotBlank()) {
            list += Backend("Gemini") { sink ->
                if (sink == null) GeminiClient.chat(geminiKey, msg, max)
                else GeminiClient.chatStream(geminiKey, msg, max, sink)
            }
        }

        val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
        if (groqKey.isNotBlank()) {
            list += Backend("Groq") { sink ->
                val url = "https://api.groq.com/openai/v1/chat/completions"
                if (sink == null) {
                    OpenAiCompatibleClient.chat(url, groqKey, "openai/gpt-oss-120b", msg, "Groq", max, "low")
                } else {
                    OpenAiCompatibleClient.chatStream(url, groqKey, "openai/gpt-oss-120b", msg, "Groq", max, "low", sink)
                }
            }
        }

        // v2.7 - extra backup brains. Both speak the same "OpenAI-compatible" format as Groq.
        val mistralKey = ApiKeyStore.getKey(context, Provider.MISTRAL)
        if (mistralKey.isNotBlank()) {
            val model = BrainPrefs.mistralModel(context).ifBlank { BrainPrefs.MISTRAL_DEFAULT }
            list += Backend("Mistral") { sink ->
                val url = "https://api.mistral.ai/v1/chat/completions"
                if (sink == null) OpenAiCompatibleClient.chat(url, mistralKey, model, msg, "Mistral", max, null)
                else OpenAiCompatibleClient.chatStream(url, mistralKey, model, msg, "Mistral", max, null, sink)
            }
        }

        val cfKey = ApiKeyStore.getKey(context, Provider.CLOUDFLARE)
        val cfAccount = ApiKeyStore.getKey(context, Provider.CLOUDFLARE_ACCOUNT)
        if (cfKey.isNotBlank() && cfAccount.isNotBlank()) {
            val models = (listOf(BrainPrefs.cloudflareModel(context)) + BrainPrefs.CLOUDFLARE_DEFAULTS)
                .filter { it.isNotBlank() }.distinct()
            list += Backend("Cloudflare") { sink -> cloudflareChat(cfAccount, cfKey, models, msg, max, sink) }
        }

        val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
        if (openRouterKey.isNotBlank()) {
            list += Backend("OpenRouter") { sink ->
                // "openrouter/free" always resolves to whichever free model is
                // currently available, instead of a hardcoded name that goes stale.
                val url = "https://openrouter.ai/api/v1/chat/completions"
                if (sink == null) {
                    OpenAiCompatibleClient.chat(url, openRouterKey, "openrouter/free", msg, "OpenRouter", max, null)
                } else {
                    OpenAiCompatibleClient.chatStream(url, openRouterKey, "openrouter/free", msg, "OpenRouter", max, null, sink)
                }
            }
        }

        val order = ProviderHealth.order(list.map { it.name })
        return list.sortedBy { order.indexOf(it.name) }
    }

    /** Cloudflare Workers AI (OpenAI-compatible). The whole answer arrives at once; a retired model name
     * falls through to the next one in [models] instead of failing the provider. */
    private suspend fun cloudflareChat(
        account: String, token: String, models: List<String>, msg: String, max: Int, sink: DeltaSink?
    ): Result<String> {
        val url = "https://api.cloudflare.com/client/v4/accounts/$account/ai/v1/chat/completions"
        var last: Result<String> = Result.failure(Exception("Cloudflare: no model to try."))
        for (m in models) {
            val r = OpenAiCompatibleClient.chat(url, token, m, msg, "Cloudflare", max, null)
            if (r.isSuccess) {
                r.getOrNull()?.let { text -> sink?.invoke(text) }
                return r
            }
            last = r
            val why = r.exceptionOrNull()?.message.orEmpty()
            // only a bad/retired model name is worth trying the next model for
            if (!(why.contains("(400)") || why.contains("(404)") || why.contains("(422)"))) break
        }
        return last
    }

    /**
     * v2.5 - the provider race. The best provider starts immediately; if it has
     * not produced anything after HEDGE_SECOND_MS the next one starts alongside
     * it (and the third later), or sooner if one fails. The first to produce text
     * wins and the rest are cancelled on the spot. A failing provider no longer
     * makes you wait for it before the next one is tried.
     */
    private suspend fun runRace(
        backends: List<Backend>,
        onDelta: DeltaSink?,
        timeoutMs: Long = OVERALL_TIMEOUT_MS,
        onSlow: (() -> Unit)? = null
    ): Pair<String?, List<String>> = coroutineScope {
        val result = CompletableDeferred<String?>()
        val owner = AtomicReference<String?>(null)
        val partial = StringBuilder()
        val failures = ConcurrentLinkedQueue<String>()
        val remaining = AtomicInteger(backends.size)
        val triggers = backends.map { CompletableDeferred<Unit>() }

        triggers[0].complete(Unit)
        for (i in 1 until backends.size) {
            launch {
                delay(if (i == 1) HEDGE_SECOND_MS else HEDGE_THIRD_MS + (i - 2) * 1500L)
                if (i == 1 && !result.isCompleted && owner.get() == null) onSlow?.invoke()
                triggers[i].complete(Unit)
            }
        }

        backends.forEachIndexed { i, b ->
            launch {
                triggers[i].await()
                if (result.isCompleted) return@launch
                val t0 = System.currentTimeMillis()
                var gotFirst = false

                val sink: DeltaSink? = if (onDelta == null) null else (fun(d: String) {
                    owner.compareAndSet(null, b.name)
                    if (owner.get() == b.name) {
                        if (!gotFirst) {
                            gotFirst = true
                            ProviderHealth.recordSpeed(b.name, System.currentTimeMillis() - t0)
                        }
                        synchronized(partial) { partial.append(d) }
                        onDelta(d)
                    }
                })

                val r = b.run(sink)
                if (result.isCompleted) return@launch

                val failure = r.exceptionOrNull()
                if (failure == null) {
                    owner.compareAndSet(null, b.name)
                    if (owner.get() == b.name) {
                        if (!gotFirst) ProviderHealth.recordSpeed(b.name, System.currentTimeMillis() - t0)
                        ProviderHealth.markOk(b.name)
                        result.complete(r.getOrNull().orEmpty())
                    }
                } else {
                    ProviderHealth.markFailed(b.name)
                    failures.add("${b.name}: ${failure.message ?: "failed"}")
                    if (owner.get() == b.name) {
                        // died mid-reply: keep what was already shown
                        val shown = synchronized(partial) { partial.toString() }
                        result.complete(shown.ifBlank { null })
                    } else {
                        if (i + 1 < triggers.size) triggers[i + 1].complete(Unit)
                        if (remaining.decrementAndGet() == 0) result.complete(null)
                    }
                }
            }
        }

        val finished = withTimeoutOrNull(timeoutMs) { result.await() }
        val text: String? = if (result.isCompleted) finished else {
            val shown = synchronized(partial) { partial.toString() }
            if (shown.isNotBlank()) shown else {
                failures.add("Timed out waiting for a reply.")
                null
            }
        }
        // cut off whichever providers lost
        coroutineContext.cancelChildren()
        Pair(text, failures.toList())
    }
}
