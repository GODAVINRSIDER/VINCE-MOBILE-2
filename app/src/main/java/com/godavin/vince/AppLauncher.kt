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

    // Words that mean the sentence is about something else (a website, a file, a chat ...) rather than an app's name.
    private val NOT_AN_APP = Regex(
        "\\b(website|site|link|links|url|page|tab|browser tab|file|files|folder|document|doc|chat|conversation|thread|message|email|mail|" +
            "account|door|window|image|photo|picture|video|pdf|report|list|account|idea|question|topic|menu)\\b",
        RegexOption.IGNORE_CASE
    )
    private val LEADING = Regex("^(?:the|my|up|a|an)\\s+", RegexOption.IGNORE_CASE)

    /** True when the 'name' reads like part of a sentence (a website, a file, a chat ...). Only used AFTER no installed app matched,
     * so real apps called Files, Mail, Video ... still open. */
    fun looksLikeSentenceNotApp(name: String): Boolean = NOT_AN_APP.containsMatchIn(name)

    /**
     * Returns the app name to look up if [text] is a short, clean "open/launch/start <app>" command, otherwise null.
     * v2.12.1 - anything else that merely starts with "open" ("open a website for me", "open the link above", "start over",
     * "open this idea and ...", a URL ...) now goes on to the AI / the website opener instead of being treated as an app name.
     */
    fun extractAppNameFromCommand(text: String): String? {
        val trimmed = text.trim().trimEnd('.', '!')
        val lower = trimmed.lowercase()
        if (lower.contains("http") || lower.contains("www.") || lower.contains("?") || lower.contains("\n")) return null
        for (prefix in PREFIXES) {
            if (lower.startsWith(prefix)) {
                var name = trimmed.substring(prefix.length).trim()
                name = name.replace(Regex("^(?:please\\s+)", RegexOption.IGNORE_CASE), "")
                name = LEADING.replace(name, "").trim()
                name = name.replace(Regex("\\s+(?:app|application)$", RegexOption.IGNORE_CASE), "").trim()
                if (name.isBlank()) return null
                // "open spotify and play something" keeps working: only the part before and/then/& has to look like an app name
                val first = Regex("\\s+(?:and|then|&)\\s+", RegexOption.IGNORE_CASE).split(name, 2)[0].trim()
                if (first.isBlank() || first.split(Regex("\\s+")).size > 3 || first.length > 32) return null
                if (first.any { it in ",;:()[]{}\"" }) return null
                return name
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
