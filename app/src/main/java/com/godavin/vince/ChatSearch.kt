package com.godavin.vince

import android.content.Context

/**
 * v2.8 - WhatsApp-style search across every chat. One hit = one message (or a chat title) that contains ALL the words
 * typed, in any order, ignoring capitals. Newest first. Tapping a hit opens that chat at exactly that message
 * (ChatScreen scrolls to it and flashes it).
 */
class SearchHit(
    val threadId: String,
    val threadTitle: String,
    /** null = the chat's TITLE matched (open the chat at the end as usual). */
    val message: ChatMessage?,
    val snippet: String,
    val timestamp: Long
)

object ChatSearch {

    fun tokens(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }

    fun search(context: Context, query: String, limit: Int = 100): List<SearchHit> {
        val toks = tokens(query)
        if (toks.isEmpty()) return emptyList()
        val phrase = query.trim().lowercase()
        val hits = ArrayList<SearchHit>()
        for (t in ConversationStore.getAllThreads(context)) {
            val title = t.title.lowercase()
            if (toks.all { title.contains(it) }) {
                hits.add(SearchHit(t.id, t.title, null, t.title, t.updatedAt))
            }
            for (m in t.messages) {
                if (m.text.isBlank()) continue
                val low = m.text.lowercase()
                if (!toks.all { low.contains(it) }) continue
                // centre the snippet on the whole phrase if it appears, else on the first word
                val at = low.indexOf(phrase).takeIf { it >= 0 } ?: low.indexOf(toks.first())
                val start = (at - 40).coerceAtLeast(0)
                val end = (at + phrase.length + 70).coerceAtMost(m.text.length)
                val body = m.text.substring(start, end).replace(Regex("\\s+"), " ").trim()
                val snippet = (if (start > 0) "\u2026" else "") + body + (if (end < m.text.length) "\u2026" else "")
                hits.add(SearchHit(t.id, t.title, m, snippet, m.timestamp))
            }
        }
        return hits.sortedByDescending { it.timestamp }.take(limit)
    }
}
