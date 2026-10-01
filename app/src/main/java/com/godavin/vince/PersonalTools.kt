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
 * Reminders are back (Oct 2026) at Vincent's request, rebuilt on
 * alarm-clock alarms that are re-armed on app open and after a reboot -
 * see ReminderCommands.kt.
 */
object PersonalTools {

    fun handleLocalCommand(context: Context, text: String): String? {
        // Trading rules / today's plan, reminders and small phone actions
        // are all deterministic, so they run before anything else.
        PlanCommands.handle(context, text)?.let { return it }
        ReminderCommands.handle(context, text)?.let { return it }
        DeviceActions.handle(context, text)?.let { return it }

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
