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
    private const val KEY_RECOVERY_QUESTION = "recovery_question"
    private const val KEY_RECOVERY_ANSWER_HASH = "recovery_answer_hash"

    private fun hash(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.trim().lowercase().toByteArray())
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

    /** Clears the PIN AND any recovery question with it - removing the
     * lock should remove the whole lock, not leave an orphaned recovery
     * question behind for a PIN that no longer exists. */
    fun clearPin(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_PIN_HASH)
            .remove(KEY_RECOVERY_QUESTION)
            .remove(KEY_RECOVERY_ANSWER_HASH)
            .apply()
    }

    fun verifyPin(context: Context, pin: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_PIN_HASH, null) ?: return false
        return stored == hash(pin)
    }

    // Forgot-PIN recovery - optional, set alongside the PIN. Same
    // discipline as the PIN itself: the answer is stored as a hash only,
    // never plaintext. The question text itself is plaintext (it has to
    // be, to display it back), but the answer never is.
    fun setRecoveryQuestion(context: Context, question: String, answer: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_RECOVERY_QUESTION, question)
            .putString(KEY_RECOVERY_ANSWER_HASH, hash(answer))
            .apply()
    }

    fun getRecoveryQuestion(context: Context): String? {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECOVERY_QUESTION, null)
    }

    fun hasRecoveryQuestion(context: Context): Boolean = getRecoveryQuestion(context) != null

    fun verifyRecoveryAnswer(context: Context, answer: String): Boolean {
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_RECOVERY_ANSWER_HASH, null) ?: return false
        return stored == hash(answer)
    }
}
