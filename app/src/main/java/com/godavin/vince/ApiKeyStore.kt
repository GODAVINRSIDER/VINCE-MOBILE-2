package com.godavin.vince

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

enum class Provider(val displayName: String, val prefKey: String) {
    GEMINI("Gemini", "gemini_api_key"),
    GROQ("Groq", "groq_api_key"),
    OPENROUTER("OpenRouter", "openrouter_api_key"),
    FINNHUB("Finnhub", "finnhub_api_key"),
}

/**
 * Stores VINCE Mobile's OWN API keys, encrypted at rest on the device -
 * one slot per provider in the fallback rotation (see BrainRouter.kt).
 * Deliberately separate from PC-VINCE's keys in config.py - this app
 * never talks to the PC at all.
 */
object ApiKeyStore {
    private const val PREFS_NAME = "vince_secure_prefs"

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

    fun getKey(context: Context, provider: Provider): String {
        return prefs(context).getString(provider.prefKey, "") ?: ""
    }

    fun saveKey(context: Context, provider: Provider, key: String) {
        prefs(context).edit().putString(provider.prefKey, key.trim()).apply()
    }

    fun hasKey(context: Context, provider: Provider): Boolean =
        getKey(context, provider).isNotBlank()

    /** True if at least one CHAT provider has a key saved - used by the
     * home screen's status line. Finnhub is a data-only key (price
     * lookups), not a chat provider, so it's deliberately excluded here -
     * having only a Finnhub key shouldn't claim "chat is ready". */
    fun hasAnyKey(context: Context): Boolean =
        listOf(Provider.GEMINI, Provider.GROQ, Provider.OPENROUTER).any { hasKey(context, it) }
}
