package com.godavin.vince

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v2.12 - the holographic arc reactor (same drawing as VINCE 3.0's): rotating arcs, dashed rings, data ticks, sparks and a
 * glowing core, with the persona's mark (V / C / D) in the middle. One shape per persona (arc count, tick density, dash size)
 * and one colour. Modes: 0 idle (slow, breathing when [breathe]), 1 listening, 2 thinking (fast spin), 3 speaking (pulses).
 */
object Holo {
    const val TAU = 6.2831853f
    fun hue(p: Persona): Float = when (p) { Persona.VINCE -> 190f; Persona.CLARA -> 330f; Persona.DAVINA -> 275f }
    fun arcs(p: Persona): Int = when (p) { Persona.VINCE -> 3; Persona.CLARA -> 5; Persona.DAVINA -> 4 }
    fun ticks(p: Persona): Int = when (p) { Persona.VINCE -> 72; Persona.CLARA -> 96; Persona.DAVINA -> 60 }
    fun dashOn(p: Persona): Float = when (p) { Persona.VINCE -> .06f; Persona.CLARA -> .04f; Persona.DAVINA -> .08f }
    fun dashOff(p: Persona): Float = when (p) { Persona.VINCE -> .035f; Persona.CLARA -> .025f; Persona.DAVINA -> .05f }
    fun modeOf(status: String): Int = when (status) { "LISTENING" -> 1; "THINKING" -> 2; "SPEAKING" -> 3; else -> 0 }
    fun hsl(hue: Float, l: Float, a: Float): Color = Color.hsl(hue, 1f, (l / 100f).coerceIn(0f, 1f), a.coerceIn(0f, 1f))
}

fun DrawScope.drawReactor(p: Persona, mode: Int, t: Float, cx: Float, cy: Float, R0: Float, breathe: Boolean, alpha: Float = 1f) {
    val hue = Holo.hue(p)
    fun col(l: Float, a: Float): Color = Holo.hsl(hue, l, a * alpha)
    val sp = when (mode) { 0 -> .25f; 1 -> .35f; 2 -> 1.8f; else -> .45f }
    val amp = when (mode) { 0 -> if (breathe) .4f else .06f; 1 -> .1f; 2 -> .12f; else -> .4f }
    val wob = if (mode == 3) .5f + .5f * sin(t * 9f) * sin(t * 3.1f) else .5f + .5f * sin(t * (if (breathe) 1.3f else 1.6f))
    val pulse = 1f + amp * wob
    val r = if (breathe && mode == 0) R0 * (1f + .035f * sin(t * 1.3f)) else R0
    val c = Offset(cx, cy)

    drawCircle(
        brush = Brush.radialGradient(
            0f to col(96f, .95f), min(.2f, .1f * pulse) to col(70f, .65f), .55f to col(40f, .12f), 1f to col(30f, 0f),
            center = c, radius = r * .95f
        ),
        radius = r * .95f, center = c
    )
    if (breathe) {
        drawCircle(
            brush = Brush.radialGradient(
                0f to col(50f, 0f), .69f to col(50f, 0f), .7f to col(60f, .2f * wob), 1f to col(50f, 0f),
                center = c, radius = r * 1.3f
            ),
            radius = r * 1.3f, center = c
        )
    }
    // outer thick arcs
    val n = Holo.arcs(p)
    val arcR = r * .96f
    for (i in 0 until n) {
        val a = t * sp * .6f + i * Holo.TAU / n
        drawArc(
            color = col(62f, .95f), startAngle = Math.toDegrees(a.toDouble()).toFloat(),
            sweepAngle = Math.toDegrees((Holo.TAU / n * .62f).toDouble()).toFloat(), useCenter = false,
            topLeft = Offset(cx - arcR, cy - arcR), size = Size(arcR * 2, arcR * 2),
            style = Stroke(width = r * .045f, cap = StrokeCap.Butt)
        )
    }
    // thin circles
    val thin = max(1f, r * .006f)
    for (rr in floatArrayOf(.88f, .8f, .52f, .34f)) drawCircle(col(60f, .5f), radius = r * rr, center = c, style = Stroke(width = thin))
    // dashed rings
    drawCircle(
        col(55f, .75f), radius = r * .72f, center = c,
        style = Stroke(width = r * .05f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * Holo.dashOn(p), r * Holo.dashOff(p)), -t * sp * r * .2f))
    )
    drawCircle(
        col(70f, .5f), radius = r * .6f, center = c,
        style = Stroke(width = r * .1f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(r * .015f, r * .02f), t * sp * r * .3f))
    )
    // data ticks
    val tk = Holo.ticks(p)
    val tw = max(1.2f, r * .012f)
    for (i in 0 until tk) {
        val a = i.toFloat() / tk * Holo.TAU - t * sp * .5f
        val l = r * (.04f + .05f * ((i * 7) % 5) / 4f)
        val x0 = cx + cos(a) * r * .43f
        val y0 = cy + sin(a) * r * .43f
        drawLine(col(75f, .3f + .5f * ((i * 13) % 7) / 7f), Offset(x0, y0), Offset(cx + cos(a) * (r * .43f + l), cy + sin(a) * (r * .43f + l)), strokeWidth = tw)
    }
    // sparks
    for (k in 0 until 9) {
        val a = t * sp * .8f * (if (k % 2 == 1) 1f else -1f) + k * .8f
        val rr = r * (.55f + .4f * ((k * 37) % 10) / 10f)
        drawCircle(col(90f, .9f), radius = max(1.5f, r * .012f), center = Offset(cx + cos(a) * rr, cy + sin(a) * rr))
    }
}

private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** The persona mark in the middle of the ring: V / C / D in the persona colour on a dark disc. */
fun DrawScope.drawPersonaMark(p: Persona, cx: Float, cy: Float, R: Float, alpha: Float = 1f) {
    val hue = Holo.hue(p)
    val c = Offset(cx, cy)
    drawCircle(Color.Black.copy(alpha = .72f * alpha), radius = R * .22f, center = c)
    drawCircle(Holo.hsl(hue, 66f, .95f * alpha), radius = R * .22f, center = c, style = Stroke(width = max(1.5f, R * .014f)))
    markPaint.color = Holo.hsl(hue, 82f, alpha).toArgb()
    markPaint.textSize = R * .25f
    markPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    markPaint.textAlign = Paint.Align.CENTER
    markPaint.setShadowLayer(R * .12f, 0f, 0f, Holo.hsl(hue, 66f, alpha).toArgb())
    drawContext.canvas.nativeCanvas.drawText(p.displayName.take(1), cx, cy + R * .085f, markPaint)
}

/** One shared clock (about 30 frames a second - gentle on the battery) that only the draw lambdas read. */
@Composable
fun rememberHoloClock(): State<Float> {
    val clock = remember { mutableStateOf(0f) }
    LaunchedEffect(Unit) {
        var last = 0L
        while (true) {
            withFrameNanos { n ->
                val ms = n / 1_000_000L
                if (ms - last >= 33L) {
                    last = ms
                    clock.value = ms / 1000f
                }
            }
        }
    }
    return clock
}

@Composable
fun HoloReactorView(persona: Persona, status: String, modifier: Modifier = Modifier, breathe: Boolean = true, onTap: (() -> Unit)? = null) {
    val clock = rememberHoloClock()
    val m = if (onTap != null) {
        modifier.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onTap() }
    } else modifier
    Canvas(m) {
        val t = clock.value
        val r = size.minDimension * .38f
        drawReactor(persona, Holo.modeOf(status), t, size.width / 2f, size.height / 2f, r, breathe)
        drawPersonaMark(persona, size.width / 2f, size.height / 2f, r)
    }
}

private fun personaBlurb(p: Persona): String = when (p) { Persona.VINCE -> "Analytical"; Persona.CLARA -> "Warm"; Persona.DAVINA -> "Calm" }

@Composable
private fun PersonaChip(p: Persona, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .border(if (active) 1.5.dp else 1.dp, p.color().copy(alpha = if (active) 1f else .35f), RoundedCornerShape(14.dp))
            .background(p.color().copy(alpha = if (active) .16f else .04f))
            .clickable { onClick() }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(p.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = p.color())
        Text(personaBlurb(p), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f))
        Text(if (active) "\u25CF Active" else "Standby", fontSize = 10.sp, color = if (active) Color(0xFF34D399) else MaterialTheme.colorScheme.onSurface.copy(alpha = .4f))
    }
}

/** Home: the reactor (it replaces the old ring) with the persona cards under it. Tap a card to pick a persona; tap the reactor for the next one. */
@Composable
fun ReactorHome(active: Persona, status: String, onPersonaTapped: (Persona) -> Unit) {
    val order = Persona.values().toList()
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        HoloReactorView(
            active, status,
            Modifier.fillMaxWidth(.8f).widthIn(max = 330.dp).aspectRatio(1f),
            breathe = true,
            onTap = { onPersonaTapped(order[(order.indexOf(active) + 1) % order.size]) }
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            order.forEach { p -> PersonaChip(p, p == active, Modifier.weight(1f)) { onPersonaTapped(p) } }
        }
    }
}

/** A new, empty chat: the reactor breathes instead of a static circle. */
@Composable
fun HoloEmptyChat(persona: Persona) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            HoloReactorView(persona, "STANDBY", Modifier.size(210.dp), breathe = true)
            Spacer(Modifier.height(6.dp))
            Text(
                "Hi, I\u2019m " + persona.displayName.lowercase().replaceFirstChar { it.uppercase() },
                fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                "Ask me anything, or tell me what to do.", fontSize = 12.sp, textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = .6f), modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
