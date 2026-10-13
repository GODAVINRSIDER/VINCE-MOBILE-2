package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File

/**
 * Full data backup/restore. Vincent wanted a way to move to a new phone
 * or recover from a reinstall without starting fresh - every chat
 * thread, every structured memory fact, and the activity log, bundled
 * into one JSON file.
 *
 * WHY THIS SHAPE, NOT "a built-in email account": actually wiring up a
 * real email account (sending mail automatically on VINCE's own behalf)
 * needs either a mail-server integration or Gmail's OAuth API - a much
 * bigger, ongoing piece of infrastructure (API credentials, consent
 * screens, token refresh, and it'd need to keep working indefinitely).
 * What actually solves the stated problem - "don't lose everything if I
 * switch phones" - without any of that: export everything to one file,
 * then use Android's own share sheet to send it wherever Vincent wants
 * (his own email, WhatsApp to himself, Google Drive, a USB copy - his
 * choice, not locked to email specifically). Restoring on the new phone
 * is the same file, read back in. No servers, no ongoing cost, no
 * account to maintain - and it already works with whatever mail app is
 * on his phone today.
 */
object BackupManager {
    private const val CONVERSATIONS_FILE = "vince_conversations.json"
    private const val MEMORY_FILE = "vince_structured_memory.json"
    private const val ACTIVITY_FILE = "vince_activity_log.json"

    private fun readRaw(context: Context, fileName: String): String {
        val f = File(context.filesDir, fileName)
        return if (f.exists()) f.readText() else "[]"
    }

    private fun writeRaw(context: Context, fileName: String, content: String) {
        File(context.filesDir, fileName).writeText(content)
    }

    /** Everything VINCE actually stores, bundled into one JSON envelope. */
    fun buildBackupJson(context: Context): String {
        val envelope = JSONObject()
        envelope.put("backup_version", 1)
        envelope.put("created_at", System.currentTimeMillis())
        envelope.put("conversations", readRaw(context, CONVERSATIONS_FILE))
        envelope.put("structured_memory", readRaw(context, MEMORY_FILE))
        envelope.put("activity_log", readRaw(context, ACTIVITY_FILE))
        return envelope.toString(2)
    }

    /** Writes the backup to a shareable cache file and returns a content
     * Uri (via FileProvider) ready to hand to a share Intent. */
    private fun createBackupFile(context: Context): Uri {
        val dir = File(context.cacheDir, "backups").apply { mkdirs() }
        val file = File(dir, "vince_backup_${System.currentTimeMillis()}.json")
        file.writeText(buildBackupJson(context))
        return FileProvider.getUriForFile(context, "com.godavin.vince.fileprovider", file)
    }

    /** An ACTION_SEND intent with the backup file attached - hand this to
     * startActivity() to open the system share sheet (email, Drive,
     * WhatsApp, wherever). */
    fun shareBackupIntent(context: Context): Intent {
        val uri = createBackupFile(context)
        return Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "VINCE backup")
            putExtra(
                Intent.EXTRA_TEXT,
                "VINCE data backup - keep this file to restore your chats, memory, " +
                    "and activity on a new phone or after reinstalling."
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    /** What the last restore did (or why it failed) - shown on the Settings screen. */
    @Volatile var lastSummary: String = ""
    @Volatile var lastError: String = ""

    /** Restores from a previously exported backup file - OVERWRITES current on-device
     * data with what's in the backup. v2.6 fixes: (1) the in-memory chat cache is now
     * dropped after restoring - before, the app kept showing (and then re-saving) its
     * OLD chats right over the restored file, so a restore looked like it did nothing;
     * (2) the file is validated BEFORE anything is overwritten; (3) the current files
     * are kept as ".before-restore" copies; (4) the result says what was restored. */
    fun restoreFromUri(context: Context, uri: Uri): Boolean {
        lastSummary = ""
        lastError = ""
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
            if (text.isNullOrBlank()) {
                lastError = "that file is empty or can't be opened"
                return false
            }
            val envelope = JSONObject(text.trim().removePrefix("\uFEFF"))
            if (!envelope.has("conversations")) {
                lastError = "that isn't a VINCE backup file"
                return false
            }
            val conversations = envelope.getString("conversations")
            val threadCount = JSONArray(conversations).length()
            val memory = envelope.optString("structured_memory", "")
            if (memory.isNotBlank()) JSONTokener(memory).nextValue()   // must parse
            val activity = envelope.optString("activity_log", "")

            // keep what is there now, just in case
            for (name in listOf(CONVERSATIONS_FILE, MEMORY_FILE, ACTIVITY_FILE)) {
                val f = File(context.filesDir, name)
                if (f.exists()) f.copyTo(File(context.filesDir, "$name.before-restore"), overwrite = true)
            }

            writeRaw(context, CONVERSATIONS_FILE, conversations)
            if (memory.isNotBlank()) writeRaw(context, MEMORY_FILE, memory)
            if (activity.isNotBlank()) writeRaw(context, ACTIVITY_FILE, activity)

            // the real fix: forget the stale in-memory copy
            ConversationStore.reload()

            var messages = 0
            val arr = JSONArray(conversations)
            for (i in 0 until arr.length()) {
                messages += arr.getJSONObject(i).optJSONArray("messages")?.length() ?: 0
            }
            lastSummary = "$threadCount chats ($messages messages)" +
                if (memory.isNotBlank()) " and your memory" else ""
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            false
        }
    }
}
