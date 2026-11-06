package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

/**
 * v2.15 - Bluetooth earbuds / headset: finds them, says whether Android exposes a MICROPHONE for them, can route VINCE's listening through
 * the headset mic ("use my headset" - talk to VINCE from across the room), tests that the mic really hears you, and tells you when a
 * headset connects, loses its mic, or drops. The logic lives in BtLogic.kt (unit-tested); this file only talks to Android.
 *
 * How routing works: while "use my headset" is on, the phone keeps a hands-free (SCO) link to the headset, so every speech-recognition
 * session in the app hears the headset mic. Cost: Bluetooth drops to phone-call sound quality while it is on, and music over the same
 * headset sounds worse. "use the phone mic" turns it off. Some phones route the recogniser differently - the mic test says what is
 * actually heard.
 */
object BtAudio {
    private const val PREFS = "vince_btaudio"
    private const val KEY_CHOSEN = "chosen"
    private const val NOTIF_ID = 7301
    private const val DEBOUNCE_MS = 2500L

    @Volatile private var lastSeen: Map<String, Boolean>? = null
    @Volatile private var lastTest: String = "I haven't run a headset mic test yet. Say 'test my headset mic'."
    @Volatile private var started = false
    private val handler = Handler(Looper.getMainLooper())
    private var pendingCheck: Runnable? = null

    private fun am(c: Context) = c.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun chosen(c: Context): String = prefs(c).getString(KEY_CHOSEN, "") ?: ""
    private fun setChosen(c: Context, name: String) { prefs(c).edit().putString(KEY_CHOSEN, name).apply() }

    private fun devices(c: Context): List<BtLogic.Dev> =
        am(c).getDevices(AudioManager.GET_DEVICES_ALL).map { BtLogic.Dev(it.productName?.toString() ?: "", it.isSource, it.type) }

    fun scan(c: Context): List<BtLogic.Headset> = try { BtLogic.group(devices(c)) } catch (e: Exception) { emptyList() }

    private fun hasBtPermission(c: Context): Boolean =
        Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(c, "android.permission.BLUETOOTH_CONNECT") == PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(c: Context): Boolean =
        ContextCompat.checkSelfPermission(c, "android.permission.RECORD_AUDIO") == PackageManager.PERMISSION_GRANTED

    private fun openAppSettings(c: Context) {
        try {
            c.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.packageName)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) { /* nothing more to do */ }
    }

    /** Turn the hands-free link to the headset on/off. True if Android accepted it. Never throws. */
    private fun route(c: Context, on: Boolean): Boolean {
        return try {
            val a = am(c)
            if (Build.VERSION.SDK_INT >= 31) {
                if (on) {
                    val dev = a.availableCommunicationDevices.firstOrNull { it.type == BtLogic.TYPE_SCO || it.type == BtLogic.TYPE_BLE_HEADSET }
                    if (dev == null) false else a.setCommunicationDevice(dev)
                } else {
                    a.clearCommunicationDevice(); true
                }
            } else {
                if (on) { a.startBluetoothSco(); a.isBluetoothScoOn = true } else { a.stopBluetoothSco(); a.isBluetoothScoOn = false }
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    // ---------------------------------------------------------------------------------------------------
    fun describe(c: Context): String = BtLogic.describe(scan(c), chosen(c))

    fun useHeadset(c: Context): String {
        val hs = scan(c)
        if (hs.isEmpty()) return "I don't see a Bluetooth headset connected. Connect it in Bluetooth settings first, then say 'use my headset'. Until then I use the phone mic."
        val h = hs.firstOrNull { it.hasMic } ?: return BtLogic.lineFor(hs[0]) + " So I'm staying on the phone mic."
        if (!hasBtPermission(c)) {
            openAppSettings(c)
            return "To listen through a Bluetooth headset I need the 'Nearby devices' permission. I opened VINCE's settings - allow it under Permissions, then say 'use my headset' again."
        }
        setChosen(c, h.name)
        return if (route(c, true)) {
            "Listening through ${h.name}. Say something, or say 'test my headset mic' and I'll tell you if I really hear you. " +
                "Bluetooth drops to phone-call sound while the mic is on - that's normal. If it disconnects I switch back to the phone mic by myself."
        } else {
            "I saved ${h.name} as your headset, but Android wouldn't hand its mic over just now. Try again in a few seconds, or check that 'Phone calls' is on for it in Bluetooth settings."
        }
    }

    fun usePhone(c: Context): String {
        setChosen(c, "")
        route(c, false)
        return "Back to the phone's own mic and speaker."
    }

    /** Starts a 3-second listen on a background thread (never blocks the screen). Result: 'headset mic result'. */
    fun startMicTest(c: Context): String {
        val hs = scan(c)
        if (hs.isEmpty()) return "I don't see a Bluetooth headset connected, so there's no headset mic to test."
        val h = hs.firstOrNull { it.hasMic } ?: return BtLogic.lineFor(hs[0])
        if (!hasMicPermission(c)) return "I need the microphone permission to test it - allow it in VINCE's settings first."
        if (!hasBtPermission(c)) {
            openAppSettings(c)
            return "I need the 'Nearby devices' permission to use the headset mic. I opened VINCE's settings - allow it, then test again."
        }
        lastTest = "The test is still running - give it a few seconds."
        val app = c.applicationContext
        thread(name = "bt-mic-test") { lastTest = runTest(app, h) }
        return "Listening through ${h.name} for 3 seconds - keep talking. Then say 'headset mic result'."
    }

    fun lastTestResult(): String = lastTest

    private fun runTest(c: Context, h: BtLogic.Headset): String {
        val wasChosen = chosen(c).equals(h.name, ignoreCase = true)
        var rec: AudioRecord? = null
        try {
            route(c, true)
            Thread.sleep(800)   // the hands-free link takes a moment to come up
            val rate = 16000
            val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, 4096) * 2)
            rec = r
            if (r.state != AudioRecord.STATE_INITIALIZED) return "I couldn't open the mic for the test. Another app may be using it."
            val inDev = am(c).getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                (it.type == BtLogic.TYPE_SCO || it.type == BtLogic.TYPE_BLE_HEADSET) && (it.productName?.toString() ?: "").trim().equals(h.name, ignoreCase = true)
            }
            if (inDev != null) r.setPreferredDevice(inDev)
            r.startRecording()
            val buf = ShortArray(1024)
            var peak = 0
            val end = System.currentTimeMillis() + 3000
            while (System.currentTimeMillis() < end) {
                val n = r.read(buf, 0, buf.size)
                for (i in 0 until maxOf(n, 0)) peak = maxOf(peak, Math.abs(buf[i].toInt()))
            }
            r.stop()
            return BtLogic.levelMessage(h.name, peak)
        } catch (e: SecurityException) {
            return "Android blocked the mic test (permission). Allow Microphone and Nearby devices for VINCE and try again."
        } catch (e: Exception) {
            return "The headset mic test failed (${e.javaClass.simpleName}). Wait a few seconds and try again."
        } finally {
            try { rec?.release() } catch (e: Exception) { }
            if (!wasChosen) route(c, false)   // only keep the link if you had chosen the headset
        }
    }

    // ---------------------------------------------------------------------------------------------------
    /** Call once from MainActivity.onCreate: remembers what is connected, re-applies your headset choice, and watches for changes. */
    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext
        try {
            lastSeen = scan(app).associate { it.name to it.hasMic }
            if (chosen(app).isNotBlank() && scan(app).any { it.hasMic && it.name.equals(chosen(app), ignoreCase = true) } && hasBtPermission(app)) route(app, true)
            am(app).registerAudioDeviceCallback(object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { schedule(app) }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { schedule(app) }
            }, handler)
        } catch (e: Exception) {
            started = false
        }
    }

    private fun schedule(app: Context) {
        pendingCheck?.let { handler.removeCallbacks(it) }
        val r = Runnable { check(app) }
        pendingCheck = r
        handler.postDelayed(r, DEBOUNCE_MS)   // a headset shows its music side first and its mic a moment later - wait for both
    }

    private fun check(app: Context) {
        try {
            val cur = scan(app).associate { it.name to it.hasMic }
            val prev = lastSeen
            lastSeen = cur
            if (prev == null) return
            val pref = chosen(app)
            if (pref.isNotBlank() && hasBtPermission(app)) {
                route(app, cur.entries.any { it.key.equals(pref, ignoreCase = true) && it.value })   // follow the headset, or fall back to the phone mic
            }
            val msgs = BtLogic.diff(prev, cur, pref)
            if (msgs.isNotEmpty()) {
                Notifs.show(app, Notifs.CH_PROACTIVE, NOTIF_ID, "Bluetooth headset", msgs.joinToString("\n"))
                VinceLog.i(app, "bluetooth", msgs.joinToString(" | ").take(300))
            }
        } catch (e: Exception) {
            // never let a Bluetooth hiccup affect the app
        }
    }

    // ---------------------------------------------------------------------------------------------------
    /** "check my earbuds", "use my headset", "use the phone mic", "test my headset mic", "headset mic result". Null if not about this. */
    fun handleCommand(context: Context, rawText: String): String? {
        return when (BtLogic.kind(rawText)) {
            "check" -> describe(context)
            "use" -> useHeadset(context)
            "phone" -> usePhone(context)
            "test" -> startMicTest(context)
            "result" -> lastTestResult()
            else -> null
        }
    }
}
