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
    TAVILY("Tavily", "tavily_api_key"),
    ELEVENLABS("ElevenLabs", "elevenlabs_api_key"),
    // v2.7 - extra backup brains for chat
    MISTRAL("Mistral", "mistral_api_key"),
    CLOUDFLARE("Cloudflare", "cloudflare_api_token"),
    CLOUDFLARE_ACCOUNT("Cloudflare account ID", "cloudflare_account_id"),
}

/** Non-secret brain settings: which model each backup provider uses (blank = the built-in default). */
object BrainPrefs {
    private const val PREFS = "vince_brain_prefs"
    const val MISTRAL_DEFAULT = "mistral-small-latest"
    // tried in order; a retired one just falls through to the next
    val CLOUDFLARE_DEFAULTS = listOf("@cf/meta/llama-3.3-70b-instruct-fp8-fast", "@cf/meta/llama-3.1-8b-instruct")

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun mistralModel(c: Context): String = prefs(c).getString("mistral_model", "")?.trim().orEmpty()
    fun cloudflareModel(c: Context): String = prefs(c).getString("cloudflare_model", "")?.trim().orEmpty()
    fun setMistralModel(c: Context, v: String) { prefs(c).edit().putString("mistral_model", v.trim()).apply() }
    fun setCloudflareModel(c: Context, v: String) { prefs(c).edit().putString("cloudflare_model", v.trim()).apply() }
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
        listOf(Provider.GEMINI, Provider.GROQ, Provider.OPENROUTER, Provider.MISTRAL).any { hasKey(context, it) } ||
            (hasKey(context, Provider.CLOUDFLARE) && hasKey(context, Provider.CLOUDFLARE_ACCOUNT))
}
