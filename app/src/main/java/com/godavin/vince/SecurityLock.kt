package com.godavin.vince

import android.content.Context
import java.security.MessageDigest

/**
 * Security/PIN layer - now that VINCE can open other apps, control a
 * floating widget, and read whatever's on screen, a lock screen is
 * worth having (it wasn't while the app was read/answer-only). Optional:
 * if no PIN is ever set, the app behaves exactly as before, no lock
 * screen ever appears. Once set, the PIN is required once per app
 * process launch (not every screen) - stored as a SHA-256 hash only,
 * never the plaintext PIN itself.
 */
object SecurityLock {
    private const val PREFS = "vince_security_prefs"
    private const val KEY_PIN_HASH = "pin_hash"

    private fun hash(pin: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(pin.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun isPinSet(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .contains(KEY_PIN_HASH)
    }

    fun setPin(context: Context, pin: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_PIN_HASH, hash(pin))
            .apply()
    }

    fun clearPin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PIN_HASH)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN_HASH, null) ?: return false
        return stored == hash(pin)
    }
}
