package com.godavin.vince

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores VINCE Mobile's OWN Gemini API key, encrypted at rest on the device.
 * Deliberately separate from PC-VINCE's config.py key - this app never talks
 * to the PC at all (Stage 1 of the standalone-phone-VINCE pivot).
 */
object ApiKeyStore {
    private const val PREFS_NAME = "vince_secure_prefs"
    private const val KEY_GEMINI_API_KEY = "gemini_api_key"

    private fun prefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    fun getKey(context: Context): String {
        return prefs(context).getString(KEY_GEMINI_API_KEY, "") ?: ""
    }

    fun saveKey(context: Context, key: String) {
        prefs(context).edit().putString(KEY_GEMINI_API_KEY, key.trim()).apply()
    }

    fun hasKey(context: Context): Boolean = getKey(context).isNotBlank()
}
