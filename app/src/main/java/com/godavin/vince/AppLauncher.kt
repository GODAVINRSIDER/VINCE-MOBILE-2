package com.godavin.vince

import android.content.Context
import android.content.Intent
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
 *
 * Fix - this was failing for EVERY app ("couldn't find an app called
 * WhatsApp") because Android 11+ hides other installed apps from
 * PackageManager queries by default unless the app declares what it
 * needs to see (package visibility) - the AndroidManifest now declares
 * a <queries> block for the LAUNCHER intent, and this queries via that
 * exact same intent (queryIntentActivities) rather than the older
 * getInstalledApplications() - the officially recommended, reliable
 * pairing for "find apps with a launcher icon."
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
     * matched.
     */
    fun openAppByName(context: Context, appName: String): String? {
        val pm = context.packageManager
        val launcherIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolveInfos = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(launcherIntent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(launcherIntent, 0)
        }

        // Prefer an exact (case-insensitive) label match first, so "open
        // WhatsApp" doesn't accidentally grab "WhatsApp Business" if both
        // are installed; fall back to a "contains" match otherwise.
        val exactMatch = resolveInfos.firstOrNull {
            it.loadLabel(pm).toString().equals(appName, ignoreCase = true)
        }
        val match = exactMatch ?: resolveInfos.firstOrNull {
            it.loadLabel(pm).toString().contains(appName, ignoreCase = true)
        } ?: return null

        val packageName = match.activityInfo.packageName
        val launchIntent = pm.getLaunchIntentForPackage(packageName) ?: return null
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)

        return match.loadLabel(pm).toString()
    }
}
