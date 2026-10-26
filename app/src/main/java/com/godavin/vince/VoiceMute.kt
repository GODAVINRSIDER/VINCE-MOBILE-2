package com.godavin.vince

import android.content.Context

/**
 * v2.12.3 - the chat's "Voice: On / Off" switch, remembered between visits and honoured by everything that reads a reply aloud.
 * Turning it Off also stops whatever is being read right now (before, a reply already being read just carried on).
 */
object VoiceMute {
    private const val PREFS = "vince_voice_toggle"

    fun get(c: Context): Boolean = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("speak_replies", true)

    fun set(c: Context, on: Boolean) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("speak_replies", on).apply()
        if (!on) {
            try { VoiceOutput.stop() } catch (e: Exception) { }
        }
    }
}
