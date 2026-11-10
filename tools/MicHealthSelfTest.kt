// Plain-JVM check of MicHealth.kt against the same synthetic signals the PC tests use (tests/test_mic_health.py).
// Run (no Android needed):  kotlinc MicHealth.kt MicHealthSelfTest.kt -include-runtime -d t.jar && java -jar t.jar
package com.godavin.vince

import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

const val RATE = 16000
const val SECS = 5

class Lcg(seed: Long) {
    var s = seed and 0x7FFFFFFF
    fun next(): Double { s = (s * 1103515245L + 12345L) and 0x7FFFFFFF; return (s / 1073741823.5) - 1.0 }
}

fun voice(amp: Int = 9000, noise: Int = 60, seed: Long = 1, secs: Int = SECS): ShortArray {
    val r = Lcg(seed)
    return ShortArray(RATE * secs) { i ->
        val t = i.toDouble() / RATE
        val env = if ((t * 3) % 1.0 < 0.6) max(0.0, sin(2 * PI * 3 * t)).pow(0.5) else 0.0
        val v = (sin(2 * PI * 140 * t) + 0.5 * sin(2 * PI * 280 * t) + 0.25 * sin(2 * PI * 560 * t)) / 1.75
        (amp * env * v + noise * r.next()).toInt().toShort()
    }
}

fun caseOk() = voice()
fun caseDead() = ShortArray(RATE * SECS)
fun caseSilent(): ShortArray { val r = Lcg(2); return ShortArray(RATE * SECS) { (20 * r.next()).toInt().toShort() } }
fun caseQuiet() = voice(300, 8)
fun caseNoisy() = voice(9000, 1800, 3)
fun caseDropouts(): ShortArray { val s = voice(); for (k in intArrayOf(1, 4, 8)) { val a = 5333 * k + 700; for (j in 0 until 1300) s[a + j] = 0 }; return s }
fun caseDistorted(): ShortArray { val s = voice(9000); return ShortArray(s.size) { max(-32767, min(32767, s[it] * 6)).toShort() } }
fun caseCrackly(): ShortArray {
    val s = voice(); val r = Lcg(9)
    for (k in 0 until 14) { val a = 3000 + k * 5200; for (j in 0 until 160) s[a + j] = (30000 * (if (j % 2 == 1) 1 else -1) * r.next() * 0.9).toInt().toShort() }
    return s
}
fun caseGated(): ShortArray { val s = voice(); return ShortArray(s.size) { if (Math.abs(s[it].toInt()) < 120) 0 else s[it] } }
fun caseShort() = voice(secs = 1).copyOf(5000)

fun main() {
    var bad = 0
    val cases = listOf("ok" to ::caseOk, "dead" to ::caseDead, "silent" to ::caseSilent, "quiet" to ::caseQuiet, "noisy" to ::caseNoisy,
        "dropouts" to ::caseDropouts, "distorted" to ::caseDistorted, "crackly" to ::caseCrackly, "too_short" to ::caseShort)
    for ((want, fn) in cases) {
        val r = MicHealth.analyze(fn(), RATE)
        val ok = r.verdict == want
        if (!ok) bad++
        println((if (ok) "PASS " else "FAIL ") + "%-10s -> %-10s peak=%d noise=%s snr=%s drop=%d clip=%s clicks=%d dead=%s issues=%s".format(want, r.verdict, r.peak, r.noise, r.snr, r.dropouts, r.clip, r.clicks, r.deadRatio, r.issues))
    }
    val g = MicHealth.analyze(caseGated(), RATE)
    val gOk = !g.issues.contains("dropouts"); if (!gOk) bad++
    println((if (gOk) "PASS " else "FAIL ") + "gated pauses are not dropouts -> ${g.verdict} drop=${g.dropouts} dead=${g.deadRatio}")
    val e = MicHealth.explain(MicHealth.analyze(caseDead(), RATE), "Galaxy Buds", MicHealth.analyze(caseOk(), RATE), "phone mic")
    val eOk = e.contains("fault is in the earbuds"); if (!eOk) bad++; println((if (eOk) "PASS " else "FAIL ") + "control ok -> earbuds blamed")
    val e2 = MicHealth.explain(MicHealth.analyze(caseDead(), RATE), "Galaxy Buds", MicHealth.analyze(caseDead(), RATE), "phone mic")
    val e2Ok = e2.contains("same problem"); if (!e2Ok) bad++; println((if (e2Ok) "PASS " else "FAIL ") + "control dead too -> phone/room")
    val j = MicHealth.analyze(ShortArray(0), 16000); val jOk = j.verdict == "too_short"; if (!jOk) bad++; println((if (jOk) "PASS " else "FAIL ") + "empty input")
    println(if (bad == 0) "\nALL PASSED" else "\n$bad FAILED")
    if (bad != 0) System.exit(1)
}
