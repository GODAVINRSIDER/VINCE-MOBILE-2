package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import org.json.JSONObject
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

    /** Restores from a previously exported backup file - OVERWRITES
     * current on-device data with what's in the backup. That's the
     * correct behavior for the "just reinstalled / new phone" case this
     * exists for, since there's nothing meaningful to merge with on a
     * fresh install. Returns true if the file parsed and restored. */
    fun restoreFromUri(context: Context, uri: Uri): Boolean {
        return try {
            val text = context.contentResolver.openInputStream(uri)
                ?.use { it.readBytes().toString(Charsets.UTF_8) }
                ?: return false
            val envelope = JSONObject(text)
            writeRaw(context, CONVERSATIONS_FILE, envelope.getString("conversations"))
            writeRaw(context, MEMORY_FILE, envelope.getString("structured_memory"))
            if (envelope.has("activity_log")) {
                writeRaw(context, ACTIVITY_FILE, envelope.getString("activity_log"))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
