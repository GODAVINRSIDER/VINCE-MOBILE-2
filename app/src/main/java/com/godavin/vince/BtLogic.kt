package com.godavin.vince

/**
 * v2.15 - Bluetooth headset / earbuds logic. Pure Kotlin (no Android classes), so it is unit-testable on a plain JVM.
 * BtAudio.kt does the Android side (reading the audio devices, routing the mic, notices).
 *
 * How Android shows a Bluetooth headset: as an OUTPUT of type A2DP (music) and, when the phone-call profile (HFP) is connected, ALSO as an
 * INPUT of type SCO (or BLE headset on newer phones) - that input is the microphone. No such input = no mic that Android can use.
 */
object BtLogic {
    const val TYPE_SCO = 7
    const val TYPE_A2DP = 8
    const val TYPE_BLE_HEADSET = 26
    const val DEFAULT_NAME = "Bluetooth headset"

    data class Dev(val name: String, val isInput: Boolean, val type: Int)
    data class Headset(val name: String, val hasMic: Boolean, val hasOutput: Boolean, val hfp: Boolean)

    private fun isHeadsetType(t: Int) = t == TYPE_SCO || t == TYPE_A2DP || t == TYPE_BLE_HEADSET
    private fun isMicType(t: Int) = t == TYPE_SCO || t == TYPE_BLE_HEADSET

    /** One entry per Bluetooth headset, whatever number of endpoints Android lists for it. */
    fun group(devs: List<Dev>): List<Headset> {
        val by = LinkedHashMap<String, MutableList<Dev>>()
        for (d in devs) {
            if (!isHeadsetType(d.type)) continue
            val n = d.name.trim().ifBlank { DEFAULT_NAME }
            by.getOrPut(n.lowercase()) { mutableListOf() }.add(d.copy(name = n))
        }
        return by.values.map { list ->
            Headset(
                name = list.first().name,
                hasMic = list.any { it.isInput && isMicType(it.type) },
                hasOutput = list.any { !it.isInput },
                hfp = list.any { isMicType(it.type) }
            )
        }
    }

    fun lineFor(h: Headset): String =
        if (h.hasMic) {
            "${h.name}: connected, and its microphone is available. Say 'use my headset' and I'll listen through it - handy from across the room."
        } else {
            "${h.name}: connected, but only for music - Android doesn't see a microphone on it. Either these have no mic, or the phone-call " +
                "profile isn't connected (Settings > Connected devices > ${h.name} > make sure 'Phone calls' is on). I can still talk through " +
                "them, but I can't hear you through them."
        }

    fun describe(hs: List<Headset>, chosen: String): String {
        if (hs.isEmpty()) {
            return "I don't see a Bluetooth headset or earbuds connected right now. I'm using the phone's own mic and speaker. " +
                "Connect them in Bluetooth settings and say 'check my earbuds' again."
        }
        val using = if (chosen.isNotBlank()) "Right now I'm set to listen through $chosen when it's connected (and I fall back to the phone mic if it drops)."
        else "Right now I'm listening through the phone's own mic."
        return hs.joinToString("\n") { lineFor(it) } + "\n" + using
    }

    /** prev/cur = name -> has mic. The sentences to show when something changed. */
    fun diff(prev: Map<String, Boolean>, cur: Map<String, Boolean>, chosen: String): List<String> {
        val out = ArrayList<String>()
        fun isChosen(n: String) = chosen.isNotBlank() && n.equals(chosen, ignoreCase = true)
        for ((name, mic) in cur) {
            val before = prev[name]
            if (before == null) {
                out.add(
                    if (mic) (if (isChosen(name)) "$name is back - listening through it again."
                    else "$name connected, and it has a microphone. Say 'use my headset' and I'll listen through it.")
                    else "$name connected, but Android sees no microphone on it, so I can't hear you through it. Say 'check my earbuds' for what to try."
                )
            } else if (before != mic) {
                out.add(
                    if (mic) "$name's microphone just became available. Say 'use my headset' to listen through it."
                    else "$name's microphone went away (the phone switched profile)."
                )
            }
        }
        for (name in prev.keys) {
            if (!cur.containsKey(name)) {
                out.add("$name disconnected" + if (isChosen(name)) " - I switched back to the phone mic." else ".")
            }
        }
        return out
    }

    /** peak = loudest sample, 0..32767 */
    fun classifyLevel(peak: Int): String = when {
        peak < 150 -> "silent"
        peak < 800 -> "very_quiet"
        else -> "ok"
    }

    fun levelMessage(name: String, peak: Int): String = when (classifyLevel(peak)) {
        "silent" -> "I got NO sound from the $name mic during the 3-second test. If you weren't talking, say 'test my headset mic' again and keep talking. " +
            "Otherwise it may be muted, the earbuds may be in their case or not worn, or the phone is still on the phone mic."
        "very_quiet" -> "I can hear the $name mic but it's very quiet (level $peak). Speak closer to it."
        else -> "I hear you clearly through the $name mic (level $peak). Good to go."
    }

    // ---------------------------------------------------------------------------------------------------
    private const val DEV = "(?:bluetooth(?:\\s+(?:headset|headphones?|earbuds?|earphones?|earpods?|device|speaker))?|headset|head\\s?phones?|earbuds?|earphones?|ear\\s?pods?|airpods?|buds)"
    val CHECK = Regex(
        "^\\W*(?:please\\s+)?(?:(?:check|detect|scan|find|look\\s+for|show)\\s+(?:my\\s+|the\\s+|any\\s+)?" + DEV + "(?:\\s+(?:status|connection))?|" +
            "(?:is|are)\\s+(?:my\\s+|the\\s+)?" + DEV + "\\s+(?:connected|on|paired|working)|" +
            "do(?:es)?\\s+(?:my\\s+|the\\s+)?" + DEV + "\\s+have\\s+(?:a\\s+)?(?:mic|microphone)|" +
            "which\\s+(?:mic|microphone)\\s+are\\s+you\\s+(?:using|listening\\s+(?:to|through))|bluetooth\\s+status|audio\\s+devices)\\W*$",
        RegexOption.IGNORE_CASE
    )
    val USE = Regex(
        "^\\W*(?:please\\s+)?(?:use|switch\\s+to|connect\\s+to|listen\\s+(?:through|on|to|with)|talk\\s+(?:through|on|to))\\s+(?:my\\s+|the\\s+)?" + DEV +
            "(?:\\s+(?:mic|microphone))?\\W*$", RegexOption.IGNORE_CASE
    )
    val PHONE = Regex(
        "^\\W*(?:please\\s+)?(?:use|switch\\s+(?:back\\s+)?to|go\\s+back\\s+to|listen\\s+(?:through|on|to))\\s+(?:the\\s+|my\\s+)?(?:phone|phone's|built[- ]?in|default|normal)\\s*(?:mic|microphone|speakers?)?\\W*$|" +
            "^\\W*(?:please\\s+)?stop\\s+using\\s+(?:the\\s+|my\\s+)?" + DEV + "\\W*$", RegexOption.IGNORE_CASE
    )
    val COMPARE = Regex(
        "^\\W*(?:please\\s+)?(?:compare\\s+(?:my\\s+|the\\s+)?(?:mics?|microphones?)|(?:full|proper|complete)\\s+(?:(?:headset|earbuds?)\\s+)?mic\\s+(?:check|test)|" +
            "compare\\s+(?:my\\s+|the\\s+)?" + DEV + "\\s*(?:mic|microphone)?(?:\\s+(?:with|to|and)\\s+(?:the\\s+|my\\s+)?phone\\s*(?:mic|microphone)?)?)\\W*$", RegexOption.IGNORE_CASE
    )
    val RESET = Regex(
        "^\\W*(?:please\\s+)?(?:fix|reset|repair)\\s+(?:my\\s+|the\\s+)?(?:sound|audio)\\W*$|^\\W*(?:there\\s+is\\s+|there's\\s+)?no\\s+sound\\W*$|^\\W*i\\s+can(?:'?t|not)\\s+hear\\s+anything\\W*$",
        RegexOption.IGNORE_CASE
    )
    val TEST = Regex("^\\W*(?:please\\s+)?test\\s+(?:my\\s+|the\\s+)?" + DEV + "\\s*(?:mic|microphone)?\\W*$", RegexOption.IGNORE_CASE)
    val RESULT = Regex("^\\W*(?:please\\s+)?(?:(?:show\\s+|give\\s+me\\s+)?(?:the\\s+)?(?:headset|earbuds?|bluetooth)\\s+mic\\s+(?:test\\s+)?result|how\\s+did\\s+the\\s+(?:headset|earbuds?|bluetooth)\\s+(?:mic\\s+)?test\\s+go)\\W*$", RegexOption.IGNORE_CASE)

    /** Which command a (normalised) message is, or null. */
    fun kind(text: String): String? {
        val t = text.replace('\u2019', '\'').replace(Regex("\\s+"), " ").trim()
        return when {
            RESET.matches(t) -> "reset"
            COMPARE.matches(t) -> "compare"
            TEST.matches(t) -> "test"
            RESULT.matches(t) -> "result"
            PHONE.matches(t) -> "phone"
            USE.matches(t) -> "use"
            CHECK.matches(t) -> "check"
            else -> null
        }
    }
}
