package com.godavin.vince

import android.content.Context

/**
 * v2.4 - MetaTrader 5 trade instructions by voice/text, with hard rails.
 *
 * What VINCE will do: when YOU give a complete instruction (side, symbol, lots,
 * SL, TP) - or say "take that trade <lots>" after it showed a trade-idea card -
 * it repeats the exact numbers, waits for your "yes", fills the MT5 ticket,
 * verifies every number is really on screen, and only then taps Buy/Sell.
 * Saying "no need to confirm" in the same instruction skips the yes.
 *
 * What it will never do: invent a lot size, SL or TP; trade without a stop loss
 * and take profit; exceed your max lot; place a second trade within a minute;
 * tap Buy/Sell if the screen doesn't match; or run while this switch is off.
 * Start on a DEMO account. UI automation is not infallible.
 */
object Mt5Trader {
    private const val PREFS = "vince_mt5"
    private const val K_ENABLED = "enabled"
    private const val K_MAXLOT = "max_lot"

    @Volatile private var lastIdea: TradeIdeaData? = null
    @Volatile private var lastIdeaAt = 0L

    fun enabled(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, false)
    fun maxLot(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(K_MAXLOT, 0.02f).toDouble()

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ENABLED, on).apply()
    }

    fun setMaxLot(context: Context, lots: Double) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putFloat(K_MAXLOT, lots.toFloat()).apply()
    }

    /** Called when a trade-idea card is shown, so "take that trade" has something to refer to. */
    fun rememberIdea(idea: TradeIdeaData) {
        lastIdea = idea
        lastIdeaAt = System.currentTimeMillis()
    }

    private val ALIASES = mapOf(
        "gold" to "XAUUSD", "silver" to "XAGUSD", "nasdaq" to "NAS100", "nas100" to "NAS100", "us30" to "US30",
        "dow" to "US30", "spx" to "SPX500", "spx500" to "SPX500", "us500" to "US500", "oil" to "USOIL"
    )

    private fun num(s: String?) = s?.replace(",", "")?.toDoubleOrNull()

    fun handle(context: Context, text: String): String? {
        val lower = text.lowercase().replace('\u2019', '\'').trim().trimEnd('.', '!', '?')

        // ---- settings ----
        if (Regex("^(?:please\\s+)?(?:enable|turn on|switch on|activate)\\s+(?:mt5|metatrader)(?: trading| trade execution| execution)?$").matches(lower) ||
            lower == "enable trade execution") {
            setEnabled(context, true)
            return "MT5 trade execution is ON. Max lot is ${maxLot(context)}. Every trade needs a full instruction (side, symbol, lots, SL, TP) " +
                "and your yes. Test on a DEMO account first. Change the cap with \"set mt5 max lot to 0.05\"."
        }
        if (Regex("^(?:please\\s+)?(?:disable|turn off|switch off|deactivate)\\s+(?:mt5|metatrader)(?: trading| trade execution| execution)?$").matches(lower)) {
            setEnabled(context, false)
            return "MT5 trade execution is OFF."
        }
        Regex("^(?:please\\s+)?set\\s+(?:my\\s+)?(?:mt5\\s+|metatrader\\s+)?max(?:imum)?\\s+lot(?:\\s+size)?\\s+(?:to\\s+)?(\\d*\\.?\\d+)$").find(lower)?.let {
            val v = it.groupValues[1].toDoubleOrNull() ?: return "I couldn't read that lot size."
            if (v < 0.01 || v > 5.0) return "Pick a max lot between 0.01 and 5."
            setMaxLot(context, v)
            return "Max lot per trade set to $v."
        }
        if (lower == "mt5 status" || lower == "trading status") {
            return "MT5 execution: ${if (enabled(context)) "ON" else "OFF"}. Max lot: ${maxLot(context)}. " +
                "Phone control: ${if (VinceAccessibilityService.isRunning) "on" else "OFF (needed)"}."
        }

        // ---- a trade instruction? ----
        val side = Regex("\\b(buy|sell|long|short)\\b").find(lower)?.groupValues?.get(1)?.let {
            if (it == "buy" || it == "long") "Buy" else "Sell"
        }
        val lots = Regex("(\\d*\\.?\\d+)\\s*(?:lots?|lot size)\\b|\\blots?\\s*(?:of|=|:)?\\s*(\\d*\\.?\\d+)").find(lower)?.let {
            num(it.groupValues[1].ifBlank { it.groupValues[2] })
        }
        val sl = Regex("\\b(?:sl|stop ?loss|stop)\\s*(?:at|of|=|:)?\\s*(\\d+(?:[.,]\\d+)*)").find(lower)?.let { num(it.groupValues[1]) }
        val tp = Regex("\\b(?:tp|take ?profit|target)\\s*(?:at|of|=|:)?\\s*(\\d+(?:[.,]\\d+)*)").find(lower)?.let { num(it.groupValues[1]) }
        val explicitVerb = Regex("\\b(place|execute|take|enter|open|put|send)\\b").containsMatchIn(lower) || lower.contains("mt5") || lower.contains("metatrader")
        val refersToIdea = Regex("\\b(?:take|execute|place|enter|open)\\s+(?:that|the|this)\\s+(?:trade|setup|idea|order|position)\\b").containsMatchIn(lower)

        val fullSpec = side != null && lots != null && sl != null && tp != null && explicitVerb
        if (!fullSpec && !refersToIdea) {
            // Looks like a half-formed order command - help, don't guess.
            if (side != null && (lower.contains("mt5") || lower.contains("metatrader")) && explicitVerb) {
                return "To place a trade I need all of it: side, symbol, lots, SL and TP. For example: \"place a buy on gold 0.01 lots SL 4510 TP 4560 on MT5\"."
            }
            return null
        }

        // ---- rails ----
        if (!enabled(context)) {
            return "MT5 trade execution is switched off. Say \"enable MT5 trading\" first (and test on a DEMO account)."
        }
        if (!VinceAccessibilityService.isRunning) return PhoneAgent.SETUP_MESSAGE

        var symbol: String?
        val useSide: String
        val useSl: Double
        val useTp: Double
        val useLots: Double
        val skipConfirm: Boolean

        if (fullSpec) {
            symbol = findSymbol(lower, text)
            if (symbol == null) return "Which symbol? For example XAUUSD or gold."
            useSide = side!!; useLots = lots!!; useSl = sl!!; useTp = tp!!
            skipConfirm = Regex("no need to confirm|don'?t ask|do not ask|without (?:asking|confirmation|confirming)|skip (?:the )?confirmation|execute directly").containsMatchIn(lower)
        } else {
            val idea = lastIdea
            if (idea == null || System.currentTimeMillis() - lastIdeaAt > 30 * 60_000L) {
                return "I don't have a fresh trade idea to take. Ask me to analyze a chart first, or give me the full order (side, symbol, lots, SL, TP)."
            }
            if (idea.bias.uppercase() !in setOf("BUY", "SELL")) return "That idea was a WAIT, not a trade."
            if (TradeIdea.levelsLookWrong(idea)) return "The levels on that idea don't make sense for the direction, so I won't trade it."
            symbol = idea.symbol.uppercase().filter { it.isLetterOrDigit() }.ifBlank { null }
                ?: return "The idea has no readable symbol."
            symbol = ALIASES[symbol.lowercase()] ?: symbol
            useSide = if (idea.bias.uppercase() == "BUY") "Buy" else "Sell"
            useSl = num(idea.sl.replace(Regex("[^0-9.,]"), "")) ?: return "I can't read the stop loss on that idea."
            useTp = num(idea.tp.replace(Regex("[^0-9.,]"), "")) ?: return "I can't read the take profit on that idea."
            useLots = lots ?: return "How many lots? Say it like \"take that trade 0.01 lots\" - I never choose the size for you."
            skipConfirm = false
        }

        if (useLots < 0.01) return "Lot size is too small to be valid."
        if (useLots > maxLot(context)) return "That's ${useLots} lots, above your cap of ${maxLot(context)}. Raise the cap yourself with \"set mt5 max lot to ...\" if you really mean it."
        if (useSide == "Buy" && !(useSl < useTp)) return "For a buy, the stop loss must be below the take profit. Check your numbers."
        if (useSide == "Sell" && !(useSl > useTp)) return "For a sell, the stop loss must be above the take profit. Check your numbers."

        val finalSymbol: String = symbol ?: return "I couldn't work out the symbol."
        return PhoneAgent.armMt5(context, PhoneAgent.Mt5Spec(useSide, finalSymbol, useLots, useSl, useTp), skipConfirm)
    }

    private fun findSymbol(lower: String, original: String): String? {
        ALIASES.entries.firstOrNull { Regex("\\b${it.key}\\b").containsMatchIn(lower) }?.let { return it.value }
        return Regex("\\b[A-Z]{3,6}[0-9]{0,3}\\b").findAll(original)
            .map { it.value }
            .firstOrNull { it !in setOf("SL", "TP", "MT", "BUY", "SELL", "LOT", "LOTS", "AT", "ON", "THE") && it.length >= 5 }
    }
}
