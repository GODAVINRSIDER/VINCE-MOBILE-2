package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import java.io.File

/**
 * v2.11 - standing lessons, same idea as VINCE 3.0's. When the user corrects VINCE ("don't say that",
 * "that's wrong", "stop doing X", "next time ..."), the correction is turned into ONE short rule that is
 * saved and followed in every chat from then on. The user can see them ("my lessons") and remove them
 * ("forget lesson 2", "clear lessons").
 */
object Lessons {
    private const val FILE_NAME = "vince_lessons.json"
    private const val MAX_LESSONS = 30

    private val CORRECTION = Regex(
        "(that'?s wrong|that is wrong|not what i (asked|said|meant|wanted)|don'?t (say|do|give|use|keep|show|mention|start|tell)|" +
            "stop (saying|doing|giving|using|showing|mentioning)|i (told|said) you|you keep|you always|" +
            "never (say|do|use|give|mention)|just (give|answer|tell|say)|why (did|do) you (say|keep|give)|" +
            "wrong (answer|rate|number|one)|next time)",
        RegexOption.IGNORE_CASE
    )

    private val SECRET = Regex(
        "(AIza[0-9A-Za-z_\\-]{20,}|sk-[A-Za-z0-9\\-_]{16,}|tvly-[A-Za-z0-9\\-_]{8,}|gsk_[A-Za-z0-9]{16,}|" +
            "\\bpassword\\b|\\bpasscode\\b|\\bpin (is|number)\\b)",
        RegexOption.IGNORE_CASE
    )

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun all(context: Context): List<String> {
        return try {
            val f = file(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(context: Context, list: List<String>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(it) }
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // best-effort
        }
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    fun add(context: Context, rule: String): Boolean {
        val clean = rule.trim().trimEnd('.').take(200)
        if (clean.length < 8 || SECRET.containsMatchIn(clean)) return false
        val list = all(context).toMutableList()
        val n = norm(clean)
        if (list.any { val o = norm(it); o == n || o.contains(n) || n.contains(o) }) return false
        list.add(clean)
        while (list.size > MAX_LESSONS) list.removeAt(0)
        save(context, list)
        return true
    }

    /** Text added to every prompt. Empty when there are no lessons. */
    fun block(context: Context): String {
        val l = all(context).takeLast(12)
        if (l.isEmpty()) return ""
        return "STANDING LESSONS - the user has corrected you before; follow these every time, without mentioning them: " +
            l.joinToString(" ") { it.trimEnd('.') + "." }
    }

    fun looksLikeCorrection(text: String) = CORRECTION.containsMatchIn(text)

    /** Fire-and-forget from BrainRouter: if the message is a correction, ask a tiny AI call for the one rule. */
    suspend fun learn(context: Context, userText: String, previousReply: String) {
        if (previousReply.isBlank() || !looksLikeCorrection(userText)) return
        val prompt = "A user corrected his personal AI assistant. Write the ONE standing rule the assistant should follow " +
            "from now on so this mistake never repeats. One short imperative sentence, specific (for example: " +
            "'When adding numbers, reply with just the answer in plain words, never show the operation.'). " +
            "If the message is not really a correction or a lasting preference, output []. " +
            "Output ONLY a JSON array like [\"rule\"].\n\n" +
            "ASSISTANT SAID: ${previousReply.take(600)}\nUSER SAID: ${userText.take(600)}"
        val out = MemoryExtractor.quickAsk(context, prompt)?.trim() ?: return
        val cleaned = out.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val rule: String? = try {
            val arr = JSONArray(cleaned)
            if (arr.length() > 0) arr.optString(0) else null
        } catch (e: Exception) {
            Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").find(cleaned)?.groupValues?.get(1)?.replace("\\\"", "\"")
        }
        if (!rule.isNullOrBlank() && add(context, rule)) {
            VinceLog.i(context, "lessons", "learned: ${rule.take(120)}")
        }
    }

    /** "my lessons", "what have you learned", "forget lesson 2", "clear lessons". Returns null if not about lessons. */
    fun handleCommand(context: Context, rawText: String): String? {
        val t = rawText.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        val list = all(context)

        if (Regex("^(please )?(clear|delete|remove|forget) (all |every |my )?(standing )?lessons?$").matches(t)) {
            if (list.isEmpty()) return "There are no lessons saved, so nothing to clear."
            save(context, emptyList())
            return "Done - I cleared all ${list.size} lessons."
        }
        Regex("^(please )?(forget|delete|remove) (standing )?lesson (\\d{1,2})$").find(t)?.let { m ->
            val n = m.groupValues[4].toInt()
            if (n < 1 || n > list.size) return "I only have ${list.size} lessons - say 'my lessons' to see them."
            val gone = list[n - 1]
            save(context, list.filterIndexed { i, _ -> i != n - 1 })
            return "Forgot lesson $n: $gone."
        }
        val asksList = Regex("^(please )?(show |list |what are )?(me )?(my )?(your )?(standing )?lessons$").matches(t) ||
            Regex("^(what|which) (lessons|rules) (have|did) you (learned?|learnt|saved?|picked up)( from me)?$").matches(t) ||
            Regex("^what have you learned( about me)?( so far)?$").matches(t)
        if (!asksList) return null

        val facts = StructuredMemory.getFacts(context)
        val sb = StringBuilder()
        if (list.isEmpty()) {
            sb.append("I have no standing lessons yet. When you correct me (\"don't do that\", \"that's wrong\", \"stop saying X\"), ")
            sb.append("I turn it into a rule and follow it from then on.")
        } else {
            sb.append("My standing lessons from your corrections:\n")
            list.forEachIndexed { i, s -> sb.append("${i + 1}. $s\n") }
            sb.append("Say 'forget lesson 2' to remove one, or 'clear lessons' to remove all.")
        }
        if (t.startsWith("what have you learned") && facts.isNotEmpty()) {
            sb.append("\n\nThings I remember about you: ").append(facts.take(8).joinToString("; "))
            if (facts.size > 8) sb.append("; and ${facts.size - 8} more")
            sb.append(".")
        }
        return sb.toString().trim()
    }
}
