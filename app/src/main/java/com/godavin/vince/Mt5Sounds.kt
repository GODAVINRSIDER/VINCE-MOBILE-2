package com.godavin.vince

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.OpenableColumns
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * v2.9 - custom sounds for MetaTrader 5 mobile.
 *
 * Two sources feed it:
 *  1. VinceAccessibilityService sees you tap Buy / Sell in MT5 (instant "tap" sound) and then watches
 *     MT5's screen for a few seconds for the result (filled / rejected).
 *  2. VinceNotificationListener sees MT5's own notifications (pending triggered, SL / TP hit,
 *     position closed, and a fallback "filled").
 *
 * MT5's wording is not documented and can differ per broker / language, so everything it sees is
 * kept in a small log (Settings > MT5 trade sounds) and the keyword lists below are easy to tune.
 * Nothing outside the MT5 packages is read, logged or acted on.
 */
object Mt5Sounds {

    const val PKG5 = "net.metaquotes.metatrader5"
    const val PKG4 = "net.metaquotes.metatrader4"

    enum class Ev(val key: String, val label: String) {
        TAP("tap", "Buy / Sell button pressed"),
        FILLED("filled", "Order filled / position opened"),
        PENDING("pending", "Pending order triggered"),
        TP("tp", "Take profit hit"),
        SL("sl", "Stop loss hit"),
        CLOSED("closed", "Position closed"),
        REJECTED("rejected", "Order rejected / failed")
    }

    private const val PREFS = "mt5_sounds"
    private const val K_ENABLED = "enabled"
    private const val K_LOG = "log"
    private const val LOG_MAX = 14

    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ settings

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun enabled(c: Context) = prefs(c).getBoolean(K_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) { prefs(c).edit().putBoolean(K_ENABLED, on).apply() }

    fun debug(c: Context) = prefs(c).getBoolean("debug", false)
    fun setDebug(c: Context, on: Boolean) { prefs(c).edit().putBoolean("debug", on).apply() }

    /** In-memory only: package name + time of the last tap Android reported to VINCE (any app). Lets us tell
     *  "the service isn't getting taps at all" apart from "it gets them but MT5's button isn't recognised". */
    @Volatile var lastClickPkg = ""
    @Volatile var lastClickAt = 0L

    fun evEnabled(c: Context, e: Ev) = prefs(c).getBoolean("on_${e.key}", true)
    fun setEvEnabled(c: Context, e: Ev, on: Boolean) { prefs(c).edit().putBoolean("on_${e.key}", on).apply() }

    private fun customFile(c: Context, e: Ev): File? {
        val f = File(File(c.filesDir, "mt5_sounds"), e.key + ".snd")
        return if (f.exists() && f.length() > 0) f else null
    }

    fun customName(c: Context, e: Ev): String? =
        if (customFile(c, e) != null) prefs(c).getString("name_${e.key}", "custom file") else null

    /** Copies the picked audio file into the app so it keeps working. Returns an error message or null. */
    fun setCustom(c: Context, e: Ev, uri: Uri): String? {
        return try {
            val dir = File(c.filesDir, "mt5_sounds").apply { mkdirs() }
            val out = File(dir, e.key + ".snd")
            var size = 0L
            c.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { o -> size = input.copyTo(o) }
            } ?: return "Couldn't open that file."
            if (size > 3_000_000) { out.delete(); return "That file is too big (limit about 3 MB). Use a short clip." }
            var name = "custom file"
            c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cur ->
                if (cur.moveToFirst()) name = cur.getString(0) ?: name
            }
            prefs(c).edit().putString("name_${e.key}", name).apply()
            null
        } catch (ex: Exception) {
            "Couldn't save that file."
        }
    }

    fun clearCustom(c: Context, e: Ev) {
        File(File(c.filesDir, "mt5_sounds"), e.key + ".snd").delete()
        prefs(c).edit().remove("name_${e.key}").apply()
    }

    // ------------------------------------------------------------ log (for tuning the keywords)

    @Synchronized
    private fun log(c: Context, line: String) {
        val cur = prefs(c).getString(K_LOG, "").orEmpty().split("\n").filter { it.isNotBlank() }
        val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        val next = (cur + "$stamp  ${line.replace('\n', ' ').take(160)}").takeLast(LOG_MAX)
        prefs(c).edit().putString(K_LOG, next.joinToString("\n")).apply()
    }

    fun logLines(c: Context): List<String> =
        prefs(c).getString(K_LOG, "").orEmpty().split("\n").filter { it.isNotBlank() }.reversed()

    fun clearLog(c: Context) { prefs(c).edit().remove(K_LOG).apply() }

    // ------------------------------------------------------------ playing

    private val lastPlayed = HashMap<Ev, Long>()
    @Volatile private var confirmedAt = 0L

    /** [force] is used by the Test buttons: ignores the master switch and de-duplication. */
    fun play(c: Context, e: Ev, force: Boolean = false) {
        if (!force) {
            if (!enabled(c) || !evEnabled(c, e)) return
            val now = SystemClock.elapsedRealtime()
            val gap = if (e == Ev.TAP) 400L else 1500L
            synchronized(lastPlayed) {
                if (now - (lastPlayed[e] ?: 0L) < gap) return
                lastPlayed[e] = now
            }
        }
        val app = c.applicationContext
        io.execute {
            try {
                val f = customFile(app, e)
                if (f != null && playFile(f)) return@execute
                playSynth(e)
            } catch (ex: Exception) {
                // a sound problem must never affect anything else
            }
        }
    }

    private fun attrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    private fun playFile(f: File): Boolean {
        return try {
            val mp = MediaPlayer()
            FileInputStream(f).use { mp.setDataSource(it.fd) }
            mp.setAudioAttributes(attrs())
            mp.setOnCompletionListener { it.release() }
            mp.setOnErrorListener { p, _, _ -> p.release(); true }
            mp.prepare()
            mp.start()
            // safety: never hold the player longer than 15 s
            main.postDelayed({ try { mp.release() } catch (x: Exception) { } }, 15_000)
            true
        } catch (ex: Exception) {
            false
        }
    }

    private fun playSynth(e: Ev) {
        val sr = 22050
        val pcm = synth(e, sr)
        val track = AudioTrack.Builder()
            .setAudioAttributes(attrs())
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sr)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()
        track.write(pcm, 0, pcm.size)
        track.play()
        val ms = pcm.size * 1000L / sr + 300
        main.postDelayed({ try { track.release() } catch (x: Exception) { } }, ms)
    }

    // ---- built-in tones, generated in code so the app needs no sound assets

    private fun tone(sr: Int, freq: Double, ms: Int, vol: Double = 0.6, decay: Double = 6.0, square: Boolean = false): ShortArray {
        val n = sr * ms / 1000
        val out = ShortArray(n)
        for (i in 0 until n) {
            val t = i.toDouble() / sr
            var s = sin(2 * PI * freq * t)
            if (square) s = if (s >= 0) 0.6 else -0.6
            val env = exp(-decay * t / (ms / 1000.0)) * minOf(1.0, i / (sr * 0.004))
            out[i] = (s * env * vol * Short.MAX_VALUE).toInt().toShort()
        }
        return out
    }

    private fun silence(sr: Int, ms: Int) = ShortArray(sr * ms / 1000)

    private fun synth(e: Ev, sr: Int): ShortArray {
        val parts: List<ShortArray> = when (e) {
            Ev.TAP -> listOf(tone(sr, 1800.0, 45, 0.5, 9.0))
            Ev.FILLED -> listOf(tone(sr, 988.0, 90, 0.6, 4.0), tone(sr, 1568.0, 260, 0.6, 5.0))
            Ev.PENDING -> listOf(tone(sr, 660.0, 80), tone(sr, 880.0, 80), tone(sr, 1100.0, 140))
            Ev.TP -> listOf(tone(sr, 523.0, 90), tone(sr, 659.0, 90), tone(sr, 784.0, 90), tone(sr, 1047.0, 380, 0.65, 4.0))
            Ev.SL -> listOf(tone(sr, 200.0, 220, 0.6, 2.5, true), silence(sr, 60), tone(sr, 150.0, 330, 0.6, 2.5, true))
            Ev.CLOSED -> listOf(tone(sr, 988.0, 110), tone(sr, 659.0, 220, 0.6, 5.0))
            Ev.REJECTED -> listOf(tone(sr, 320.0, 110, 0.6, 3.0, true), silence(sr, 70), tone(sr, 320.0, 110, 0.6, 3.0, true), silence(sr, 70), tone(sr, 240.0, 200, 0.6, 3.0, true))
        }
        val total = parts.sumOf { it.size }
        val out = ShortArray(total)
        var pos = 0
        for (p in parts) { System.arraycopy(p, 0, out, pos, p.size); pos += p.size }
        return out
    }

    // ------------------------------------------------------------ source 1: MT5 notifications

    private val RX_SL = Regex("\\[sl[ \\]]|stop loss (?:hit|triggered|activated|reached)|stopped out|\\bby sl\\b")
    private val RX_TP = Regex("\\[tp[ \\]]|take profit (?:hit|triggered|activated|reached)|\\bby tp\\b")
    private val RX_REJECT = Regex("reject|invalid|failed|no money|not enough|market (?:is )?closed|off quotes|requote|timeout|trade is disabled|too many requests")
    private val RX_PENDING = Regex("(?:pending|limit|stop).{0,40}(?:trigger|activat|execut|fill)|(?:trigger|activat).{0,40}(?:pending|limit|stop)")
    private val RX_CLOSED = Regex("\\bclos(?:e|ed|ing)\\b")
    private val RX_FILLED = Regex("\\b(?:opened|filled|executed|done|placed)\\b|order .{0,30}\\bdone\\b")

    fun classify(textLower: String): Ev? = when {
        RX_SL.containsMatchIn(textLower) -> Ev.SL
        RX_TP.containsMatchIn(textLower) -> Ev.TP
        RX_REJECT.containsMatchIn(textLower) -> Ev.REJECTED
        RX_PENDING.containsMatchIn(textLower) -> Ev.PENDING
        RX_CLOSED.containsMatchIn(textLower) -> Ev.CLOSED
        RX_FILLED.containsMatchIn(textLower) -> Ev.FILLED
        else -> null
    }

    fun onNotification(c: Context, sbn: StatusBarNotification) {
        if (sbn.packageName != PKG5 && sbn.packageName != PKG4) return
        if (!enabled(c)) return
        if (sbn.isOngoing) return
        if (sbn.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = sbn.notification.extras
        val title = ex.getCharSequence("android.title")?.toString().orEmpty()
        val text = (ex.getCharSequence("android.bigText") ?: ex.getCharSequence("android.text"))?.toString().orEmpty()
        val line = "$title | $text".trim()
        if (line == "|") return
        val ev = classify(line.lowercase())
        log(c, "notif -> ${ev?.key ?: "no match"}: $line")
        if (ev == null) return
        // already announced by the on-screen confirmation a moment ago
        if (ev == Ev.FILLED && SystemClock.elapsedRealtime() - confirmedAt < 12_000) return
        play(c, ev)
    }

    // ------------------------------------------------------------ source 2: taps + on-screen result

    @Volatile private var armedAt = 0L
    @Volatile private var lastCheckAt = 0L
    @Volatile private var lastSeen = ""

    private val RX_TRADE_BTN = Regex("\\b(?:buy|sell)\\b")
    private val RX_NOT_BTN = Regex("limit|stop|modify|close|cancel|chart|depth|history|journal|watch|settings|new order|trade$")
    private val RX_PLACE_BTN = Regex("^(?:place|place order|send|send order)$")
    private val RX_OK = Regex("\\bdone\\b|order placed|request executed|\\bexecuted\\b|\\bfilled\\b|position opened|placed")
    private val RX_FAIL = Regex("reject|invalid|not enough money|no money|market (?:is )?closed|off quotes|requote|failed|\\berror\\b|trade is disabled|too many requests|timeout")

    private fun labelOf(event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        event.contentDescription?.let { sb.append(it).append(' ') }
        val src = event.source
        if (src != null) {
            src.text?.let { sb.append(it).append(' ') }
            src.contentDescription?.let { sb.append(it).append(' ') }
            for (i in 0 until minOf(src.childCount, 6)) {
                val ch = src.getChild(i) ?: continue
                ch.text?.let { sb.append(it).append(' ') }
                ch.contentDescription?.let { sb.append(it).append(' ') }
            }
        }
        return sb.toString().trim().lowercase()
    }

    private fun isTradeButton(label: String): Boolean {
        if (label.isBlank() || label.length > 60) return false
        if (RX_PLACE_BTN.matches(label)) return true
        return RX_TRADE_BTN.containsMatchIn(label) && !RX_NOT_BTN.containsMatchIn(label)
    }

    private fun screenText(service: AccessibilityService, event: AccessibilityEvent): String {
        val sb = StringBuilder()
        event.text?.forEach { sb.append(it).append(' ') }
        val root = service.rootInActiveWindow
        if (root != null && (root.packageName == PKG5 || root.packageName == PKG4)) {
            var count = 0
            fun walk(n: AccessibilityNodeInfo?) {
                if (n == null || count > 120) return
                count++
                n.text?.let { sb.append(it).append(' ') }
                n.contentDescription?.let { sb.append(it).append(' ') }
                for (i in 0 until n.childCount) walk(n.getChild(i))
            }
            walk(root)
        }
        return sb.toString().lowercase()
    }

    fun onAccessibility(service: AccessibilityService, event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            lastClickPkg = pkg
            lastClickAt = System.currentTimeMillis()
        }
        if (pkg != PKG5 && pkg != PKG4) return
        val c = service.applicationContext
        if (!enabled(c)) return
        val now = SystemClock.elapsedRealtime()

        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            val label = labelOf(event)
            if (debug(c)) log(c, "click: \"${label.take(60)}\" class=${event.className} match=${isTradeButton(label)}")
            if (isTradeButton(label)) {
                play(c, Ev.TAP)
                armedAt = now
                lastSeen = ""
                log(c, "tap: \"${label.take(60)}\"")
            }
            return
        }

        val armed = armedAt
        if (armed == 0L) return
        if (now - armed > 8_000) {
            if (lastSeen.isNotBlank()) log(c, "no result recognised after tap. Screen said: ${lastSeen.take(120)}")
            armedAt = 0L
            return
        }
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            event.eventType != AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) return
        if (now - lastCheckAt < 300) return
        lastCheckAt = now

        val text = screenText(service, event)
        if (text.isBlank()) return
        lastSeen = text.replace(Regex("\\s+"), " ").trim()
        val verdict = when {
            RX_FAIL.containsMatchIn(text) -> Ev.REJECTED
            RX_OK.containsMatchIn(text) -> Ev.FILLED
            else -> null
        } ?: return
        armedAt = 0L
        log(c, "screen -> ${verdict.key}: ${lastSeen.take(120)}")
        if (verdict == Ev.FILLED) confirmedAt = now
        play(c, verdict)
    }
}
