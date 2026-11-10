package com.godavin.vince

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * v2.16 - microphone HEALTH check. Pure Kotlin (no Android classes), so it is unit-testable on a plain JVM. A port of mic_health.py in VINCE 3.0
 * (PC): same thresholds, same verdicts - keep them identical.
 *
 * "Does the headset have a mic?" only says a mic EXISTS. This judges what the mic actually SOUNDS like: dead, very quiet, hissy, cutting out,
 * crackly or distorted. Honest limit: software only hears the audio. A broken mic, a mic muted with the earbud's own button, a blocked
 * permission and a mic that isn't routed to the app all look the same ("no sound"). So a failing headset can be compared with a CONTROL test
 * of the phone's own mic: control fine + headset bad = the earbuds; both bad = something on the phone, the room, or you weren't speaking.
 */
object MicHealth {
    const val FRAME_MS = 20
    const val MIN_FRAMES = 25
    const val SILENT_PEAK = 150
    const val QUIET_PEAK = 800
    const val NOISE_FLOOR = 500.0
    const val MIN_SNR_DB = 6.0
    const val DEAD_PEAK = 2
    const val DROPOUT_FRAMES = 3
    const val DROPOUT_MAX_FRAMES = 6
    const val DROPOUT_LOUD = 0.25
    const val DROPOUTS_BAD = 2
    const val CLIP_LEVEL = 32000
    const val CLIP_BAD = 0.01
    const val CLICK_RATIO = 3.0
    const val CLICK_MIN_RMS = 1500.0
    const val CLICKS_BAD = 4

    data class Result(
        val verdict: String,
        val issues: List<String> = emptyList(),
        val peak: Int = 0,
        val rms: Double = 0.0,
        val noise: Double = 0.0,
        val speech: Double = 0.0,
        val snr: Double = 0.0,
        val dropouts: Int = 0,
        val clip: Double = 0.0,
        val clicks: Int = 0,
        val deadRatio: Double = 0.0,
        val seconds: Double = 0.0
    )

    private fun round(x: Double, digits: Int): Double {
        var m = 1.0
        repeat(digits) { m *= 10.0 }
        return Math.rint(x * m) / m
    }

    private fun percentile(sorted: List<Double>, p: Double): Double =
        if (sorted.isEmpty()) 0.0 else sorted[Math.rint(p * (sorted.size - 1)).toInt()]

    /** samples = 16-bit signed mono, `count` of them valid. Never throws. */
    fun analyze(samples: ShortArray, rate: Int, count: Int = samples.size): Result {
        try {
            val r = if (rate > 0) rate else 16000
            val total = minOf(count, samples.size)
            val n = max(1, r * FRAME_MS / 1000)
            val seconds = round(total / r.toDouble(), 2)
            val nframes = total / n
            if (nframes < MIN_FRAMES) return Result("too_short", seconds = seconds)
            val rms = DoubleArray(nframes)
            val peaks = IntArray(nframes)
            var clipped = 0L
            var sqAll = 0.0
            for (i in 0 until nframes) {
                var mx = 0
                var sq = 0.0
                val base = i * n
                for (k in 0 until n) {
                    val x = samples[base + k].toInt()
                    val a = if (x < 0) -x else x
                    if (a > mx) mx = a
                    sq += x.toDouble() * x
                }
                sqAll += sq
                rms[i] = sqrt(sq / n)
                peaks[i] = mx
                if (mx >= CLIP_LEVEL) {
                    for (k in 0 until n) {
                        val x = samples[base + k].toInt()
                        if (x >= CLIP_LEVEL || x <= -CLIP_LEVEL) clipped++
                    }
                }
            }
            val used = nframes * n
            val peak = peaks.max()
            val rmsAll = round(sqrt(sqAll / used), 1)
            val dead = BooleanArray(nframes) { peaks[it] <= DEAD_PEAK }
            val deadRatio = round(dead.count { it } / nframes.toDouble(), 3)
            if (deadRatio >= 0.98) return Result("dead", peak = peak, rms = rmsAll, deadRatio = deadRatio, seconds = seconds)
            if (peak < SILENT_PEAK) return Result("silent", peak = peak, rms = rmsAll, deadRatio = deadRatio, seconds = seconds)

            val srt = rms.toList().sorted()
            val noise = round(percentile(srt, 0.10), 1)
            val speech = round(percentile(srt, 0.90), 1)
            val snr = round(20.0 * log10(max(speech, 1.0) / max(noise, 1.0)), 1)
            val clip = round(clipped / used.toDouble(), 4)

            val loud = DROPOUT_LOUD * speech
            var runs = 0
            var i = 0
            while (i < nframes) {
                if (dead[i]) {
                    var j = i
                    while (j < nframes && dead[j]) j++
                    if (i > 0 && j < nframes && (j - i) in DROPOUT_FRAMES..DROPOUT_MAX_FRAMES && rms[i - 1] >= loud && rms[j] >= loud) runs++
                    i = j
                } else i++
            }

            var clicks = 0
            for (f in 2 until nframes - 2) {
                if (dead[f - 2] || dead[f + 2] || dead[f - 1] || dead[f + 1]) continue
                if (rms[f] > CLICK_MIN_RMS && rms[f] > CLICK_RATIO * max(max(rms[f - 2], rms[f + 2]), 1.0)) clicks++
            }

            val issues = ArrayList<String>()
            if (runs >= DROPOUTS_BAD) issues.add("dropouts")
            if (clip >= CLIP_BAD) issues.add("distorted")
            if (clicks >= CLICKS_BAD) issues.add("crackly")
            if (noise > NOISE_FLOOR) issues.add("noisy")
            if (peak < QUIET_PEAK) issues.add("quiet") else if (snr < MIN_SNR_DB && !issues.contains("noisy")) issues.add("no_speech")
            return Result(if (issues.isEmpty()) "ok" else issues[0], issues, peak, rmsAll, noise, speech, snr, runs, clip, clicks, deadRatio, seconds)
        } catch (e: Exception) {
            return Result("too_short")
        }
    }

    fun isHealthy(r: Result?): Boolean = r != null && r.verdict == "ok"

    fun advice(v: String, r: Result): String = when (v) {
        "dead" -> "it gave me pure digital silence, not even background hiss, so the audio link is open but nothing is coming through. " +
            "Either the mic is muted (the earbud's own button, or an app), Android is blocking the mic (Settings > Privacy > Microphone access, " +
            "and VINCE's Microphone permission), or the mic itself is dead."
        "silent" -> "I got almost no sound at all (level ${r.peak}). If you weren't talking, run it again and keep talking; otherwise the mic may be muted, " +
            "covered, the earbuds may be in their case or not worn, or the phone is still on its own mic."
        "quiet" -> "it hears you, but very faintly (level ${r.peak}). Speak closer. If it stays this weak when you talk right into it, the mic is " +
            "probably blocked by dirt or damaged."
        "noisy" -> "there is a constant hiss or rumble under your voice (background level ${r.noise.toInt()}). A damaged mic, a bad Bluetooth link or a " +
            "noisy room can do that - try again in a quiet room, closer to the phone."
        "dropouts" -> "the sound cut out ${r.dropouts} times in a few seconds (total silence in the middle of you speaking). That is usually a weak or " +
            "interrupted Bluetooth link - move closer, keep the phone in line of sight, or re-pair the earbuds. (A few earbuds also mute their mic between " +
            "words as noise reduction - if you sound fine on a call, ignore this one.)"
        "distorted" -> "it is clipping - your voice hits the limit and distorts. Speak a bit further from the mic or turn the volume down at the source."
        "crackly" -> "I hear crackle and pops (${r.clicks} in a few seconds). That is usually interference, a bad link, or a mic that is starting to fail."
        "no_speech" -> "I couldn't pick your voice out from the background (only ${r.snr} dB above the noise). Speak right at it and try again."
        else -> "the recording was too short to judge. Try again."
    }

    /** One speakable paragraph. control = the phone mic tested the same way (or null). */
    fun explain(r: Result, name: String, control: Result? = null, controlName: String = "phone mic"): String {
        if (r.verdict == "ok") {
            var s = "The $name mic checks out: clear voice (level ${r.peak}), background level ${r.noise.toInt()}, no dropouts or distortion."
            if (control != null && !isHealthy(control)) s += " (The $controlName didn't test clean, so the room or the phone itself may be the weaker link.)"
            return s
        }
        var s = "The $name mic has a problem: ${advice(r.verdict, r)}"
        val extra = r.issues.filter { it != r.verdict }
        if (extra.isNotEmpty()) s += " Also noticed: " + extra.joinToString(", ") { it.replace('_', ' ') } + "."
        if (control != null) {
            val cv = control.verdict
            s += when {
                cv == "ok" -> " The $controlName tested clean in the same way, so the fault is in the earbuds (or their hands-free link), not the $controlName or the room."
                cv == r.verdict || cv == "dead" || cv == "silent" -> " The $controlName had the same problem (${cv.replace('_', ' ')}), so this points at the phone's settings, the room, or you not speaking, rather than the earbuds."
                else -> " The $controlName had its own issue (${cv.replace('_', ' ')}), so test again in a quiet room to be sure."
            }
        }
        return s
    }
}
