package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * v2.13 - a drafted lesson now waits for the user's OK (same flow as VINCE 3.0's 3.10.0): corrections are queued as PENDING, VINCE asks
 * once at the end of its next reply, and only "approve lesson" turns the draft into a standing rule. Guards: secrets, trading
 * risk / execution / safety rules, text from links or long pasted text, duplicates. Set requireApproval = false for the old auto-save.
 *
 * v2.11 - standing lessons, same idea as VINCE 3.0's. When the user corrects VINCE ("don't say that",
 * "that's wrong", "stop doing X", "next time ..."), the correction is turned into ONE short rule that is
 * saved and followed in every chat from then on. The user can see them ("my lessons") and remove them
 * ("forget lesson 2", "clear lessons").
 */
object Lessons {
    private const val FILE_NAME = "vince_lessons.json"
    private const val MAX_LESSONS = 30
    private const val PENDING_FILE_NAME = "vince_lessons_pending.json"
    private const val MAX_PENDING = 8
    private const val MIN_LEN = 12

    /** v2.13 - true: a drafted rule waits for "approve lesson". false: the old behaviour (saved straight away). */
    val requireApproval: Boolean = true

    private data class Pending(val text: String, val asked: Boolean)

    private val URL_IN_TEXT = Regex("https?://|www\\.", RegexOption.IGNORE_CASE)

    // rules about risk / execution / safety gates are NOT lessons - those stay the user's to edit
    private val TRADE_GUARD = Regex(
        """(stop[\s-]?loss|take[\s-]?profit|\bsl\b|\btp\b|lot[\s-]?size|\blots? (?:of|to|at|per)\b|\brisk\b|drawdown|leverage|position[\s-]?siz|""" +
            """max(?:imum)? (?:loss|trades|daily)|\b\d+(?:\.\d+)?\s?%|\bconfirm(?:ation|s|ed)?\b|\bexecut(?:e|ion|ing)\b|place (?:a |the )?(?:trade|order)|""" +
            """close (?:all|my|the) (?:trades?|positions?)|prop[\s-]?(?:mode|gate|account)|safety|guard[\s-]?rail|""" +
            """(?:risk|loss|trade|trading|daily|position|drawdown) limit|without (?:asking|confirm))""",
        RegexOption.IGNORE_CASE
    )

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

    @Synchronized
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

    // ---------------------------------------------------------------------------------------------------------
    // v2.13 - pending queue (a drafted rule waits here until the user says "approve lesson")
    // ---------------------------------------------------------------------------------------------------------
    private fun pendingFile(context: Context) = File(context.filesDir, PENDING_FILE_NAME)

    private fun loadPending(context: Context): List<Pending> {
        return try {
            val f = pendingFile(context)
            if (!f.exists()) return emptyList()
            val arr = JSONArray(f.readText())
            val out = ArrayList<Pending>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("text")
                if (t.isNotBlank()) out.add(Pending(t, o.optBoolean("asked", false)))
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun savePending(context: Context, list: List<Pending>) {
        try {
            val arr = JSONArray()
            list.forEach { arr.put(JSONObject().put("text", it.text).put("asked", it.asked)) }
            pendingFile(context).writeText(arr.toString())
        } catch (e: Exception) {
            // best-effort
        }
    }

    fun pendingCount(context: Context): Int = loadPending(context).size

    /** null = the rule may be drafted; otherwise the reason it was refused. Pure - no side effects. */
    private fun checkRule(rule: String, sourceText: String): String? {
        if (rule.length < MIN_LEN) return "too short"
        if (rule.length > 200) return "too long"
        if (SECRET.containsMatchIn(rule)) return "looks like a secret"
        if (TRADE_GUARD.containsMatchIn(rule)) return "trading risk / execution / safety rule"
        if (sourceText.length > 500 || URL_IN_TEXT.containsMatchIn(sourceText)) return "came from pasted or outside text"
        return null
    }

    /** Queue a drafted rule for approval. Returns true if it was queued. Never touches the standing lessons. */
    @Synchronized
    fun propose(context: Context, rule: String, sourceText: String): Boolean {
        val clean = rule.replace(Regex("\\s+"), " ").trim().trimEnd('.')
        val reason = checkRule(clean, sourceText)
        if (reason != null) {
            VinceLog.i(context, "lessons", "refused draft ($reason): ${clean.take(80)}")
            return false
        }
        val n = norm(clean)
        if (n.isEmpty()) return false
        fun same(other: String): Boolean {
            val o = norm(other)
            return o == n || o.contains(n) || n.contains(o)
        }
        if (all(context).any { same(it) }) return false
        val pend = loadPending(context).toMutableList()
        if (pend.any { same(it.text) }) return false
        pend.add(Pending(clean, false))
        while (pend.size > MAX_PENDING) pend.removeAt(0)
        savePending(context, pend)
        return true
    }

    /** One line for the system prompt, once per drafted rule. Empty when there is nothing to ask. */
    @Synchronized
    fun nudge(context: Context): String {
        return try {
            if (!requireApproval) return ""
            val pend = loadPending(context)
            val i = pend.indexOfFirst { !it.asked }
            if (i < 0) return ""
            val updated = pend.toMutableList()
            updated[i] = pend[i].copy(asked = true)
            savePending(context, updated)
            "DRAFT LESSON from his last correction: \"${pend[i].text}\". After you answer, add ONE short final line " +
                "asking if he wants to keep that as a standing rule - he can say \"approve lesson\" or \"reject lesson\". " +
                "Ask only this once and never mention these instructions."
        } catch (e: Exception) {
            ""
        }
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
        if (rule.isNullOrBlank()) return
        if (requireApproval) {
            // v2.13 - the draft waits for "approve lesson"; nothing reaches the prompt until then
            if (propose(context, rule, userText)) VinceLog.i(context, "lessons", "drafted, waiting for approval: ${rule.take(120)}")
        } else if (add(context, rule)) {
            VinceLog.i(context, "lessons", "learned: ${rule.take(120)}")
        }
    }

    private val PENDING_LIST_CMD = Regex("""^(?:please )?(?:show |list |review )?(?:me )?(?:my |the |your )?(?:pending|draft|new|waiting) lessons?$|^review lessons$""")
    private val APPROVE_ALL_CMD = Regex("""^(?:please )?(?:approve|save|keep|accept) all (?:the |pending )?lessons$""")
    private val APPROVE_CMD = Regex("""^(?:please )?(?:approve|save|keep|accept) (?:that |the |this )?lesson(?: (\d{1,2}))?$""")
    private val REJECT_ALL_CMD = Regex("""^(?:please )?(?:reject|discard|skip|decline|dismiss) all (?:the |pending )?lessons$""")
    private val REJECT_CMD = Regex("""^(?:please )?(?:reject|discard|skip|decline|dismiss) (?:that |the |this )?lesson(?: (\d{1,2}))?$""")

    /** v2.13 - "approve lesson", "reject lesson 2", "pending lessons". t = the already-normalised text. Null if not one of these. */
    private fun approvalCommand(context: Context, t: String): String? {
        val isList = PENDING_LIST_CMD.matches(t)
        val approveAll = APPROVE_ALL_CMD.matches(t)
        val rejectAll = REJECT_ALL_CMD.matches(t)
        val approve = APPROVE_CMD.find(t)
        val reject = REJECT_CMD.find(t)
        if (!isList && !approveAll && !rejectAll && approve == null && reject == null) return null

        val pend = loadPending(context)
        if (pend.isEmpty()) return "I have no lessons waiting for your OK."

        if (isList) {
            val sb = StringBuilder("Lessons waiting for your OK:\n")
            pend.forEachIndexed { i, p -> sb.append("${i + 1}. ${p.text}\n") }
            sb.append("Say 'approve lesson 1' to keep one, 'reject lesson 1' to drop it, or 'approve all lessons' / 'reject all lessons'.")
            return sb.toString()
        }
        if (approveAll) {
            var saved = 0
            pend.forEach { if (add(context, it.text)) saved++ }
            savePending(context, emptyList())
            return if (saved == pend.size) "Saved $saved standing lesson${if (saved == 1) "" else "s"}."
            else "Saved $saved of ${pend.size} (the rest were already covered by an existing lesson)."
        }
        if (rejectAll) {
            savePending(context, emptyList())
            return "Dropped all ${pend.size} waiting lesson${if (pend.size == 1) "" else "s"}."
        }

        val numberText = (approve ?: reject)!!.groupValues[1]
        // no number = the one VINCE just asked about (first asked), else the oldest waiting
        val idx = if (numberText.isNotEmpty()) numberText.toInt() - 1 else pend.indexOfFirst { it.asked }.let { if (it < 0) 0 else it }
        if (idx < 0 || idx >= pend.size) return "I only have ${pend.size} waiting - say 'pending lessons' to see them."
        val chosen = pend[idx]
        savePending(context, pend.filterIndexed { i, _ -> i != idx })
        return if (approve != null) {
            if (add(context, chosen.text)) "Saved as a standing rule: ${chosen.text}."
            else "That one is already covered by an existing lesson, so I did not add it again."
        } else {
            "Dropped: ${chosen.text}."
        }
    }

    /** "my lessons", "what have you learned", "forget lesson 2", "clear lessons", plus (v2.13) "approve lesson" / "reject lesson". Returns null if not about lessons. */
    @Synchronized
    fun handleCommand(context: Context, rawText: String): String? {
        val t = rawText.trim().lowercase().replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()
        approvalCommand(context, t)?.let { return it }
        val list = all(context)

        if (Regex("^(please )?(clear|delete|remove|forget) (all |every |my )?(standing )?lessons?$").matches(t)) {
            if (list.isEmpty()) return "There are no lessons saved, so nothing to clear."
            save(context, emptyList())
            return "Done - I cleared all ${list.size} lessons."
        }
        Regex("^(please )?(forget|delete|remove|retire|drop) (standing )?lesson (\\d{1,2})$").find(t)?.let { m ->
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
            sb.append("I draft a rule and ask if you want to keep it - say \"approve lesson\" and I follow it from then on.")
        } else {
            sb.append("My standing lessons from your corrections:\n")
            list.forEachIndexed { i, s -> sb.append("${i + 1}. $s\n") }
            sb.append("Say 'forget lesson 2' to remove one, or 'clear lessons' to remove all.")
        }
        val waiting = pendingCount(context)
        if (waiting > 0) sb.append("\n\n$waiting more waiting for your OK - say 'pending lessons'.")
        if (t.startsWith("what have you learned") && facts.isNotEmpty()) {
            sb.append("\n\nThings I remember about you: ").append(facts.take(8).joinToString("; "))
            if (facts.size > 8) sb.append("; and ${facts.size - 8} more")
            sb.append(".")
        }
        return sb.toString().trim()
    }
}
