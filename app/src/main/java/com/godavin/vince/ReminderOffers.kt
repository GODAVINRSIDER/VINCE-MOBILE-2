package com.godavin.vince

/**
 * v2.14 - closes two reminder gaps. Pure Kotlin (no Android), so it is unit-testable on a plain JVM.
 *
 * 1. OFFER -> "yes": the AI suggests "want me to remind you at 3pm to check the retest?", he says "yes", and nothing was set, because
 *    ReminderCommands only understood messages that START with "remind me ...". Now the offer is remembered for 15 minutes and a plain
 *    yes/ok/set it turns it into a real "remind me ..." command.
 * 2. FALSE CLAIM: the AI says "I've set a reminder" / "I'll remind you at 3pm" in a normal reply, but only code can arm an alarm. Such a
 *    sentence is now made true (the time is read from it and the reminder is really set) or replaced by an honest line.
 */
object ReminderOffers {
    private const val TTL_MS = 15 * 60 * 1000L

    private var pendingCmd: String? = null
    private var pendingAt: Long = 0L

    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    // a sentence that OFFERS to set a reminder (a question or "I can ..."), not a statement about what the app can do in general
    private val OFFER = Regex(
        "\\b(?:want me to|would you like me to|do you want me to|should i|shall i|can i|could i|i can|i could|let me)\\b[^.?!]*" +
            "\\b(?:remind you|set (?:a |an )?(?:reminder|alarm)\\b|create (?:a )?reminder\\b)", OPTS
    )

    // a sentence that CLAIMS a reminder exists / will be set
    private val CLAIM = Regex(
        "\\b(?:i(?:'ve| have)\\s+(?:set|created|scheduled|added)\\b[^.?!]*\\breminder\\b|" +
            "i(?:'ll| will)\\s+(?:remind you|set (?:a |an )?reminder\\b)|" +
            "(?:a |the |your )?reminder\\s+(?:is|has been|was)\\s+(?:now\\s+)?set\\b|reminder set\\b|" +
            "i(?:'ve| have) set (?:that|it)\\b)", OPTS
    )

    private val AFTER = Regex(
        "(?:remind you|remind me|set (?:a |an )?(?:reminder|alarm)|create (?:a )?reminder|added (?:a )?reminder|set (?:a |an )?reminder)\\b\\s*(.*)$", OPTS
    )

    private val AFFIRM = Regex(
        "^(?:(?:yes|yeah|yep|yup|sure|ok|okay|alright)(?: please| do it| set it| set that| go ahead)?|do it|go ahead|set it|set that|" +
            "sounds good|please do|that works|lets do it|do that|yes please)$", OPTS
    )

    private fun norm(s: String) = s.replace('\u2019', '\'').lowercase().replace(Regex("[^a-z0-9' ]"), " ").replace(Regex("\\s+"), " ").trim()

    private fun sentences(text: String): List<String> = text.split(Regex("(?<=[.!?])\\s+")).filter { it.isNotBlank() }

    /** "Want me to remind you at 3pm to check the retest?" -> "remind me at 3pm to check the retest". Null when nothing follows the verb. */
    fun commandFrom(sentence: String): String? {
        val m = AFTER.find(sentence) ?: return null
        val rest = m.groupValues[1].trim().trimEnd('?', '.', '!', ' ').trim()
        return if (rest.isEmpty()) null else "remind me $rest"
    }

    /** Call with every finished AI reply: remembers an offer, or forgets the old one if this reply made none. */
    @Synchronized
    fun note(reply: String, now: Long = System.currentTimeMillis()) {
        val offer = sentences(reply).firstOrNull { OFFER.containsMatchIn(it) }
        val cmd = offer?.let { commandFrom(it) }
        pendingCmd = cmd
        pendingAt = now
    }

    /**
     * Call with the user's message BEFORE the reminder parser. A plain yes/ok/set it returns the "remind me ..." command to run (once);
     * any other message ends the offer.
     */
    @Synchronized
    fun takeForReply(userText: String, now: Long = System.currentTimeMillis()): String? {
        val cmd = pendingCmd
        val fresh = cmd != null && now - pendingAt < TTL_MS
        if (AFFIRM.matches(norm(userText))) {
            if (!fresh) return null
            pendingCmd = null
            return cmd
        }
        pendingCmd = null
        return null
    }

    /**
     * Replaces sentences that claim a reminder is set. setter(command) must return the app's own confirmation ("Done. I'll remind you ...")
     * or null if it could not be set. Only the first claim is acted on; any further claim sentences are dropped (no double reminders).
     */
    fun reconcileClaims(reply: String, setter: (String) -> String?): String {
        val parts = sentences(reply)
        if (parts.none { CLAIM.containsMatchIn(it) && !OFFER.containsMatchIn(it) }) return reply
        var handled = false
        val out = ArrayList<String>()
        for (s in parts) {
            if (CLAIM.containsMatchIn(s) && !OFFER.containsMatchIn(s)) {
                if (handled) continue
                handled = true
                val result = commandFrom(s)?.let { cmd -> setter(cmd) }
                val line = result ?: "I couldn't set that reminder - give me the time, like: remind me at 3pm to check the retest."
                out.add(if (line.last() in ".!?") line else "$line.")
            } else out.add(s)
        }
        return out.joinToString(" ")
    }
}
