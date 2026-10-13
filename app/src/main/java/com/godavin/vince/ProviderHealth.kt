package com.godavin.vince

import java.util.concurrent.ConcurrentHashMap

/**
 * v2.5 - remembers which AI provider is failing and which is fastest, so a
 * dead provider is skipped instead of waited on, and the quickest healthy one
 * goes first. In-memory only: it re-learns in a couple of messages after the
 * app restarts.
 */
object ProviderHealth {
    private const val COOLDOWN_MS = 45_000L

    private val failedAt = ConcurrentHashMap<String, Long>()

    // Rough starting guesses (ms to first reply); real timings replace them fast.
    private val speedMs = ConcurrentHashMap<String, Long>(
        mapOf("Gemini" to 900L, "Groq" to 700L, "OpenRouter" to 3000L)
    )

    fun markFailed(name: String) { failedAt[name] = System.currentTimeMillis() }

    fun markOk(name: String) { failedAt.remove(name) }

    fun coolingDown(name: String): Boolean {
        val t = failedAt[name] ?: return false
        return System.currentTimeMillis() - t < COOLDOWN_MS
    }

    fun recordSpeed(name: String, ms: Long) {
        val old = speedMs[name] ?: ms
        speedMs[name] = (old * 0.6 + ms * 0.4).toLong()
    }

    fun speed(name: String): Long = speedMs[name] ?: 2000L

    /** Healthy providers first, fastest first. A cooling-down provider is kept
     * (last) so that if everything is cooling down, everything still gets tried. */
    fun order(names: List<String>): List<String> =
        names.sortedWith(compareBy({ coolingDown(it) }, { speed(it) }))
}
