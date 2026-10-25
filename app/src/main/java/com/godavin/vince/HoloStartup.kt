package com.godavin.vince

import android.content.Context
import android.media.MediaPlayer
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.Canvas
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** Hands taps from the startup screen to the normal navigation (RootScreen watches these). */
object StartupBus {
    val openThread = mutableStateOf<String?>(null)
    val openNew = mutableStateOf(false)
    val replay = mutableStateOf(false)
}

/**
 * v2.12 - cinematic startup. Which persona opens this launch (Vince, Clara, Davina, round again - remembered), and whether the
 * FULL spoken intro plays: only on the first launch after the timer (default 8 h, you can type 1 min, 4min, 1H, 5H ...) has run out.
 * Launches inside that window play the same sound-synced entrance, with no speech. Everything is stored on this phone only.
 */
object HoloStartup {
    private const val PREFS = "vince_holo"
    const val DEFAULT_SECONDS = 8L * 3600L
    private val ORDER = listOf(Persona.VINCE, Persona.CLARA, Persona.DAVINA)
    private val UNITS = mapOf(
        "s" to 1L, "sec" to 1L, "secs" to 1L, "second" to 1L, "seconds" to 1L,
        "m" to 60L, "min" to 60L, "mins" to 60L, "minute" to 60L, "minutes" to 60L,
        "h" to 3600L, "hr" to 3600L, "hrs" to 3600L, "hour" to 3600L, "hours" to 3600L,
        "d" to 86400L, "day" to 86400L, "days" to 86400L
    )

    data class Plan(val persona: Persona, val full: Boolean, val addr: String)

    private var cached: Plan? = null
    private var consumed = false

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun lookOn(c: Context): Boolean = prefs(c).getBoolean("look", true)
    fun setLook(c: Context, on: Boolean) { prefs(c).edit().putBoolean("look", on).apply() }

    fun introSeconds(c: Context): Long = prefs(c).getLong("intro_seconds", DEFAULT_SECONDS).coerceIn(10L, 7L * 86400L)
    fun setIntroSeconds(c: Context, s: Long) { prefs(c).edit().putLong("intro_seconds", s).apply() }

    /** Decides this launch (once per app process) and makes that persona the active one. Null when the holographic look is off. */
    @Synchronized
    fun plan(c: Context): Plan? {
        if (!lookOn(c)) return null
        cached?.let { return it }
        val p = prefs(c)
        val idx = p.getInt("launch_index", 0).coerceAtLeast(0)
        val persona = ORDER[idx % ORDER.size]
        val now = System.currentTimeMillis()
        val last = p.getLong("intro_last", 0L)
        val full = last <= 0L || last > now || now - last >= introSeconds(c) * 1000L
        val e = p.edit().putInt("launch_index", idx + 1)
        if (full) e.putLong("intro_last", now)
        e.apply()
        try { PersonaState.setActive(c, persona) } catch (ex: Exception) { }
        val plan = Plan(persona, full, if (Math.random() < .5) "sir" else "Vince")
        cached = plan
        return plan
    }

    /** The startup screen takes the plan once per process (a screen rotation must not replay it). */
    @Synchronized
    fun takeStartup(c: Context): Plan? {
        if (consumed) return null
        consumed = true
        return plan(c)
    }

    /** "1 min", "4min", "1H", "5H", "1.5 hours", "90s", "2d", "1h 30m" -> seconds. Throws IllegalArgumentException with a friendly message. */
    fun parseDuration(text: String): Long {
        val t = text.trim().lowercase().replace(',', '.')
        val parts = Regex("(\\d+(?:\\.\\d+)?)\\s*([a-z]*)").findAll(t).toList()
        if (parts.isEmpty() || t.replace(Regex("[\\d.\\sa-z]"), "").isNotEmpty()) throw IllegalArgumentException("Type a time like 1 min, 4min, 1H or 5H.")
        var total = 0.0
        for (m in parts) {
            val mult = UNITS[m.groupValues[2]] ?: throw IllegalArgumentException("Add a unit after the number - s, min, h or d (for example 5 min or 2H).")
            total += m.groupValues[1].toDouble() * mult
        }
        val secs = Math.round(total)
        if (secs < 10L || secs > 7L * 86400L) throw IllegalArgumentException("Pick a time between 10 seconds and 7 days.")
        return secs
    }

    fun formatDuration(sec: Long): String = when {
        sec % 86400L == 0L -> "${sec / 86400L} d"
        sec % 3600L == 0L -> "${sec / 3600L} h"
        sec % 60L == 0L -> "${sec / 60L} min"
        else -> "$sec s"
    }

    /** The newest chat that has real messages and a real title - the one we offer to pick back up. */
    fun resumeThread(c: Context): ChatThread? = try {
        ConversationStore.getAllThreads(c)
            .filter { it.messages.isNotEmpty() && it.title.isNotBlank() && !it.title.equals("New chat", ignoreCase = true) }
            .maxByOrNull { it.updatedAt }
    } catch (e: Exception) {
        null
    }
}

// ------------------------------------------------------------------------------------------------------------------
private class Piece(val r: Float, val a: Float, val len: Float, val lw: Float, val al: Float, val l: Float, val far: Float, val spin: Float, val delay: Float)

private fun ease(x: Float): Float { val v = x.coerceIn(0f, 1f); return 1f - (1f - v) * (1f - v) * (1f - v) }

private fun mkPieces(p: Persona): List<Piece> {
    var s = 7L
    fun rnd(): Float { s = (s * 16807L) % 2147483647L; return s.toFloat() / 2147483647f }
    val out = ArrayList<Piece>()
    val tau = Holo.TAU
    fun add(r: Float, a: Float, len: Float, lw: Float, al: Float, l: Float) { out.add(Piece(r, a, len, lw, al, l, 1.2f + rnd() * 2.8f, (rnd() - .5f) * 9f, rnd() * .45f)) }
    val n = Holo.arcs(p)
    for (i in 0 until n) add(.96f, i * tau / n, tau / n * .62f, .045f, .95f, 62f)
    for (i in 0 until 28) add(.72f, i * tau / 28, tau / 28 * .62f, .05f, .75f, 55f)
    for (i in 0 until 36) add(.6f, i * tau / 36, tau / 36 * .4f, .1f, .5f, 70f)
    for (r in floatArrayOf(.88f, .8f, .52f, .34f)) for (j in 0 until 4) add(r, j * 1.5708f, 1.2f, .008f, .6f, 60f)
    return out
}

private fun introLines(p: Persona, addr: String, topic: String?): List<String> {
    val name = p.displayName.lowercase().replaceFirstChar { it.uppercase() }
    val ask = if (topic != null) "Last time we were on \u201C$topic\u201D. Want to pick that chat back up?" else "What shall we do first?"
    return listOf(
        "Welcome back, $addr. $name online.",
        "I\u2019m on your phone: voice, camera and screen help, reminders and alerts, with your plan and rules close.",
        "I act on trades only after your clear yes, and I stay in sync with the PC.",
        ask
    )
}

private const val CPS = 17f

/** Mounted on top of the whole app in MainActivity. Black from the very first frame; removes itself when finished. */
@Composable
fun HoloStartupLayer() {
    val context = LocalContext.current
    var active by remember { mutableStateOf(HoloStartup.takeStartup(context)) }
    val replay = StartupBus.replay.value
    LaunchedEffect(replay) {
        if (replay) {
            StartupBus.replay.value = false
            active = HoloStartup.Plan(PersonaState.getActive(context), true, if (Math.random() < .5) "sir" else "Vince")
        }
    }
    val plan = active
    if (plan != null) {
        StartupOverlay(plan) { threadId, newChat ->
            active = null
            if (threadId != null) StartupBus.openThread.value = threadId
            else if (newChat) StartupBus.openNew.value = true
        }
    }
}

@Composable
private fun StartupOverlay(plan: HoloStartup.Plan, onFinish: (String?, Boolean) -> Unit) {
    val context = LocalContext.current
    val persona = plan.persona
    val full = plan.full
    val thread = remember { if (full) HoloStartup.resumeThread(context) else null }
    val lines = remember { if (full) introLines(persona, plan.addr, thread?.title) else emptyList() }
    val pieces = remember(persona) { mkPieces(persona) }
    var t by remember { mutableStateOf(0f) }
    var typeT0 by remember { mutableStateOf(-1f) }
    var closeAt by remember { mutableStateOf(-1f) }
    var result by remember { mutableStateOf<Pair<String?, Boolean>>(Pair(null, false)) }

    val player = remember { try { MediaPlayer.create(context, R.raw.arc_reactor) } catch (e: Exception) { null } }
    DisposableEffect(Unit) {
        try { player?.start() } catch (e: Exception) { }
        onDispose {
            try { player?.stop() } catch (e: Exception) { }
            try { player?.release() } catch (e: Exception) { }
        }
    }

    fun finish(id: String?, newChat: Boolean) {
        if (closeAt >= 0f) return
        result = Pair(id, newChat)
        closeAt = t
        try { player?.stop() } catch (e: Exception) { }
        try { VoiceOutput.stop() } catch (e: Exception) { }
    }
    BackHandler(enabled = closeAt < 0f) { finish(null, false) }

    LaunchedEffect(Unit) {
        val t0 = withFrameNanos { it }
        while (true) withFrameNanos { n -> t = (n - t0) / 1_000_000_000f }
    }
    LaunchedEffect(Unit) {
        if (full) {
            delay(5200)
            if (closeAt < 0f) {
                try { VoiceOutput.speak(lines.joinToString(" "), persona) } catch (e: Exception) { }
                typeT0 = t + .3f
            }
        }
    }
    LaunchedEffect(closeAt >= 0f) {
        if (closeAt >= 0f) {
            delay(480)
            onFinish(result.first, result.second)
        }
    }

    val starts = remember(typeT0) {
        var s = typeT0
        lines.map { val x = s; s += it.length / CPS + .7f; x }
    }
    val doneT = if (typeT0 >= 0f && lines.isNotEmpty()) starts.last() + lines.last().length / CPS + .3f else Float.MAX_VALUE
    val showButtons = full && t > doneT
    val endT = 6.0f
    LaunchedEffect(!full && t > endT, showButtons && t > doneT + 25f) {
        if ((!full && t > endT) || (showButtons && t > doneT + 25f)) finish(null, false)
    }

    val a0 = .3f
    val a1 = a0 + 3.7f
    val fade = if (closeAt >= 0f) (1f - (t - closeAt) / .45f).coerceIn(0f, 1f) else 1f

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = fade }
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { finish(null, false) }
    ) {
        val wPx = constraints.maxWidth.toFloat()
        val hPx = constraints.maxHeight.toFloat()
        val m = if (full) ease((t - 4.2f) / .8f) else 0f
        val r0 = min(wPx * .36f, hPx * .2f)
        val r2 = min(wPx * .2f, hPx * .1f)
        val rr = r0 + (r2 - r0) * m
        val cy = hPx * .5f + (hPx * .24f - hPx * .5f) * m
        val cx = wPx / 2f
        val pr = (t - a0) / 3.7f
        val xf = ease((t - a1) / .5f)

        Canvas(Modifier.fillMaxSize()) {
            val hue = Holo.hue(persona)
            fun col(l: Float, a: Float): Color = Holo.hsl(hue, l, a)
            // faint grid + the scan line
            val gx = size.width / 12f
            var x = 0f
            while (x < size.width) { drawLine(col(40f, .1f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f); x += gx }
            val sw = ease(t / .9f)
            drawLine(col(70f, (1f - sw) * .6f), Offset(0f, sw * size.height), Offset(size.width, sw * size.height), strokeWidth = 3f)
            // the power-on shockwave (the hit in the sound)
            if (t > .28f && t < 1f) {
                val q = (t - .28f) / .72f
                drawCircle(col(80f, (1f - q) * .7f), radius = rr * (.2f + q * 1.6f), center = Offset(cx, cy), style = Stroke(width = 3f))
            }
            // glow that grows while the pieces fly in
            drawCircle(
                brush = androidx.compose.ui.graphics.Brush.radialGradient(0f to col(90f, .7f * ease(pr)), 1f to col(40f, 0f), center = Offset(cx, cy), radius = rr),
                radius = rr, center = Offset(cx, cy)
            )
            // the disassembled ring flying in and locking together
            if (xf < 1f) {
                for (pc in pieces) {
                    val q = ease((pr - pc.delay) / .55f)
                    if (q <= 0f) continue
                    val radius = rr * (pc.r + (1f - q) * pc.far)
                    val a = pc.a + (1f - q) * pc.spin
                    drawArc(
                        color = col(pc.l, pc.al * q * (1f - xf)), startAngle = Math.toDegrees(a.toDouble()).toFloat(),
                        sweepAngle = Math.toDegrees(pc.len.toDouble()).toFloat(), useCenter = false,
                        topLeft = Offset(cx - radius, cy - radius), size = Size(radius * 2, radius * 2),
                        style = Stroke(width = max(1f, rr * pc.lw), cap = StrokeCap.Butt)
                    )
                }
            }
            if (xf > 0f) {
                val mode = if (t < 2.2f) 2 else if (full && typeT0 >= 0f && t < doneT) 3 else 0
                drawReactor(persona, mode, t, cx, cy, rr, false, xf)
                drawPersonaMark(persona, cx, cy, rr, xf)
            }
            if (t > a1 + .1f && t < a1 + .9f) {
                val q = (t - a1 - .1f) / .8f
                drawCircle(col(90f, 1f - q), radius = rr * (.3f + q * 1.4f), center = Offset(cx, cy), style = Stroke(width = 4f))
            }
        }

        // the name, under the ring
        val nameAlpha = ease((t - (a1 + .9f)) / .6f)
        Text(
            persona.displayName.toList().joinToString(" "),
            modifier = Modifier
                .fillMaxWidth()
                .offset { IntOffset(0, (cy + rr * 1.3f).roundToInt()) }
                .graphicsLayer { alpha = nameAlpha },
            textAlign = TextAlign.Center, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = persona.core()
        )

        // the intro, typed as it is spoken (the not-yet-typed part is transparent, so the text never jumps around)
        if (full && typeT0 >= 0f) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .offset { IntOffset(0, (cy + rr * 1.3f + 64f * (rr / 200f).coerceIn(.6f, 1.4f)).roundToInt()) }
                    .padding(horizontal = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                lines.forEachIndexed { i, ln ->
                    val n = ((t - starts[i]) * CPS).toInt().coerceIn(0, ln.length)
                    val last = i == lines.size - 1
                    Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = persona.core().copy(alpha = if (last) 1f else .8f))) { append(ln.take(n)) }
                            withStyle(SpanStyle(color = Color.Transparent)) { append(ln.drop(n)) }
                        },
                        textAlign = TextAlign.Center, fontSize = 15.sp, lineHeight = 21.sp
                    )
                }
            }
        }

        if (showButtons && closeAt < 0f) {
            Row(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 56.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (thread != null) {
                    Button(
                        onClick = { finish(thread.id, false) },
                        colors = ButtonDefaults.buttonColors(containerColor = persona.color().copy(alpha = .25f), contentColor = persona.core())
                    ) { Text("Resume chat") }
                    OutlinedButton(onClick = { finish(null, true) }) { Text("New chat", color = persona.core()) }
                } else {
                    Button(
                        onClick = { finish(null, false) },
                        colors = ButtonDefaults.buttonColors(containerColor = persona.color().copy(alpha = .25f), contentColor = persona.core())
                    ) { Text("Continue") }
                }
            }
        } else if (full && closeAt < 0f) {
            Text(
                "tap to skip", fontSize = 11.sp, color = Color.White.copy(alpha = .3f),
                modifier = Modifier.align(Alignment.BottomEnd).padding(14.dp)
            )
        }
    }
}

/** Settings card: the timer you type, the on/off switch and a replay button. */
@Composable
fun HoloSection() {
    val context = LocalContext.current
    var look by remember { mutableStateOf(HoloStartup.lookOn(context)) }
    var timer by remember { mutableStateOf(HoloStartup.formatDuration(HoloStartup.introSeconds(context))) }
    var note by remember { mutableStateOf("") }
    Text("Holographic look and startup", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
    Spacer(Modifier.height(6.dp))
    Text(
        "The reactor, the cinematic opening with its sound, and the intro. Each launch opens with the next persona. " +
            "The full spoken intro plays only after the time below has passed since the last one; in between you get the short entrance.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .55f)
    )
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Holographic look", modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurface.copy(alpha = .8f))
        Switch(checked = look, onCheckedChange = { look = it; HoloStartup.setLook(context, it); note = "Restart VINCE to see the change." })
    }
    Spacer(Modifier.height(10.dp))
    Text("Play the full intro again after (try 1 min, 4min, 1H, 5H)", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
    OutlinedTextField(value = timer, onValueChange = { timer = it; note = "" }, modifier = Modifier.fillMaxWidth(), singleLine = true)
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Button(onClick = {
            try {
                val s = HoloStartup.parseDuration(timer)
                HoloStartup.setIntroSeconds(context, s)
                timer = HoloStartup.formatDuration(s)
                note = "Saved: full intro again after $timer."
            } catch (e: IllegalArgumentException) {
                note = e.message ?: "Couldn't read that time."
            }
        }) { Text("Save timer") }
        OutlinedButton(onClick = { StartupBus.replay.value = true }) { Text("Replay intro") }
    }
    if (note.isNotBlank()) {
        Spacer(Modifier.height(6.dp))
        Text(note, fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
    }
}
