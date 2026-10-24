package com.godavin.vince

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v2.11 - VINCE's own log file (vince_log.txt in the app's private storage).
 * Records crashes (any thread) and the things that quietly went wrong - providers that failed,
 * searches that came back empty - so "diagnose yourself" has something real to read.
 * Never throws, never blocks the chat, keeps itself small (rotates at ~300 KB).
 */
object VinceLog {
    private const val FILE_NAME = "vince_log.txt"
    private const val MAX_BYTES = 300_000L
    private val lock = Any()
    @Volatile private var dir: File? = null
    @Volatile private var handlerInstalled = false

    private fun stamp(): String = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())

    /** Safe to call from anywhere, any number of times. First call also installs the crash recorder. */
    fun ensure(context: Context) {
        if (dir == null) dir = context.applicationContext.filesDir
        if (!handlerInstalled) {
            handlerInstalled = true
            val version = try {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
            } catch (e: Exception) {
                "?"
            }
            write("INFO  ===== VINCE Mobile $version started =====")
            val previous = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { t, e ->
                try {
                    write("ERROR CRASH in thread ${t.name}: ${Log.getStackTraceString(e)}")
                } catch (ignored: Throwable) {
                }
                previous?.uncaughtException(t, e)
            }
        }
    }

    fun e(context: Context, tag: String, msg: String) {
        ensure(context)
        write("ERROR [$tag] $msg")
    }

    fun w(context: Context, tag: String, msg: String) {
        ensure(context)
        write("WARN  [$tag] $msg")
    }

    fun i(context: Context, tag: String, msg: String) {
        ensure(context)
        write("INFO  [$tag] $msg")
    }

    private fun write(line: String) {
        val d = dir ?: return
        try {
            synchronized(lock) {
                val f = File(d, FILE_NAME)
                if (f.exists() && f.length() > MAX_BYTES) {
                    val old = File(d, "$FILE_NAME.1")
                    if (old.exists()) old.delete()
                    f.renameTo(old)
                }
                File(d, FILE_NAME).appendText("${stamp()}  $line\n")
            }
        } catch (ignored: Throwable) {
        }
    }

    fun sizeBytes(context: Context): Long = try {
        File(context.filesDir, FILE_NAME).length()
    } catch (e: Exception) {
        0L
    }

    /** The newest lines of the log (about the last 200 KB). */
    fun tail(context: Context, maxBytes: Long = 200_000L): List<String> {
        return try {
            val f = File(context.filesDir, FILE_NAME)
            if (!f.exists()) return emptyList()
            val text = f.readText()
            val cut = if (text.length > maxBytes) text.substring(text.length - maxBytes.toInt()) else text
            cut.lines().filter { it.isNotBlank() }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
