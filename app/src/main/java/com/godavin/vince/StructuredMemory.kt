package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * VINCE Mobile's standalone durable memory - separate from
 * ConversationStore's per-thread chat history. This is for things that
 * shouldn't be scoped to just one conversation: "my weekly target is
 * $150" said in one thread should still be known in every other thread
 * and every future chat. Mirrors PC-VINCE's memory.py (profile +
 * freeform facts, auto-capture for name, explicit "remember that X" for
 * everything else) - this was always meant to be part of the original
 * standalone-brain stage and is being added properly now.
 */
object StructuredMemory {
    private const val FILE_NAME = "vince_structured_memory.json"
    private const val MAX_FACTS = 50

    private val NAME_PATTERN = Regex("(?:call me|my name is|i'm called|im called)\\s+([a-zA-Z][a-zA-Z\\s]{0,30})")

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): JSONObject {
        val f = file(context)
        if (f.exists()) {
            try {
                val data = JSONObject(f.readText())
                if (!data.has("profile")) data.put("profile", JSONObject())
                if (!data.has("facts")) data.put("facts", JSONArray())
                return data
            } catch (e: Exception) {
                // corrupt file - start fresh rather than crash
            }
        }
        return JSONObject().apply {
            put("profile", JSONObject())
            put("facts", JSONArray())
        }
    }

    private fun save(context: Context, data: JSONObject) {
        try {
            file(context).writeText(data.toString())
        } catch (e: Exception) {
            // best-effort - a failed save shouldn't crash the chat
        }
    }

    fun getPreferredName(context: Context): String? {
        val profile = load(context).getJSONObject("profile")
        return profile.optString("preferred_name").ifBlank { null }
    }

    private fun setPreferredName(context: Context, name: String) {
        val data = load(context)
        data.getJSONObject("profile").put("preferred_name", name)
        save(context, data)
    }

    private fun extractName(match: MatchResult): String {
        return match.groupValues[1].trim().split(Regex("\\s+")).take(3)
            .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
    }

    fun getFacts(context: Context): List<String> {
        val facts = load(context).getJSONArray("facts")
        return (0 until facts.length()).map { facts.getString(it) }
    }

    fun addFact(context: Context, factText: String): Boolean {
        val cleaned = factText.trim().trimEnd('.')
        if (cleaned.isBlank()) return false

        val data = load(context)
        val facts = data.getJSONArray("facts")

        for (i in 0 until facts.length()) {
            if (facts.getString(i).equals(cleaned, ignoreCase = true)) return true // already saved
        }

        facts.put(cleaned)
        if (facts.length() > MAX_FACTS) {
            val trimmed = JSONArray()
            for (i in 1 until facts.length()) trimmed.put(facts.getString(i))
            data.put("facts", trimmed)
        }

        save(context, data)
        return true
    }

    /** Removes the fact that best matches query (substring match, shortest
     * match wins if several contain it, so a broad query doesn't wrongly
     * delete a longer unrelated fact). Returns the removed text, or null. */
    fun removeFact(context: Context, query: String): String? {
        val q = query.trim().lowercase()
        if (q.isBlank()) return null

        val data = load(context)
        val facts = data.getJSONArray("facts")
        var bestMatch: String? = null

        for (i in 0 until facts.length()) {
            val f = facts.getString(i)
            if (f.lowercase().contains(q)) {
                if (bestMatch == null || f.length < bestMatch!!.length) bestMatch = f
            }
        }
        if (bestMatch == null) return null

        val rebuilt = JSONArray()
        for (i in 0 until facts.length()) {
            val f = facts.getString(i)
            if (f != bestMatch) rebuilt.put(f)
        }
        data.put("facts", rebuilt)
        save(context, data)
        return bestMatch
    }

    fun formatFactsForDisplay(context: Context): String {
        val facts = getFacts(context)
        val name = getPreferredName(context)

        if (facts.isEmpty() && name == null) {
            return "I don't have anything saved about you yet."
        }

        val parts = mutableListOf<String>()
        if (name != null) parts.add("I'm calling you $name")
        if (facts.isNotEmpty()) parts.add("here's what I've got: " + facts.joinToString("; "))
        return parts.joinToString(". ") + "."
    }

    /** Returns a short context block to prepend to every AI request so
     * durable facts are present regardless of which chat thread this is,
     * or '' if nothing's saved yet. */
    fun buildContextBlock(context: Context): String {
        val facts = getFacts(context)
        val name = getPreferredName(context)
        if (facts.isEmpty() && name == null) return ""

        val lines = mutableListOf<String>()
        if (name != null) lines.add("The user prefers to be called $name.")
        if (facts.isNotEmpty()) {
            lines.add("Known facts about the user, saved by their own request: " + facts.joinToString("; ") + ".")
        }
        return lines.joinToString(" ")
    }

    /** Entry point for the command router - returns a spoken reply if
     * text matched a memory command, else null. */
    fun handleMemoryCommand(context: Context, text: String): String? {
        val lower = text.lowercase()

        val nameMatch = NAME_PATTERN.find(lower)
        if (nameMatch != null) {
            val name = extractName(nameMatch)
            if (name.isNotBlank()) {
                setPreferredName(context, name)
                return "Got it, I'll call you $name."
            }
        }

        if (listOf(
                "what do you remember about me", "what do you know about me",
                "what have i told you to remember", "recall what you know about me"
            ).any { lower.contains(it) }
        ) {
            return formatFactsForDisplay(context)
        }

        val rememberMatch = Regex("remember (?:that )?(.+)").find(lower)
        if (rememberMatch != null) {
            val startIndex = rememberMatch.groups[1]!!.range.first
            val fact = text.substring(startIndex).trim() // original casing, not lowercased
            addFact(context, fact)
            return "Got it, I'll remember that."
        }

        val forgetMatch = Regex("forget (?:that |about )?(.+)").find(lower)
        if (forgetMatch != null) {
            val query = forgetMatch.groupValues[1].trim()
            val removed = removeFact(context, query)
            return if (removed != null) "Done, forgot that." else "I couldn't find anything matching that to forget."
        }

        return null
    }
}
