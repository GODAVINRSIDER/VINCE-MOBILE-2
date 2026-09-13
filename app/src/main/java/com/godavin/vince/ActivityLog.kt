package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Dashboard visual pass - a real, on-device log of things VINCE actually
 * did (a message sent to a persona, a photo/screen analyzed, an app
 * opened), so the Activity panel shows genuine recent activity instead
 * of a decorative placeholder list. Capped at MAX_ENTRIES, oldest
 * dropped first - this is a rolling recent-activity feed, not a
 * permanent audit log.
 */
object ActivityLog {
    private const val FILE_NAME = "vince_activity_log.json"
    private const val MAX_ENTRIES = 50

    data class Entry(val label: String, val timestamp: Long)

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): MutableList<Entry> {
        val f = file(context)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val list = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(Entry(obj.getString("label"), obj.getLong("timestamp")))
            }
            list
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun persist(context: Context, list: List<Entry>) {
        val arr = JSONArray()
        for (entry in list) {
            arr.put(JSONObject().apply {
                put("label", entry.label)
                put("timestamp", entry.timestamp)
            })
        }
        try {
            file(context).writeText(arr.toString())
        } catch (e: Exception) {
            // best-effort - a failed save shouldn't break whatever triggered it
        }
    }

    fun addEvent(context: Context, label: String) {
        val list = load(context)
        list.add(0, Entry(label, System.currentTimeMillis()))
        while (list.size > MAX_ENTRIES) list.removeAt(list.size - 1)
        persist(context, list)
    }

    /** Most recent first. */
    fun getRecent(context: Context, limit: Int = 4): List<Entry> {
        return load(context).take(limit)
    }

    fun getAll(context: Context): List<Entry> {
        return load(context)
    }

    /** "3m ago" / "2h ago" / "5d ago" style relative label, real elapsed
     * time from the entry's actual timestamp - never a made-up figure. */
    fun relativeLabel(timestamp: Long): String {
        val elapsedMs = System.currentTimeMillis() - timestamp
        val minutes = elapsedMs / 60_000
        val hours = minutes / 60
        val days = hours / 24
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "${minutes}m ago"
            hours < 24 -> "${hours}h ago"
            else -> "${days}d ago"
        }
    }
}
