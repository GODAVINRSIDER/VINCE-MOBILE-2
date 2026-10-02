package com.godavin.vince

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Phone actions that need NO special access - standard Android intents and
 * system services only, so they behave the same on any Android phone:
 * websites, Google search, timers, maps, flashlight, volume, media keys,
 * battery level, dialer / SMS / WhatsApp drafts, and shortcuts into the
 * system settings pages. Deterministic and checked before the AI.
 *
 * What it deliberately does NOT do: place calls or send messages by
 * itself (it opens the dialer / a prefilled draft and you press the
 * button), and flip Wi-Fi / Bluetooth / mobile data (Android no longer
 * lets ordinary apps do that - it opens the right settings page instead).
 */
object DeviceActions {
    private val OPTS = setOf(RegexOption.IGNORE_CASE)

    private val OPEN_SITE = Regex(
        "^(?:open|go to|visit|launch|take me to)\\s+((?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.(?:com|org|net|io|co|ke|app|dev|info|me|ai|tv|trade|finance|news)(?:/\\S*)?)$", OPTS
    )
    private val GOOGLE = Regex("^google\\s+(.+)$", OPTS)
    private val TIMER_2 = Regex("^(?:set|start|run)\\s+(?:a\\s+)?timer\\s+(?:for|of)\\s+(\\d+)\\s*(seconds?|secs?|minutes?|mins?|hours?|hrs?)$", OPTS)
    private val TIMER_3 = Regex("^(?:set|start|run)\\s+(?:a\\s+)?(\\d+)[\\s-]*(seconds?|secs?|minutes?|mins?|hours?|hrs?)\\s+timer$", OPTS)
    private val NAVIGATE = Regex("^(?:navigate to|directions to|take me to|show me on (?:the )?map)\\s+(.+)$", OPTS)

    private val TORCH = Regex("^(?:please\\s+)?(?:turn\\s+)?(?:(on|off)\\s+)?(?:the\\s+)?(?:flash\\s?light|torch)(?:\\s+(on|off))?$", OPTS)
    private val VOL_STEP = Regex("^(?:turn\\s+)?(?:the\\s+)?volume\\s+(up|down)$", OPTS)
    private val VOL_WORD = Regex("^(?:turn\\s+it\\s+|make\\s+it\\s+)?(louder|quieter)$", OPTS)
    private val VOL_MUTE = Regex("^(mute|unmute)(?:\\s+(?:the\\s+)?(?:phone|sound|volume|music))?$", OPTS)
    private val VOL_SET = Regex("^(?:set\\s+)?(?:the\\s+)?volume\\s+(?:to\\s+)?(\\d{1,3})\\s*%?$", OPTS)
    private val MEDIA = Regex("^(?:please\\s+)?(play|pause|resume|stop)\\s+(?:the\\s+)?(?:music|song|media|playback|audio)$", OPTS)
    private val MEDIA_SKIP = Regex("^(?:(next|skip)|(?:previous|last))\\s+(?:song|track)$", OPTS)
    private val BATTERY = Regex("^(?:what(?:'s| is)?\\s+(?:my\\s+)?|how much\\s+)?battery(?:\\s+(?:level|percentage|percent|status|left|do i have|is left))*\\??$", OPTS)

    private val NUMBER = "(\\+?\\d[\\d\\s\\-]{5,}\\d)"
    private val DIAL = Regex("^(?:call|dial|phone|ring)\\s+$NUMBER$", OPTS)
    private val SMS = Regex("^(?:text|sms)\\s+$NUMBER\\s+(?:saying|that says|say|:)\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val WHATSAPP = Regex("^(?:whatsapp|wa)\\s+$NUMBER\\s+(?:saying|that says|say|:)\\s*(.+)$", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    private val SETTINGS_PAGE = Regex(
        "^(?:open|show|go to)\\s+(?:the\\s+)?(wi-?fi|bluetooth|battery|display|sound|airplane(?:\\s+mode)?|location|date(?:\\s+and\\s+time)?|mobile data|data)\\s+settings$", OPTS
    )
    private val RADIO_TOGGLE = Regex(
        "^(?:please\\s+)?(?:turn|switch)\\s+(on|off)\\s+(?:the\\s+)?(wi-?fi|bluetooth|mobile data|data|airplane(?:\\s+mode)?)$", OPTS
    )

    @Volatile private var torchOn = false

    fun handle(context: Context, rawText: String): String? {
        val text = rawText.replace('\u2019', '\'').trim().trimEnd('.', '!', '?')

        OPEN_SITE.find(text)?.let { m ->
            val raw = m.groupValues[1]
            val url = if (raw.startsWith("http", ignoreCase = true)) raw else "https://$raw"
            return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Opening $raw.", "I couldn't open a browser for $raw.")
        }

        GOOGLE.find(text)?.let { m ->
            val q = m.groupValues[1].trim()
            val url = "https://www.google.com/search?q=" + Uri.encode(q)
            return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "Searching Google for \"$q\".", "I couldn't open a browser.")
        }

        TIMER_2.find(text)?.let { return startTimer(context, toSeconds(it.groupValues[1].toInt(), it.groupValues[2])) }
        TIMER_3.find(text)?.let { return startTimer(context, toSeconds(it.groupValues[1].toInt(), it.groupValues[2])) }

        NAVIGATE.find(text)?.let { m ->
            val place = m.groupValues[1].trim()
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(place)))
            return launch(context, intent, "Opening maps for $place.", "I couldn't find a maps app.")
        }

        TORCH.find(text)?.let { m ->
            val word = (m.groupValues[1].ifBlank { m.groupValues[2] }).lowercase()
            val want = when (word) { "on" -> true; "off" -> false; else -> !torchOn }
            return setTorch(context, want)
        }

        VOL_STEP.find(text)?.let { return adjustVolume(context, if (it.groupValues[1].lowercase() == "up") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER) }
        VOL_WORD.find(text)?.let { return adjustVolume(context, if (it.groupValues[1].lowercase() == "louder") AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER) }
        VOL_MUTE.find(text)?.let {
            val mute = it.groupValues[1].lowercase() == "mute"
            return adjustVolume(context, if (mute) AudioManager.ADJUST_MUTE else AudioManager.ADJUST_UNMUTE,
                if (mute) "Muted." else "Unmuted.")
        }
        VOL_SET.find(text)?.let { return setVolumePercent(context, it.groupValues[1].toInt()) }

        MEDIA.find(text)?.let { m ->
            val key = when (m.groupValues[1].lowercase()) {
                "play", "resume" -> KeyEvent.KEYCODE_MEDIA_PLAY
                "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
                else -> KeyEvent.KEYCODE_MEDIA_STOP
            }
            return mediaKey(context, key, "Sent \"${m.groupValues[1].lowercase()}\" to your music player.")
        }
        MEDIA_SKIP.find(text)?.let { m ->
            val next = m.groupValues[1].isNotBlank()
            return mediaKey(context, if (next) KeyEvent.KEYCODE_MEDIA_NEXT else KeyEvent.KEYCODE_MEDIA_PREVIOUS,
                if (next) "Skipped to the next track." else "Went back to the previous track.")
        }

        if (BATTERY.matches(text)) return batteryStatus(context)

        DIAL.find(text)?.let { m ->
            val number = m.groupValues[1].filter { it.isDigit() || it == '+' }
            return launch(context, Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")),
                "Opened the dialer with $number - press call when you're ready.", "I couldn't open the dialer.")
        }
        SMS.find(text)?.let { m ->
            val number = m.groupValues[1].filter { it.isDigit() || it == '+' }
            val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:$number")).putExtra("sms_body", m.groupValues[2].trim())
            return launch(context, intent, "Opened a text to $number with your message - press send.", "I couldn't open a messaging app.")
        }
        WHATSAPP.find(text)?.let { m ->
            val digits = normalizeForWhatsApp(m.groupValues[1])
            val url = "https://wa.me/$digits?text=" + Uri.encode(m.groupValues[2].trim())
            return launch(context, Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                "Opened WhatsApp with your message to +$digits - press send.", "I couldn't open WhatsApp.")
        }

        SETTINGS_PAGE.find(text)?.let { m -> return openSettingsPage(context, m.groupValues[1].lowercase(), false, true) }
        RADIO_TOGGLE.find(text)?.let { m ->
            val turnOn = m.groupValues[1].lowercase() == "on"
            return openSettingsPage(context, m.groupValues[2].lowercase(), true, turnOn)
        }
        return null
    }

    // ---------------- timers ----------------

    private fun toSeconds(amount: Int, unit: String): Int {
        val u = unit.lowercase()
        return when {
            u.startsWith("sec") -> amount
            u.startsWith("min") -> amount * 60
            else -> amount * 3600
        }
    }

    private fun startTimer(context: Context, seconds: Int): String {
        if (seconds <= 0 || seconds > 24 * 3600) return "Give me a timer between 1 second and 24 hours."
        val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, seconds)
            putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            putExtra(AlarmClock.EXTRA_MESSAGE, "VINCE timer")
        }
        val label = when {
            seconds % 3600 == 0 -> "${seconds / 3600} hour(s)"
            seconds % 60 == 0 -> "${seconds / 60} minute(s)"
            else -> "$seconds seconds"
        }
        return launch(context, intent, "Timer set for $label in your Clock app.", "I couldn't reach your Clock app to start a timer.")
    }

    // ---------------- flashlight ----------------

    private fun setTorch(context: Context, on: Boolean): String {
        return try {
            val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull { camId ->
                val c = cm.getCameraCharacteristics(camId)
                c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                    c.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
            } ?: cm.cameraIdList.firstOrNull { camId ->
                cm.getCameraCharacteristics(camId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "This phone doesn't report a flashlight I can use."
            cm.setTorchMode(id, on)
            torchOn = on
            ActivityLog.addEvent(context, if (on) "Flashlight on" else "Flashlight off")
            if (on) "Flashlight on." else "Flashlight off."
        } catch (e: Exception) {
            "I couldn't switch the flashlight (another app may be using the camera)."
        }
    }

    // ---------------- volume / media ----------------

    private fun audio(context: Context) = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun adjustVolume(context: Context, direction: Int, reply: String? = null): String {
        return try {
            val am = audio(context)
            am.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)
            val level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            reply ?: "Media volume is now ${Math.round(level * 100f / max)}%."
        } catch (e: Exception) {
            "I couldn't change the volume (Do Not Disturb or a system lock may be blocking it)."
        }
    }

    private fun setVolumePercent(context: Context, percent: Int): String {
        if (percent !in 0..100) return "Give me a volume between 0 and 100."
        return try {
            val am = audio(context)
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, Math.round(max * percent / 100f), AudioManager.FLAG_SHOW_UI)
            "Media volume set to about $percent%."
        } catch (e: Exception) {
            "I couldn't set the volume."
        }
    }

    private fun mediaKey(context: Context, keyCode: Int, okMsg: String): String {
        return try {
            val am = audio(context)
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
            "$okMsg If nothing happened, no music app is active right now."
        } catch (e: Exception) {
            "I couldn't send that to the music player."
        }
    }

    // ---------------- battery ----------------

    private fun batteryStatus(context: Context): String {
        return try {
            val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
            val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            if (level < 0) return "I couldn't read the battery level."
            val pct = Math.round(level * 100f / scale)
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            "Battery is at $pct%${if (charging) " and charging" else ""}."
        } catch (e: Exception) {
            "I couldn't read the battery level."
        }
    }

    // ---------------- settings shortcuts ----------------

    private fun openSettingsPage(context: Context, what: String, isToggle: Boolean, turnOn: Boolean): String {
        val action: String
        val label: String
        when {
            what.startsWith("wi") || what == "mobile data" || what == "data" -> {
                action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
                label = if (what.startsWith("wi")) "Wi-Fi" else "mobile data"
            }
            what == "bluetooth" -> { action = Settings.ACTION_BLUETOOTH_SETTINGS; label = "Bluetooth" }
            what == "battery" -> { action = Settings.ACTION_BATTERY_SAVER_SETTINGS; label = "battery" }
            what == "display" -> { action = Settings.ACTION_DISPLAY_SETTINGS; label = "display" }
            what == "sound" -> { action = Settings.ACTION_SOUND_SETTINGS; label = "sound" }
            what.startsWith("airplane") -> { action = Settings.ACTION_AIRPLANE_MODE_SETTINGS; label = "airplane mode" }
            what == "location" -> { action = Settings.ACTION_LOCATION_SOURCE_SETTINGS; label = "location" }
            else -> { action = Settings.ACTION_DATE_SETTINGS; label = "date and time" }
        }
        val ok = if (isToggle) {
            "Android doesn't let apps flip $label by themselves, so I opened its ${if (turnOn) "on" else "off"} switch for you to tap."
        } else {
            "Opened your $label settings."
        }
        return launch(context, Intent(action), ok, "I couldn't open the $label settings on this phone.")
    }

    // ---------------- helpers ----------------

    /** wa.me wants country code + number, digits only. A leading 0 is treated as a Kenyan number (254). */
    private fun normalizeForWhatsApp(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        return when {
            raw.trim().startsWith("+") -> digits
            digits.startsWith("0") -> "254" + digits.drop(1)
            else -> digits
        }
    }

    private fun launch(context: Context, intent: Intent, okMsg: String, failMsg: String): String {
        return try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ActivityLog.addEvent(context, okMsg.trimEnd('.'))
            okMsg
        } catch (e: ActivityNotFoundException) {
            failMsg
        } catch (e: SecurityException) {
            failMsg
        }
    }
}
