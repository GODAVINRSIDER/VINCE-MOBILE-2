package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * Opens other installed apps by spoken/typed name ("open WhatsApp",
 * "launch MT5"). Checked from PersonalTools.handleLocalCommand, same
 * "deterministic local handler first" pattern as everything else in
 * this file - launching an app is a fact-based action, not something to
 * hand to the AI brain to guess at.
 *
 * In scope: launching the app the user named. Explicitly NOT in scope:
 * controlling what happens inside the app once it's open - this only
 * starts the app's own launch activity and stops there.
 */
object AppLauncher {

    private val PREFIXES = listOf("open ", "launch ", "start ")

    /** Returns the app name to look up if [text] looks like an
     * "open/launch/start <app>" command, otherwise null. */
    fun extractAppNameFromCommand(text: String): String? {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        for (prefix in PREFIXES) {
            if (lower.startsWith(prefix)) {
                val name = trimmed.substring(prefix.length).trim()
                return name.ifBlank { null }
            }
        }
        return null
    }

    /**
     * Finds an installed app whose display label matches [appName] and
     * launches it. Returns the matched app's display label on success (so
     * the reply can say exactly what it opened), or null if nothing
     * matched or the matched app has no launchable activity.
     */
    fun openAppByName(context: Context, appName: String): String? {
        val pm = context.packageManager
        val installedApps: List<ApplicationInfo> = pm.getInstalledApplications(
            PackageManager.GET_META_DATA
        )

        // Prefer an exact (case-insensitive) label match first, so "open
        // WhatsApp" doesn't accidentally grab "WhatsApp Business" if both
        // are installed; fall back to a "contains" match otherwise.
        val exactMatch = installedApps.firstOrNull {
            pm.getApplicationLabel(it).toString().equals(appName, ignoreCase = true)
        }
        val match = exactMatch ?: installedApps.firstOrNull {
            pm.getApplicationLabel(it).toString().contains(appName, ignoreCase = true)
        } ?: return null

        val launchIntent = pm.getLaunchIntentForPackage(match.packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        return pm.getApplicationLabel(match).toString()
    }
}
