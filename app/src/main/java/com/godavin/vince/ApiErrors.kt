package com.godavin.vince

import org.json.JSONObject

/**
 * Turns a provider's raw JSON error body into one short readable line, so
 * a failed call shows "This model is currently experiencing high demand"
 * instead of a wall of JSON braces in the chat.
 */
object ApiErrors {
    fun short(body: String, max: Int = 120): String {
        val text = try {
            when (val err = JSONObject(body).opt("error")) {
                is JSONObject -> err.optString("message").ifBlank { body }
                is String -> err
                else -> body
            }
        } catch (e: Exception) {
            body
        }
        return text.replace(Regex("\\s+"), " ").trim().take(max)
    }
}
