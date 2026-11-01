package com.godavin.vince

/**
 * v2.13.2 - a HARD cap, enforced in code rather than in the prompt.
 *
 * Real failure (Oct 2026): the grey line under Clara's reply said "No usable web results came back", while the
 * reply itself said "I ran a live web search just now ... KSh 35-45". The prompt rules (HONESTY_RULE, the
 * uncertainty disclaimer) were ignored, and earlier wrong replies in the thread made the model stick to them.
 * A prompt is a request; this file is a guarantee:
 *
 *  1. Lookup question + search found nothing  -> the model is NOT called. A fixed honest reply is returned
 *     (unless the user explicitly asks for a "guess", which is allowed but labelled unverified).
 *  2. "Did you search?" questions             -> answered from the stored record of the last reply, not by the model.
 *  3. Any reply written without live results  -> sentences that claim a search / sources / live data are removed.
 *  4. Old replies in the thread                -> unverified numbers and search claims are scrubbed from the transcript
 *     before the model sees it, so it cannot keep repeating or "sticking to" an earlier wrong answer.
 */
object AnswerGuard {

    const val NO_RESULTS_REPLY =
        "I couldn't pull any reliable web results for that, so I won't give you a number or pretend I checked. " +
            "Rephrase it more specifically, say \"retry\" to search again, or say \"guess\" and I'll give a rough " +
            "estimate from general knowledge, clearly marked as unverified."

    const val NO_KEY_REPLY =
        "Live search isn't set up (no Tavily key in Settings), so I can't check that online and I won't state a " +
            "figure as fact. Add a Tavily key, or say \"guess\" for a rough estimate from general knowledge, " +
            "marked as unverified."

    private const val WITHHELD = "(my earlier answer here was not verified online, so it is not repeated)"
    private const val ESTIMATE_TAG = "Unverified estimate (no web results, from general knowledge): "
    private const val STRIPPED_TAG = "(Not verified online.) "

    // ---- what counts as a "lookup": a question whose answer is a real-world fact that must not be invented ----
    private val FACT_REGEX = Regex(
        "\\b(how much|how many|price|prices|cost|costs|fare|fares|fee|fees|charge|charges|who is|who was|who won|" +
            "when is|when does|when did|when was|where is|what time|opening hours|schedule|score|scores|" +
            "in stock|exchange rate|salary|how long does)\\b",
        RegexOption.IGNORE_CASE
    )
    private val EXPLICIT_SEARCH = Regex(
        "\\b(search|look\\s?up|google|check online|online|retry|try again|search again|rerun|re-run)\\b",
        RegexOption.IGNORE_CASE
    )
    private val WANTS_GUESS = Regex(
        "\\b(guess|estimate|ballpark|roughly|rough idea|from memory|from what you know|your best guess|" +
            "general idea|without searching)\\b",
        RegexOption.IGNORE_CASE
    )

    // opinion / advice questions ("how much should I risk per trade") are not lookups unless the user said "search"
    private val ADVICE = Regex(
        "\\b(should i|should we|do you think|your opinion|your view|advice|advise|recommend|what would you|help me|" +
            "how do i|how can i)\\b",
        RegexOption.IGNORE_CASE
    )

    fun isLookup(userMessage: String): Boolean {
        if (EXPLICIT_SEARCH.containsMatchIn(userMessage)) return true
        if (ADVICE.containsMatchIn(userMessage)) return false
        return FACT_REGEX.containsMatchIn(userMessage) || WebSearchTool.needsSearch(userMessage)
    }

    fun wantsGuess(userMessage: String): Boolean = WANTS_GUESS.containsMatchIn(userMessage)

    // ---- sentences that claim a search / sources / live data ----
    private val CLAIM = Regex(
        "(?i)(\\bi (just )?(ran|did|performed|pulled|checked|searched|looked|found|fetched|used)\\b[^.!?\\n]*" +
            "\\b(search|web|online|internet|sources?|results?|live)\\b" +
            "|\\blive (web )?(search|results|data)\\b" +
            "|\\b(recent|live|web|online|latest) sources?\\b" +
            "|\\baccording to (the )?(web|sources?|search|results|latest)\\b" +
            "|\\bsources? (say|show|list|confirm|indicate)\\b" +
            "|\\bthe (search|results) (show|says?|list|confirm|found)\\b" +
            "|\\bfresh(ly)? (search|results)\\b)"
    )
    private val SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+")

    /** Removes sentences that claim a search or sources. Returns the cleaned text and whether anything was removed. */
    fun stripClaims(text: String): Pair<String, Boolean> {
        var removed = false
        val lines = text.lines().map { line ->
            line.split(SENTENCE_SPLIT).filter { s ->
                val bad = CLAIM.containsMatchIn(s)
                if (bad) removed = true
                !bad
            }.joinToString(" ").trim()
        }
        val cleaned = lines.joinToString("\n").replace(Regex("\n{3,}"), "\n\n").trim()
        return cleaned to removed
    }

    /** Last step on every reply. [stripClaims]: no live results were used for this reply. [estimate]: user asked to guess. */
    fun finalize(reply: String, stripClaims: Boolean, estimate: Boolean): String {
        var out = reply
        var removed = false
        if (stripClaims) {
            val (cleaned, r) = stripClaims(reply)
            out = cleaned
            removed = r
            if (out.isBlank()) return NO_RESULTS_REPLY
        }
        if (estimate) return ESTIMATE_TAG + out.removePrefix(STRIPPED_TAG)
        if (removed) return STRIPPED_TAG + out
        return out
    }

    // ---- "did you search?" is answered from the record, never by the model ----
    private val META_SEARCH = Regex(
        "(?i)\\b((did|have|has|do|are) you (really |actually |even |just )?(search|searched|searching|look(ed)? (it )?up|" +
            "check(ed)? online|use (the )?(web|internet|search))|are you sure (you )?(searched|that)|" +
            "where did you (get|find|see) (that|this|it)|(what('s| is| are| were)|which|show me) (your |the )?sources?)\\b"
    )

    fun metaSearchAnswer(userMessage: String, history: List<ChatMessage>): String? {
        if (!META_SEARCH.containsMatchIn(userMessage)) return null
        val last = history.lastOrNull { !it.fromUser } ?: return null
        val src = last.sources?.trim().orEmpty()
        return when {
            src.startsWith("Sources:") || src.startsWith("Searched the web") ->
                "Yes. That answer used a live web search. " + src.replace(Regex("\\s+"), " ")
            src.startsWith("No usable web results") ->
                "No. Nothing usable came back from the web for that one, so that answer was not backed by a search " +
                    "and should be treated as unverified. Say \"retry\" and I'll search again."
            src.startsWith("Live search is not set up") ->
                "No. Live search isn't set up (no Tavily key), so that answer was not checked against the web."
            else -> null
        }
    }

    // ---- scrub the transcript so earlier unverified answers can't be repeated or defended ----
    private val HAS_FIGURE = Regex("\\d")

    fun scrubHistory(history: List<ChatMessage>): List<ChatMessage> = history.map { m ->
        if (m.fromUser) return@map m
        val src = m.sources?.trim().orEmpty()
        if (src.startsWith("Sources:") || src.startsWith("Searched the web")) return@map m   // really searched: keep as is
        val unverifiedSearch = src.startsWith("No usable web results") || src.startsWith("Live search is not set up")
        val (cleaned, removed) = stripClaims(m.text)
        when {
            unverifiedSearch && (removed || HAS_FIGURE.containsMatchIn(m.text)) -> m.copy(text = WITHHELD)
            removed -> m.copy(text = cleaned.ifBlank { WITHHELD })
            else -> m
        }
    }
}
