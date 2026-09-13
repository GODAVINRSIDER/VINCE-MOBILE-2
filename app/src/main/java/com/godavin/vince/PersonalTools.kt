package com.godavin.vince

import android.content.Context

/**
 * Same "deterministic local handler first, AI brain as fallback"
 * discipline as RealTimeTools - handles memory ("remember that X", "what
 * do you know about me") and opening other installed apps ("open
 * WhatsApp"). Checked in ChatScreen's send() alongside RealTimeTools;
 * returns null if this message matched none of these, in which case it
 * falls through to the normal AI chat path unchanged.
 *
 * Reminders were removed from here per Vincent's call - background
 * firing never worked reliably on his device across several fix
 * attempts, so "remind me..." now just falls through to the AI persona
 * instead of attempting (and failing) to schedule anything.
 */
object PersonalTools {

    fun handleLocalCommand(context: Context, text: String): String? {
        StructuredMemory.handleMemoryCommand(context, text)?.let { return it }

        val appName = AppLauncher.extractAppNameFromCommand(text)
        if (appName != null) {
            val openedLabel = AppLauncher.openAppByName(context, appName)
            return if (openedLabel != null) {
                ActivityLog.addEvent(context, "Opened $openedLabel")
                "Opening $openedLabel."
            } else {
                "I couldn't find an app called \"$appName\" on this phone."
            }
        }

        return null
    }
}
