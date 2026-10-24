package com.godavin.vince

/**
 * v2.11 - what the web search did for the reply that was just written, as one small grey line
 * under the answer. It comes from the search itself (real site names), never from the model, it is
 * not spoken, and it is not fed back to the AI.
 */
object SearchTrace {
    @Volatile private var current: String? = null

    const val NO_RESULTS = "No usable web results came back for this one - treat the answer as unverified."
    const val NO_KEY = "Live search is not set up (no Tavily key) - this answer is not checked against the web."

    fun reset() { current = null }
    fun set(line: String?) { current = line }

    /** Returns the line for the reply that just finished, and clears it. */
    fun take(): String? {
        val l = current
        current = null
        return l
    }

    fun forResults(hosts: List<String>, query: String): String {
        val sites = hosts.distinct().take(3)
        val q = query.replace("\"", "'").take(60)
        return if (sites.isEmpty()) "Searched the web (\"$q\")"
        else "Sources: " + sites.joinToString(" \u00B7 ") + "   (searched: \"$q\")"
    }
}
