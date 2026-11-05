package com.godavin.vince

import android.content.Context

/**
 * v2.14 - natural personality (same idea as VINCE 3.0's wit.py). The default is AUTO: VINCE reads the room itself and decides when a dry
 * or sarcastic line fits, JARVIS-style - no switch to flip. The dial (off / dry / auto / sharp) is only an override.
 * Two layers: the prompt describes the judgement; code adds a hard per-message gate (turnHint) so a message about a loss or someone feeling
 * low always gets a plain, kind reply no matter what the model would have done.
 */
object Wit {
    private const val PREFS = "vince_wit"
    private const val KEY = "level"
    val LEVELS = listOf("off", "dry", "auto", "sharp")
    const val DEFAULT = "auto"

    fun level(context: Context): String {
        val v = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, DEFAULT) ?: DEFAULT
        return if (v in LEVELS) v else DEFAULT
    }

    fun setLevel(context: Context, v: String): String {
        if (v in LEVELS) context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, v).apply()
        return level(context)
    }

    private const val LIMITS =
        "WIT LIMITS: no sarcasm or jokes in the same reply as a loss, a drawdown, a risk warning, a trade confirmation, an error or bad " +
            "news, or when he sounds tired, stressed or sad - then be plain and kind. Never joke about his intelligence, family, health or " +
            "money hardship, and never about self-harm. Wit never replaces the answer or hides a real concern. If he asks you to stop, stop."

    private fun flavour(name: String): String = when (name.lowercase()) {
        "vince" -> "Your humour is deadpan and economical."
        "clara" -> "Your humour is warm, playful teasing between friends - never cutting."
        "davina" -> "Your humour is calm and wry - a quiet one-liner."
        else -> ""
    }

    fun block(context: Context, personaName: String): String {
        return when (level(context)) {
            "off" -> "TONE: plain and serious. No sarcasm or jokes unless he jokes first."
            "dry" -> "TONE: dry, understated wit in the JARVIS/FRIDAY manner - at most ONE light remark, only when something genuinely invites it, " +
                "folded into the same sentence(s). Most replies need none. Never force a joke. ${flavour(personaName)} $LIMITS"
            "sharp" -> "TONE: sharp JARVIS/FRIDAY-style sarcasm, used with affection. When he does something plainly avoidable (repeats himself, " +
                "asks what he just answered, chases a revenge trade, ignores his own rule) tease him with ONE quick sarcastic line, then still " +
                "give the real answer. At most one per reply; many replies should have none. Aim the joke at the situation. ${flavour(personaName)} $LIMITS"
            else -> "TONE: natural, like JARVIS - read the room every turn and decide yourself. If he is joking, bantering or relaxed, match his " +
                "energy with ONE quick dry or sarcastic line. If he is focused on work, numbers, a build or a decision, stay crisp - humour only " +
                "if it is effortless. If he is stressed, tired, upset, or talking about a loss, money trouble or health, be plain, warm and steady " +
                "with no jokes. Humour is seasoning, never the meal: most replies need none, and never repeat the same kind of joke twice in a row. " +
                "${flavour(personaName)} $LIMITS"
        }
    }

    private val HEAVY = Regex(
        "\\b(lost|loss|losing|blew|blown|wiped|drawdown|stopped out|margin call|can'?t afford|broke|struggl\\w*|stress\\w*|anxi\\w*|depress\\w*|" +
            "sad|tired|exhaust\\w*|burn(?:ed|t)? ?out|overwhelm\\w*|worried|scared|panic\\w*|hurt|sick|funeral|died|passed away|crying|hopeless|give up|quit)\\b",
        RegexOption.IGNORE_CASE
    )
    private val JOKEY = Regex("(\\blol\\b|\\blmao\\b|\\bha(?:ha)+\\b|\\bjoke\\b|\\broast\\b|\\bbanter\\b|\\bkidding\\b|\\bjk\\b|\uD83D\uDE02|\uD83E\uDD23|\uD83D\uDE05)", RegexOption.IGNORE_CASE)

    /** Code-side gate for THIS message. Empty when nothing special applies. */
    fun turnHint(context: Context, userMessage: String): String {
        val lv = level(context)
        if (lv == "off") return ""
        if (HEAVY.containsMatchIn(userMessage)) {
            return "THIS TURN: he may be stressed or talking about a loss or something heavy - be plain, kind and steady. No jokes or sarcasm in this reply."
        }
        if ((lv == "auto" || lv == "sharp") && JOKEY.containsMatchIn(userMessage)) {
            return "THIS TURN: he is joking around - you may match it with ONE quick witty line, then still answer properly."
        }
        return ""
    }

    // ---------------------------------------------------------------------------------------------------
    private const val W = "(?:sarcasm|wit|humou?r)"
    private val SET = Regex("^\\W*(?:please\\s+)?(?:turn|set|put|switch|dial)\\s+(?:the\\s+|your\\s+)?" + W + "\\s+(?:level\\s+)?(?:to\\s+)?(off|none|dry|low|auto|natural|sharp|high|max)\\W*$", RegexOption.IGNORE_CASE)
    private val UPDOWN = Regex("^\\W*(?:please\\s+)?(?:turn|dial|bring)\\s+(up|down)\\s+(?:the\\s+|your\\s+)?" + W + "\\W*$", RegexOption.IGNORE_CASE)
    private val MORE = Regex("^\\W*(?:please\\s+)?(more|less|no|zero)\\s+" + W + "\\W*$|^\\W*(?:please\\s+)?(?:stop|cut)\\s+the\\s+" + W + "\\W*$", RegexOption.IGNORE_CASE)
    private val SARC = Regex("^\\W*(?:please\\s+)?(?:be\\s+)?(more|less)\\s+sarcastic\\W*$|^\\W*(?:please\\s+)?stop\\s+being\\s+sarcastic\\W*$", RegexOption.IGNORE_CASE)
    private val ASK = Regex("^\\W*(?:what(?:'s| is)\\s+)?(?:your\\s+|the\\s+)?" + W + "\\s+level\\W*$", RegexOption.IGNORE_CASE)

    private val MAP = mapOf("off" to "off", "none" to "off", "no" to "off", "zero" to "off", "dry" to "dry", "low" to "dry",
        "auto" to "auto", "natural" to "auto", "sharp" to "sharp", "high" to "sharp", "max" to "sharp")
    private val SAY = mapOf(
        "off" to "Okay, straight and serious from here. No jokes unless you start them.",
        "dry" to "Dry wit only - understated, and only when it earns its place.",
        "auto" to "Back to natural: I'll read the room and decide when a dry or sarcastic line fits, and keep it out of serious moments.",
        "sharp" to "Sharp it is. I'll keep it affectionate, and serious moments stay serious."
    )

    private fun step(context: Context, delta: Int): String {
        val i = LEVELS.indexOf(level(context)) + delta
        return LEVELS[i.coerceIn(0, LEVELS.size - 1)]
    }

    /** "turn up the sarcasm", "no sarcasm", "set sarcasm to auto", "what's your sarcasm level". Null if not about this. */
    fun handleCommand(context: Context, rawText: String): String? {
        val t = rawText.replace('\u2019', '\'').replace(Regex("\\s+"), " ").trim()
        SET.find(t)?.let { return SAY[setLevel(context, MAP[it.groupValues[1].lowercase()] ?: DEFAULT)] }
        UPDOWN.find(t)?.let {
            val cur = level(context)
            val new = setLevel(context, step(context, if (it.groupValues[1].lowercase() == "up") 1 else -1))
            return if (new == cur) "Already at the ${if (new == "sharp") "top" else "bottom"} - $new." else SAY[new]
        }
        MORE.find(t)?.let {
            val d = it.groupValues[1].lowercase()
            val target = when (d) { "no", "zero", "" -> "off"; "more" -> step(context, 1); else -> step(context, -1) }
            return SAY[setLevel(context, target)]
        }
        SARC.find(t)?.let {
            val d = it.groupValues[1].lowercase()
            val target = when (d) { "more" -> step(context, 1); "less" -> step(context, -1); else -> "off" }
            return SAY[setLevel(context, target)]
        }
        if (ASK.matches(t)) return "My wit is on ${level(context)}. Say 'turn up the sarcasm', 'turn it down', or 'set sarcasm to off / dry / auto / sharp'."
        return null
    }
}
