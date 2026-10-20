package com.godavin.vince

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * v2.8 - the home hub, rebuilt from VINCE 3.0's HUD (hud.html) so the two apps look like one family.
 *
 * Same geometry as the PC HUD (a 340 x 240 drawing): three 120-degree wedges of a ring, VINCE on top (cyan), CLARA bottom
 * right (pink), DAVINA bottom left (purple); an outer dashed ring per wedge that keeps rotating (16 s, 20 s reverse, 24 s);
 * trace lines with blinking nodes; the persona's name in the middle with a status line underneath; and the little
 * equaliser bars that move faster the busier the assistant is. The active persona's wedge glows, the others dim.
 *
 * Tap the MIDDLE (the persona's name) to switch to the next persona and its colour, or tap a wedge to pick that persona.
 */

private const val W = 340f
private const val H = 258f          // 240 of drawing + room for the top label, same as the HUD's overflow
private const val CX = 170f
private const val CY = 136f         // the HUD's centre (170,120) pushed down 16 so the top label is not clipped
private const val R_ARC = 88f
private const val R_DASH = 98f
private val AMBER = Color(0xFFF59E0B)

// Wedge start angles (degrees, clockwise from 3 o'clock, y down): same arcs as the HUD, 120 degrees each.
private val STARTS = floatArrayOf(-150f, -30f, 90f)
private val ORDER = listOf(Persona.VINCE, Persona.CLARA, Persona.DAVINA)

// Trace lines and nodes per wedge, copied from hud.html (x1, y1, x2, y2, dashed).
private class Tr(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val dashed: Boolean)
private class Nd(val x: Float, val y: Float, val r: Float, val blink: Boolean)
private val TRACES = arrayOf(
    arrayOf(Tr(170f, 32f, 170f, 15f, false), Tr(130f, 45f, 105f, 20f, true), Tr(210f, 45f, 235f, 20f, true)),
    arrayOf(Tr(252f, 140f, 261f, 162.5f, false), Tr(270f, 120f, 298f, 135f, true), Tr(255f, 175f, 270f, 195f, true)),
    arrayOf(Tr(88f, 140f, 79.1f, 162.5f, false), Tr(70f, 120f, 42f, 135f, true), Tr(85f, 175f, 70f, 195f, true))
)
private val NODES = arrayOf(
    arrayOf(Nd(170f, 15f, 3f, true), Nd(105f, 20f, 2.5f, false), Nd(235f, 20f, 2.5f, false)),
    arrayOf(Nd(261f, 162.5f, 3f, true), Nd(298f, 135f, 2.5f, false), Nd(270f, 195f, 2.5f, false)),
    arrayOf(Nd(79.1f, 162.5f, 3f, true), Nd(42f, 135f, 2.5f, false), Nd(70f, 195f, 2.5f, false))
)
// Side labels (HUD positions; the HUD's y is shifted by the same 16 as the centre).
private val LABEL_XY = arrayOf(Offset(170f, 12f), Offset(277.4f, 198f), Offset(62.6f, 198f))

@Composable
fun PersonaRingHud(active: Persona, status: String, onPersonaTapped: (Persona) -> Unit) {
    // One clock drives every animation and is only READ inside the draw lambdas, so a frame re-draws the Canvas
    // without recomposing the screen.
    val clock = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) androidx.compose.runtime.withFrameNanos { clock.value = it / 1_000_000L }
    }

    val glowV by animateFloatAsState(if (active == Persona.VINCE) 1f else 0.32f, tween(450), label = "gv")
    val glowC by animateFloatAsState(if (active == Persona.CLARA) 1f else 0.32f, tween(450), label = "gc")
    val glowD by animateFloatAsState(if (active == Persona.DAVINA) 1f else 0.32f, tween(450), label = "gd")
    val nameColor by animateColorAsState(active.core(), tween(450), label = "name")
    val glows = floatArrayOf(glowV, glowC, glowD)

    val mode = when (status) {
        "LISTENING" -> 1
        "SPEAKING" -> 3
        "THINKING" -> 2
        else -> 0
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(
            modifier = Modifier
                .widthIn(max = 380.dp)
                .fillMaxWidth()
                .aspectRatio(W / H)
                .pointerInput(active) {
                    detectTapGestures { off ->
                        val s = size.width / W
                        val dx = off.x / s - CX
                        val dy = off.y / s - CY
                        val dist = hypot(dx, dy)
                        if (dist <= 70f) {
                            // the middle: next persona
                            onPersonaTapped(ORDER[(ORDER.indexOf(active) + 1) % ORDER.size])
                        } else if (dist <= 140f) {
                            // a wedge: that persona
                            var deg = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
                            if (deg < STARTS[0]) deg += 360f
                            val idx = when {
                                deg < STARTS[1] -> 0
                                deg < STARTS[2] -> 1
                                else -> 2
                            }
                            onPersonaTapped(ORDER[idx])
                        }
                    }
                }
        ) {
            val s = size.width / W
            val t = clock.value

            // ---- wedge arcs (glow = three strokes of falling width/alpha, like the HUD's drop-shadow)
            ORDER.forEachIndexed { i, p ->
                val a = glows[i]
                val c = p.color()
                val tl = Offset((CX - R_ARC) * s, (CY - R_ARC) * s)
                val sz = Size(2 * R_ARC * s, 2 * R_ARC * s)
                drawArc(c.copy(alpha = 0.10f * a), STARTS[i], 120f, false, tl, sz, style = Stroke(11f * s, cap = StrokeCap.Butt))
                drawArc(c.copy(alpha = 0.22f * a), STARTS[i], 120f, false, tl, sz, style = Stroke(5.5f * s, cap = StrokeCap.Butt))
                drawArc(c.copy(alpha = a), STARTS[i], 120f, false, tl, sz, style = Stroke(2f * s, cap = StrokeCap.Butt))
            }

            // ---- rotating dashed outer rings (16 s, 20 s reverse, 24 s)
            val periods = floatArrayOf(16000f, -20000f, 24000f)
            ORDER.forEachIndexed { i, p ->
                val deg = (t % 100000L) / periods[i] * 360f
                val a = if (i == 0) 1f else 0.6f
                val c = p.color().copy(alpha = a * (0.45f + 0.55f * glows[i]))
                rotate(degrees = deg, pivot = Offset(CX * s, CY * s)) {
                    drawArc(
                        c, STARTS[i], 120f, false,
                        Offset((CX - R_DASH) * s, (CY - R_DASH) * s), Size(2 * R_DASH * s, 2 * R_DASH * s),
                        style = Stroke(1.5f * s, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4f * s, 10f * s)))
                    )
                }
            }

            // ---- traces + nodes (the first node of each wedge blinks, 1.5 s)
            val blink = 0.625f + 0.375f * cos((2 * PI * (t % 1500L) / 1500.0)).toFloat()
            ORDER.forEachIndexed { i, p ->
                val c = p.color()
                val a = glows[i]
                TRACES[i].forEach { tr ->
                    drawLine(
                        c.copy(alpha = 0.55f * a), Offset(tr.x1 * s, (tr.y1 + 16f) * s), Offset(tr.x2 * s, (tr.y2 + 16f) * s),
                        strokeWidth = 1f * s,
                        pathEffect = if (tr.dashed) PathEffect.dashPathEffect(floatArrayOf(1f * s, 5f * s)) else null
                    )
                }
                NODES[i].forEach { n ->
                    drawCircle(c.copy(alpha = a * (if (n.blink) blink else 1f)), n.r * s, Offset(n.x * s, (n.y + 16f) * s))
                }
            }

            // ---- text: side labels, persona name (glowing), status
            drawIntoNative(s) { canvas ->
                val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = 8f * s
                    letterSpacing = 0.25f
                    typeface = Typeface.SANS_SERIF
                }
                ORDER.forEachIndexed { i, p ->
                    label.color = p.color().copy(alpha = 0.75f * glows[i]).toArgb()
                    canvas.drawText(p.displayName, LABEL_XY[i].x * s, LABEL_XY[i].y * s, label)
                }
                val name = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = 22f * s
                    letterSpacing = 0.14f
                    typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD_ITALIC)
                    color = nameColor.toArgb()
                    setShadowLayer(9f * s, 0f, 0f, nameColor.copy(alpha = 0.9f).toArgb())
                }
                canvas.drawText(active.displayName, CX * s, (CY - 6f) * s, name)
                val st = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    textAlign = Paint.Align.CENTER
                    textSize = 10f * s
                    letterSpacing = 0.3f
                    typeface = Typeface.MONOSPACE
                    color = AMBER.toArgb()
                    setShadowLayer(5f * s, 0f, 0f, AMBER.copy(alpha = 0.8f).toArgb())
                }
                canvas.drawText(status, CX * s, (CY + 18f) * s, st)
            }
        }

        // ---- equaliser bars: calm when idle, busier when listening / thinking / speaking
        Canvas(modifier = Modifier.padding(top = 6.dp).height(26.dp).widthIn(max = 160.dp).fillMaxWidth()) {
            val t = clock.value
            val bars = 14
            val gap = 3.dp.toPx()
            val bw = (size.width - gap * (bars - 1)) / bars
            val (lo, hi, hz) = when (mode) {
                1 -> Triple(4f, 18f, 1.1f)
                2 -> Triple(4f, 18f, 0.7f)
                3 -> Triple(4f, 24f, 1.67f)
                else -> Triple(2f, 6f, 0.45f)
            }
            for (i in 0 until bars) {
                val phase = (t - i * 70L) / 1000.0 * hz
                val k = (0.5 - 0.5 * cos(2 * PI * phase)).toFloat()
                val h = (lo + (hi - lo) * k).dp.toPx()
                drawRoundRect(
                    color = active.color().copy(alpha = 0.9f),
                    topLeft = Offset(i * (bw + gap), size.height - h),
                    size = Size(bw, h),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(bw / 2f, bw / 2f)
                )
            }
        }
        Text(
            "Tap the centre to switch persona",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
            modifier = Modifier.padding(top = 6.dp)
        )
    }
}

private fun Color.toArgb(): Int = android.graphics.Color.argb(
    (alpha * 255f).toInt().coerceIn(0, 255), (red * 255f).toInt().coerceIn(0, 255),
    (green * 255f).toInt().coerceIn(0, 255), (blue * 255f).toInt().coerceIn(0, 255)
)

private inline fun DrawScope.drawIntoNative(@Suppress("UNUSED_PARAMETER") s: Float, block: (android.graphics.Canvas) -> Unit) {
    drawContext.canvas.nativeCanvas.let(block)
}
