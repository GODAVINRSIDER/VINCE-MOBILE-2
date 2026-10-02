package com.godavin.vince

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface

/**
 * v2.3 - candlestick and SMC pattern drawings made by code, not by an image
 * model, so a "morning star" or "bullish FVG" is always drawn correctly.
 * Prices are on an invented 0..100 scale: these are teaching diagrams.
 */
object CandleChart {

    class Drawing(val path: String, val caption: String)

    private class K(val o: Float, val h: Float, val l: Float, val c: Float)
    private class Zone(val from: Int, val to: Int, val low: Float, val high: Float, val color: Int, val label: String)
    private class HLine(val price: Float, val from: Int, val color: Int, val label: String)
    private class Tag(val idx: Int, val text: String, val above: Boolean)
    private class Pattern(
        val title: String,
        val bias: String,
        val note: String,
        val candles: List<K>,
        val zones: List<Zone> = emptyList(),
        val lines: List<HLine> = emptyList(),
        val tags: List<Tag> = emptyList()
    )

    private val GREEN = Color.parseColor("#26D9A0")
    private val RED = Color.parseColor("#FF5A6E")
    private val CYAN = Color.parseColor("#22D3EE")
    private val AMBER = Color.parseColor("#FFB020")
    private val PURPLE = Color.parseColor("#A78BFA")

    // ---- small builders -------------------------------------------------
    private fun k(o: Float, h: Float, l: Float, c: Float) = K(o, h, l, c)
    private fun bear(open: Float, close: Float, up: Float = 1.5f, dn: Float = 1.5f) = K(open, open + up, close - dn, close)
    private fun bull(open: Float, close: Float, dn: Float = 1.5f, up: Float = 1.5f) = K(open, close + up, open - dn, close)

    /** [n] falling candles ending at about [end]. */
    private fun down(end: Float, n: Int, step: Float = 5f): List<K> {
        val out = ArrayList<K>()
        var price = end + step * n
        repeat(n) {
            out.add(bear(price, price - step, 1.4f, 1.4f))
            price -= step
        }
        return out
    }

    /** [n] rising candles ending at about [end]. */
    private fun up(end: Float, n: Int, step: Float = 5f): List<K> {
        val out = ArrayList<K>()
        var price = end - step * n
        repeat(n) {
            out.add(bull(price, price + step, 1.4f, 1.4f))
            price += step
        }
        return out
    }

    private fun build(key: String, bearish: Boolean): Pattern? = when (key) {
        "morning star" -> {
            val c = down(54f, 3) + listOf(
                bear(54f, 40f, 1.2f, 1.5f), k(37f, 39.5f, 34.5f, 38.5f), bull(40f, 54f, 1.5f, 1.5f)
            )
            Pattern("Morning Star", "Bullish reversal",
                "Strong sellers, then a small indecision candle, then a strong buyer candle closing deep into the first one. Sellers are exhausted.",
                c, tags = listOf(Tag(3, "1 sellers", true), Tag(4, "2 pause", false), Tag(5, "3 buyers", true)))
        }
        "evening star" -> {
            val c = up(66f, 3) + listOf(
                bull(66f, 80f, 1.5f, 1.2f), k(83f, 85.5f, 80.5f, 82f), bear(80f, 66f, 1.5f, 1.5f)
            )
            Pattern("Evening Star", "Bearish reversal",
                "Strong buyers, then a small indecision candle, then a strong seller candle closing deep into the first one. Buyers are exhausted.",
                c, tags = listOf(Tag(3, "1 buyers", false), Tag(4, "2 pause", true), Tag(5, "3 sellers", false)))
        }
        "engulfing" -> if (!bearish) {
            val c = down(46f, 3) + listOf(bear(50f, 46f, 1f, 1f), bull(44f, 53f, 1.2f, 1.2f))
            Pattern("Bullish Engulfing", "Bullish reversal",
                "A big green candle completely swallows the previous red one. Buyers took over the whole prior move.",
                c, tags = listOf(Tag(3, "small red", true), Tag(4, "engulfs", true)))
        } else {
            val c = up(64f, 3) + listOf(bull(60f, 64f, 1f, 1f), bear(66f, 57f, 1.2f, 1.2f))
            Pattern("Bearish Engulfing", "Bearish reversal",
                "A big red candle completely swallows the previous green one. Sellers took over the whole prior move.",
                c, tags = listOf(Tag(3, "small green", false), Tag(4, "engulfs", false)))
        }
        "hammer" -> {
            val c = down(46f, 4) + listOf(k(45f, 46.8f, 30f, 46f))
            Pattern("Hammer", "Bullish reversal",
                "After a drop, sellers pushed price far down but buyers pushed it all the way back. Long lower wick, small body at the top.",
                c, tags = listOf(Tag(4, "long lower wick", false)))
        }
        "hanging man" -> {
            val c = up(66f, 4) + listOf(k(65f, 66.8f, 50f, 66f))
            Pattern("Hanging Man", "Bearish warning",
                "Looks like a hammer but appears after a rise. Sellers showed up; it needs a red candle after it to confirm.",
                c, tags = listOf(Tag(4, "long lower wick", false)))
        }
        "shooting star" -> {
            val c = up(64f, 4) + listOf(k(64f, 80f, 63f, 65f))
            Pattern("Shooting Star", "Bearish reversal",
                "After a rise, buyers pushed price high but sellers slammed it back down. Long upper wick, small body at the bottom.",
                c, tags = listOf(Tag(4, "long upper wick", true)))
        }
        "inverted hammer" -> {
            val c = down(46f, 4) + listOf(k(45f, 60f, 44f, 47f))
            Pattern("Inverted Hammer", "Bullish reversal (needs confirmation)",
                "After a drop, buyers tried to push up. Long upper wick, small body at the bottom. A green candle next confirms it.",
                c, tags = listOf(Tag(4, "long upper wick", true)))
        }
        "doji" -> {
            val c = up(62f, 3) + listOf(k(62f, 71f, 53f, 62.4f))
            Pattern("Doji", "Indecision",
                "Open and close are almost equal. Buyers and sellers fought to a draw. At the end of a trend it can warn of a turn.",
                c, tags = listOf(Tag(3, "open = close", true)))
        }
        "dragonfly doji" -> {
            val c = down(46f, 3) + listOf(k(46f, 46.3f, 31f, 46.1f))
            Pattern("Dragonfly Doji", "Bullish reversal",
                "Price fell hard, then recovered to the open. A long lower wick and no real body show sellers were rejected.",
                c, tags = listOf(Tag(3, "rejected lows", false)))
        }
        "gravestone doji" -> {
            val c = up(64f, 3) + listOf(k(64f, 79f, 63.7f, 63.9f))
            Pattern("Gravestone Doji", "Bearish reversal",
                "Price rallied hard, then fell back to the open. A long upper wick and no real body show buyers were rejected.",
                c, tags = listOf(Tag(3, "rejected highs", true)))
        }
        "three white soldiers" -> {
            val c = down(44f, 3) + listOf(bull(42f, 52f, 1f, 1f), bull(50f, 60f, 1f, 1f), bull(58f, 68f, 1f, 1f))
            Pattern("Three White Soldiers", "Bullish continuation / reversal",
                "Three strong green candles in a row, each opening inside the previous body and closing near its high. Steady buying pressure.",
                c)
        }
        "three black crows" -> {
            val c = up(68f, 3) + listOf(bear(70f, 60f, 1f, 1f), bear(62f, 52f, 1f, 1f), bear(54f, 44f, 1f, 1f))
            Pattern("Three Black Crows", "Bearish continuation / reversal",
                "Three strong red candles in a row, each opening inside the previous body and closing near its low. Steady selling pressure.",
                c)
        }
        "harami" -> if (!bearish) {
            val c = down(46f, 3) + listOf(bear(58f, 42f, 1f, 1f), bull(47f, 52f, 1.5f, 1.5f))
            Pattern("Bullish Harami", "Bullish reversal (early)",
                "A small green candle sits inside the body of the previous big red one. Selling momentum is fading.",
                c, tags = listOf(Tag(3, "big red", true), Tag(4, "inside", true)))
        } else {
            val c = up(64f, 3) + listOf(bull(50f, 66f, 1f, 1f), bear(61f, 56f, 1.5f, 1.5f))
            Pattern("Bearish Harami", "Bearish reversal (early)",
                "A small red candle sits inside the body of the previous big green one. Buying momentum is fading.",
                c, tags = listOf(Tag(3, "big green", false), Tag(4, "inside", false)))
        }
        "piercing line" -> {
            val c = down(46f, 3) + listOf(bear(58f, 44f, 1f, 1f), bull(40f, 52f, 1.2f, 1.2f))
            Pattern("Piercing Line", "Bullish reversal",
                "After a red candle, price gaps down but closes above the middle of that red body. Buyers fought back hard.",
                c, tags = listOf(Tag(4, "closes above midpoint", true)))
        }
        "dark cloud cover" -> {
            val c = up(66f, 3) + listOf(bull(52f, 66f, 1f, 1f), bear(70f, 58f, 1.2f, 1.2f))
            Pattern("Dark Cloud Cover", "Bearish reversal",
                "After a green candle, price gaps up but closes below the middle of that green body. Sellers fought back hard.",
                c, tags = listOf(Tag(4, "closes below midpoint", false)))
        }
        "tweezer top" -> {
            val c = up(66f, 3) + listOf(bull(60f, 67f, 1f, 3f), bear(67f, 61f, 3f, 1f))
            Pattern("Tweezer Top", "Bearish reversal",
                "Two candles with matching highs. Price failed twice at the same level, so buyers are being rejected.",
                c, lines = listOf(HLine(70f, 3, RED, "matching highs")), tags = listOf(Tag(4, "same high", true)))
        }
        "tweezer bottom" -> {
            val c = down(46f, 3) + listOf(bear(50f, 43f, 3f, 1f), bull(43f, 49f, 1f, 3f))
            Pattern("Tweezer Bottom", "Bullish reversal",
                "Two candles with matching lows. Price failed twice at the same level, so sellers are being rejected.",
                c, lines = listOf(HLine(40f, 3, GREEN, "matching lows")), tags = listOf(Tag(4, "same low", false)))
        }
        "marubozu" -> if (!bearish) {
            val c = down(44f, 2) + listOf(k(42f, 62f, 42f, 62f))
            Pattern("Bullish Marubozu", "Strong buying",
                "A full green body with no wicks. Buyers controlled the whole session from open to close.",
                c, tags = listOf(Tag(2, "no wicks", true)))
        } else {
            val c = up(64f, 2) + listOf(k(66f, 66f, 46f, 46f))
            Pattern("Bearish Marubozu", "Strong selling",
                "A full red body with no wicks. Sellers controlled the whole session from open to close.",
                c, tags = listOf(Tag(2, "no wicks", false)))
        }
        "spinning top" -> {
            val c = up(62f, 3) + listOf(k(60f, 72f, 50f, 63f))
            Pattern("Spinning Top", "Indecision",
                "Small body with wicks on both sides. Neither side won. Often a pause before the next move.",
                c, tags = listOf(Tag(3, "small body", true)))
        }
        "fvg" -> if (!bearish) {
            val g = listOf(k(40f, 48f, 39f, 47f), bull(47f, 62f, 0.5f, 1f), k(61f, 66f, 54f, 65f))
            val seq = g + listOf(bull(65f, 70f, 1f, 1f), bear(69f, 62f, 1f, 1f), bear(62f, 56f, 1f, 1f), bull(54f, 60f, 1.5f, 1f), bull(60f, 72f, 1f, 1f))
            Pattern("Bullish Fair Value Gap (FVG)", "Bullish imbalance",
                "Candle 1's high and candle 3's low leave a gap that candle 2 jumped across. Price often returns to fill part of it, then continues up.",
                seq, zones = listOf(Zone(0, 7, 48f, 54f, CYAN, "FVG")),
                tags = listOf(Tag(0, "1", false), Tag(1, "2 impulse", false), Tag(2, "3", false), Tag(7, "return to gap", false)))
        } else {
            val g = listOf(k(70f, 71f, 62f, 63f), bear(63f, 48f, 0.5f, 1f), k(49f, 56f, 44f, 45f))
            val seq = g + listOf(bear(45f, 40f, 1f, 1f), bull(41f, 48f, 1f, 1f), bull(48f, 54f, 1f, 1f), bear(56f, 50f, 1.5f, 1f), bear(50f, 38f, 1f, 1f))
            Pattern("Bearish Fair Value Gap (FVG)", "Bearish imbalance",
                "Candle 1's low and candle 3's high leave a gap that candle 2 dropped across. Price often returns to fill part of it, then continues down.",
                seq, zones = listOf(Zone(0, 7, 56f, 62f, CYAN, "FVG")),
                tags = listOf(Tag(0, "1", true), Tag(1, "2 impulse", true), Tag(2, "3", true), Tag(6, "return to gap", true)))
        }
        "order block" -> if (!bearish) {
            val seq = listOf(bull(50f, 54f, 1f, 1f), bear(54f, 46f, 1f, 1f), bear(46f, 41f, 1f, 1f), // OB = candle idx 2
                bull(41f, 58f, 0.5f, 1f), bull(58f, 68f, 1f, 1f), bear(68f, 62f, 1f, 1f), bear(62f, 54f, 1f, 1f), bear(54f, 44f, 1f, 1f),
                bull(43f, 56f, 1.5f, 1.5f), bull(56f, 70f, 1f, 1f))
            Pattern("Bullish Order Block", "Bullish zone",
                "The last red candle before a strong move up that breaks structure. When price returns to that zone, buyers often step in again.",
                seq, zones = listOf(Zone(2, 9, 40f, 47f, GREEN, "Order block")),
                lines = listOf(HLine(54f, 3, AMBER, "structure high broken")),
                tags = listOf(Tag(2, "last red", false), Tag(3, "impulse", false), Tag(8, "reacts here", false)))
        } else {
            val seq = listOf(bear(54f, 50f, 1f, 1f), bull(50f, 58f, 1f, 1f), bull(58f, 63f, 1f, 1f), // OB = candle idx 2
                bear(63f, 46f, 0.5f, 1f), bear(46f, 36f, 1f, 1f), bull(36f, 42f, 1f, 1f), bull(42f, 50f, 1f, 1f), bull(50f, 60f, 1f, 1f),
                bear(61f, 48f, 1.5f, 1.5f), bear(48f, 34f, 1f, 1f))
            Pattern("Bearish Order Block", "Bearish zone",
                "The last green candle before a strong move down that breaks structure. When price returns to that zone, sellers often step in again.",
                seq, zones = listOf(Zone(2, 9, 57f, 64f, RED, "Order block")),
                lines = listOf(HLine(50f, 3, AMBER, "structure low broken")),
                tags = listOf(Tag(2, "last green", true), Tag(3, "impulse", true), Tag(8, "reacts here", true)))
        }
        "bos" -> if (!bearish) {
            val seq = listOf(bull(40f, 52f, 1f, 1f), bear(52f, 46f, 1f, 1f), bear(46f, 44f, 1f, 1f), bull(44f, 56f, 1f, 1f), bull(56f, 64f, 1f, 1f),
                bear(64f, 56f, 1f, 1f), bull(56f, 60f, 1f, 1f), bull(60f, 72f, 1f, 1f))
            Pattern("Break of Structure (BOS) - bullish", "Trend continuation up",
                "Price closes above the previous swing high. The uptrend is confirmed and higher highs are still being made.",
                seq, lines = listOf(HLine(66f, 4, AMBER, "swing high")), tags = listOf(Tag(7, "BOS", true)))
        } else {
            val seq = listOf(bear(72f, 60f, 1f, 1f), bull(60f, 66f, 1f, 1f), bull(66f, 68f, 1f, 1f), bear(68f, 56f, 1f, 1f), bear(56f, 48f, 1f, 1f),
                bull(48f, 56f, 1f, 1f), bear(56f, 52f, 1f, 1f), bear(52f, 40f, 1f, 1f))
            Pattern("Break of Structure (BOS) - bearish", "Trend continuation down",
                "Price closes below the previous swing low. The downtrend is confirmed and lower lows are still being made.",
                seq, lines = listOf(HLine(46f, 4, AMBER, "swing low")), tags = listOf(Tag(7, "BOS", false)))
        }
        "choch" -> if (!bearish) {
            val seq = listOf(bear(80f, 70f, 1f, 1f), bull(70f, 74f, 1f, 1f), bear(74f, 62f, 1f, 1f), bull(62f, 66f, 1f, 1f), bear(66f, 52f, 1f, 1f),
                bull(52f, 60f, 1f, 1f), bull(60f, 70f, 1f, 1f), bull(70f, 80f, 1f, 1f))
            Pattern("Change of Character (CHoCH) - bullish", "Possible reversal up",
                "After lower highs and lower lows, price breaks above the last lower high. The first sign the downtrend is changing.",
                seq, lines = listOf(HLine(76f, 4, AMBER, "last lower high")), tags = listOf(Tag(7, "CHoCH", true), Tag(4, "low", false)))
        } else {
            val seq = listOf(bull(40f, 50f, 1f, 1f), bear(50f, 46f, 1f, 1f), bull(46f, 58f, 1f, 1f), bear(58f, 54f, 1f, 1f), bull(54f, 68f, 1f, 1f),
                bear(68f, 60f, 1f, 1f), bear(60f, 50f, 1f, 1f), bear(50f, 40f, 1f, 1f))
            Pattern("Change of Character (CHoCH) - bearish", "Possible reversal down",
                "After higher highs and higher lows, price breaks below the last higher low. The first sign the uptrend is changing.",
                seq, lines = listOf(HLine(52f, 4, AMBER, "last higher low")), tags = listOf(Tag(7, "CHoCH", false), Tag(4, "high", true)))
        }
        "liquidity sweep" -> if (!bearish) {
            val seq = listOf(bull(58f, 62f, 1f, 1f), bear(62f, 58f, 1f, 1.5f), bull(58f, 61.5f, 1f, 1f), bear(61.5f, 57f, 1f, 1.5f), bull(57f, 59f, 1f, 1f),
                k(58f, 59f, 47f, 58.5f), bull(58.5f, 66f, 1f, 1f), bull(66f, 74f, 1f, 1f))
            Pattern("Liquidity Sweep - sell side", "Bullish after the sweep",
                "Price dips under equal lows where stop losses sit, grabs that liquidity, then closes back above and rallies.",
                seq, lines = listOf(HLine(56f, 0, PURPLE, "equal lows / liquidity")), tags = listOf(Tag(5, "sweep", false)))
        } else {
            val seq = listOf(bear(60f, 56f, 1f, 1f), bull(56f, 60f, 1.5f, 1f), bear(60f, 56.5f, 1f, 1f), bull(56.5f, 61f, 1.5f, 1f), bear(61f, 59f, 1f, 1f),
                k(60f, 71f, 59f, 60.5f), bear(60.5f, 52f, 1f, 1f), bear(52f, 44f, 1f, 1f))
            Pattern("Liquidity Sweep - buy side", "Bearish after the sweep",
                "Price pokes above equal highs where stop losses sit, grabs that liquidity, then closes back below and drops.",
                seq, lines = listOf(HLine(62f, 0, PURPLE, "equal highs / liquidity")), tags = listOf(Tag(5, "sweep", true)))
        }
        else -> null
    }

    // phrase -> key, longest/most specific first
    private val ALIASES: List<Pair<String, String>> = listOf(
        "three white soldiers" to "three white soldiers", "white soldiers" to "three white soldiers",
        "three black crows" to "three black crows", "black crows" to "three black crows",
        "dragonfly doji" to "dragonfly doji", "gravestone doji" to "gravestone doji",
        "morning star" to "morning star", "evening star" to "evening star",
        "shooting star" to "shooting star", "inverted hammer" to "inverted hammer",
        "hanging man" to "hanging man", "piercing line" to "piercing line",
        "dark cloud" to "dark cloud cover", "tweezer top" to "tweezer top", "tweezer bottom" to "tweezer bottom",
        "spinning top" to "spinning top", "marubozu" to "marubozu", "harami" to "harami",
        "engulfing" to "engulfing", "hammer" to "hammer", "doji" to "doji",
        "fair value gap" to "fvg", "fvg" to "fvg", "imbalance" to "fvg",
        "order block" to "order block", "orderblock" to "order block",
        "break of structure" to "bos", "bos" to "bos",
        "change of character" to "choch", "choch" to "choch", "change in character" to "choch",
        "liquidity sweep" to "liquidity sweep", "liquidity grab" to "liquidity sweep", "stop hunt" to "liquidity sweep",
        "sweep" to "liquidity sweep"
    )

    private val DRAW_WORDS = Regex(
        "\\b(draw|drawing|show|illustrate|picture|image|diagram|chart|visual|generate|create|make|sketch|" +
            "look like|looks like|example of)\\b", RegexOption.IGNORE_CASE
    )

    /** Detects "draw/show a morning star", "image of bullish FVG", etc. */
    fun tryDraw(context: Context, text: String): Drawing? {
        val lower = text.lowercase().replace('\u2019', '\'')
        if (!DRAW_WORDS.containsMatchIn(lower)) return null
        // Ignore analysis of an attached chart or live-market questions
        if (Regex("\\b(this chart|my chart|current|right now|today|live)\\b").containsMatchIn(lower)) return null
        val hit = ALIASES.firstOrNull { wordMatch(lower, it.first) } ?: return null
        val bearish = Regex("\\b(bearish|sell|sell side|short|down)\\b").containsMatchIn(lower) &&
            !Regex("\\b(bullish|buy|long)\\b").containsMatchIn(lower)
        val bullishWord = Regex("\\b(bullish|buy side|buy|long)\\b").containsMatchIn(lower)
        // For the liquidity sweep, "buy side" means the highs are swept (bearish outcome)
        val sweepBuySide = hit.second == "liquidity sweep" && Regex("buy[- ]?side").containsMatchIn(lower)
        val useBearish = if (hit.second == "liquidity sweep") (sweepBuySide || (bearish && !bullishWord)) else bearish
        val pattern = build(hit.second, useBearish) ?: return null
        return try {
            val bmp = render(pattern)
            val path = ImageStore.save(context, bmp)
            Drawing(path, "${pattern.title} - ${pattern.bias}.\n${pattern.note}\n(Drawn by code on an illustrative price scale, not an AI image.)")
        } catch (e: Exception) {
            null
        }
    }

    private fun wordMatch(text: String, phrase: String): Boolean =
        Regex("\\b" + Regex.escape(phrase) + "s?\\b").containsMatchIn(text)

    // ---- rendering ------------------------------------------------------

    private fun render(p: Pattern): Bitmap {
        val w = 1080
        val h = 1080
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.parseColor("#0B0F14"))

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // plot area
        val left = 70f
        val right = w - 60f
        val top = 250f
        val bottom = h - 130f

        // faint grid
        paint.color = Color.parseColor("#16202B")
        paint.strokeWidth = 2f
        for (i in 0..6) {
            val y = top + (bottom - top) * i / 6f
            c.drawLine(left, y, right, y, paint)
        }

        var lo = Float.MAX_VALUE
        var hi = -Float.MAX_VALUE
        p.candles.forEach { lo = minOf(lo, it.l); hi = maxOf(hi, it.h) }
        p.zones.forEach { lo = minOf(lo, it.low); hi = maxOf(hi, it.high) }
        p.lines.forEach { lo = minOf(lo, it.price); hi = maxOf(hi, it.price) }
        val pad = (hi - lo) * 0.12f
        lo -= pad
        hi += pad
        fun y(price: Float) = bottom - (price - lo) / (hi - lo) * (bottom - top)

        val n = p.candles.size
        val slot = (right - left) / n
        val bodyW = slot * 0.5f
        fun cx(i: Int) = left + slot * i + slot / 2f

        // zones
        for (z in p.zones) {
            paint.style = Paint.Style.FILL
            paint.color = (z.color and 0x00FFFFFF) or (0x40 shl 24)
            c.drawRect(cx(z.from) - slot / 2f, y(z.high), cx(z.to) + slot / 2f, y(z.low), paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = z.color
            c.drawRect(cx(z.from) - slot / 2f, y(z.high), cx(z.to) + slot / 2f, y(z.low), paint)
            paint.style = Paint.Style.FILL
            paint.textSize = 30f
            paint.typeface = Typeface.DEFAULT_BOLD
            c.drawText(z.label, cx(z.from) - slot / 2f + 10f, y(z.high) - 10f, paint)
        }

        // horizontal lines
        for (l in p.lines) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f
            paint.color = l.color
            paint.pathEffect = DashPathEffect(floatArrayOf(18f, 12f), 0f)
            c.drawLine(cx(l.from) - slot / 2f, y(l.price), right, y(l.price), paint)
            paint.pathEffect = null
            paint.style = Paint.Style.FILL
            paint.textSize = 28f
            paint.typeface = Typeface.DEFAULT
            val tw = paint.measureText(l.label)
            c.drawText(l.label, right - tw - 4f, y(l.price) - 10f, paint)
        }

        // candles
        for ((i, k) in p.candles.withIndex()) {
            val bullish = k.c >= k.o
            val color = if (bullish) GREEN else RED
            paint.color = color
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f
            c.drawLine(cx(i), y(k.h), cx(i), y(k.l), paint)
            paint.style = Paint.Style.FILL
            var yt = y(maxOf(k.o, k.c))
            var yb = y(minOf(k.o, k.c))
            if (yb - yt < 5f) { yt -= 2.5f; yb += 2.5f }
            c.drawRect(cx(i) - bodyW / 2f, yt, cx(i) + bodyW / 2f, yb, paint)
        }

        // tags
        paint.style = Paint.Style.FILL
        paint.color = Color.parseColor("#C9D6E2")
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT_BOLD
        paint.textAlign = Paint.Align.CENTER
        for (t in p.tags) {
            val k = p.candles[t.idx]
            val ty = if (t.above) y(k.h) - 18f else y(k.l) + 38f
            c.drawText(t.text, cx(t.idx), ty, paint)
        }
        paint.textAlign = Paint.Align.LEFT

        // header
        paint.color = CYAN
        paint.textSize = 54f
        paint.typeface = Typeface.DEFAULT_BOLD
        c.drawText(p.title, 60f, 100f, paint)

        val bullishBias = p.bias.lowercase().contains("bullish")
        val bearishBias = p.bias.lowercase().contains("bearish")
        paint.color = if (bullishBias) GREEN else if (bearishBias) RED else AMBER
        paint.textSize = 36f
        c.drawText(p.bias, 60f, 150f, paint)

        paint.color = Color.parseColor("#9FB0C0")
        paint.textSize = 28f
        paint.typeface = Typeface.DEFAULT
        wrap(p.note, paint, w - 120f).take(3).forEachIndexed { i, line ->
            c.drawText(line, 60f, 195f + i * 36f, paint)
        }

        // footer
        paint.color = Color.parseColor("#5B6B7A")
        paint.textSize = 24f
        c.drawText("VINCE  |  illustrative price scale, drawn by code", 60f, h - 50f, paint)

        return bmp
    }

    private fun wrap(text: String, paint: Paint, maxWidth: Float): List<String> {
        val lines = ArrayList<String>()
        var cur = StringBuilder()
        for (word in text.split(" ")) {
            val trial = if (cur.isEmpty()) word else cur.toString() + " " + word
            if (paint.measureText(trial) > maxWidth && cur.isNotEmpty()) {
                lines.add(cur.toString())
                cur = StringBuilder(word)
            } else {
                cur = StringBuilder(trial)
            }
        }
        if (cur.isNotEmpty()) lines.add(cur.toString())
        return lines
    }
}
