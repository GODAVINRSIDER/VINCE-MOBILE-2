package com.godavin.vince

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Trade-idea card for chart analysis. The vision model is asked to end a
 * chart reply with a small [[IDEA]] block using ONLY prices it can read off
 * the chart; the chat bubble hides that raw block and draws a card instead.
 * Risk:reward is calculated here in code from the three levels, never
 * taken from the model. If the levels don't make sense for the stated
 * direction (e.g. stop on the wrong side), the card says so instead of
 * showing a fake ratio.
 */
data class TradeIdeaData(
    val bias: String,
    val symbol: String,
    val timeframe: String,
    val entry: String,
    val sl: String,
    val tp: String,
    val note: String
)

object TradeIdea {
    const val PROMPT_SUFFIX = "\n\nExtra instruction: if, and only if, this image is a trading chart where the " +
        "price levels are clearly readable and you can see a clean price-action or smart-money setup, end your " +
        "reply with exactly this block and nothing after it. Use only prices actually visible on the chart; never " +
        "invent or estimate numbers. If the levels aren't readable or there's no clean setup, either use " +
        "bias=WAIT with a short note or leave the block out entirely. Do not include the block for anything " +
        "that isn't a chart.\n[[IDEA]]\nbias=BUY or SELL or WAIT\nsymbol=...\ntimeframe=...\nentry=...\nsl=...\ntp=...\nnote=one short sentence on why\n[[/IDEA]]"

    private val BLOCK = Regex("\\[\\[IDEA\\]\\](.*?)\\[\\[/IDEA\\]\\]", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Returns the reply text with the raw block removed, plus the parsed idea if one was present. */
    fun split(text: String): Pair<String, TradeIdeaData?> {
        val m = BLOCK.find(text) ?: return text to null
        val fields = mutableMapOf<String, String>()
        for (line in m.groupValues[1].lines()) {
            val idx = line.indexOf('=')
            if (idx <= 0) continue
            fields[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        val bias = fields["bias"].orEmpty().uppercase().ifBlank { "WAIT" }
        val idea = TradeIdeaData(
            bias = if (bias.startsWith("BUY")) "BUY" else if (bias.startsWith("SELL")) "SELL" else "WAIT",
            symbol = fields["symbol"].orEmpty(),
            timeframe = fields["timeframe"].orEmpty(),
            entry = fields["entry"].orEmpty(),
            sl = fields["sl"].orEmpty(),
            tp = fields["tp"].orEmpty(),
            note = fields["note"].orEmpty()
        )
        val cleaned = (text.substring(0, m.range.first) + text.substring(m.range.last + 1)).trim()
        return cleaned to idea
    }

    fun stripForSpeech(text: String): String = split(text).first

    private val NUMBER = Regex("\\d[\\d,]*\\.?\\d*")
    private fun firstNumber(s: String): Double? = NUMBER.find(s)?.value?.replace(",", "")?.toDoubleOrNull()

    /** Risk:reward as reward/risk, or null if the three levels don't form a valid setup for [bias]. */
    fun riskReward(idea: TradeIdeaData): Double? {
        val e = firstNumber(idea.entry) ?: return null
        val sl = firstNumber(idea.sl) ?: return null
        val tp = firstNumber(idea.tp) ?: return null
        val risk: Double
        val reward: Double
        when (idea.bias) {
            "BUY" -> { risk = e - sl; reward = tp - e }
            "SELL" -> { risk = sl - e; reward = e - tp }
            else -> return null
        }
        if (risk <= 0.0 || reward <= 0.0) return null
        return reward / risk
    }

    fun levelsLookWrong(idea: TradeIdeaData): Boolean {
        if (idea.bias == "WAIT") return false
        val hasAll = firstNumber(idea.entry) != null && firstNumber(idea.sl) != null && firstNumber(idea.tp) != null
        return hasAll && riskReward(idea) == null
    }
}

@Composable
fun TradeIdeaCard(idea: TradeIdeaData) {
    val accent = when (idea.bias) {
        "BUY" -> Color(0xFF22C55E)
        "SELL" -> Color(0xFFEF4444)
        else -> Color(0xFFF59E0B)
    }
    val rr = TradeIdea.riskReward(idea)
    val wrong = TradeIdea.levelsLookWrong(idea)

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(accent.copy(alpha = 0.08f))
            .border(1.dp, accent.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("TRADE IDEA", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = accent)
            val head = listOf(idea.symbol, idea.timeframe).filter { it.isNotBlank() }.joinToString("  ")
            if (head.isNotBlank()) {
                Text(head, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            if (idea.bias == "WAIT") "WAIT - no clean setup yet" else idea.bias,
            fontSize = 20.sp, fontWeight = FontWeight.Bold, color = accent
        )

        if (idea.bias != "WAIT") {
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LevelCell("ENTRY", idea.entry, Modifier.weight(1f))
                LevelCell("STOP", idea.sl, Modifier.weight(1f))
                LevelCell("TARGET", idea.tp, Modifier.weight(1f))
                LevelCell("R:R", if (rr != null) "1 : %.1f".format(rr) else "n/a", Modifier.weight(1f))
            }
        }
        if (wrong) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "These levels don't line up with the direction (stop or target on the wrong side) - recheck them on the chart before using any of this.",
                fontSize = 11.sp, color = Color(0xFFF59E0B)
            )
        }
        if (idea.note.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(idea.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Read from the chart image, not advice. Verify levels yourself.",
            fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
        )
    }
}

@Composable
private fun LevelCell(label: String, value: String, modifier: Modifier) {
    Column(modifier = modifier) {
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
        Text(
            value.ifBlank { "-" },
            fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}
