package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Reminder(val id: Int, val fireAtMillis: Long, val message: String)

/**
 * Persisted list of pending reminders - unlike PC-VINCE's in-memory
 * reminders.py (fine there since the PC process runs continuously),
 * VINCE Mobile's process can be killed by Android at any time, so
 * reminders need to survive that. The actual OS-level firing is handled
 * by ReminderScheduler/AlarmManager; this file just tracks what's
 * pending so it can be listed or cancelled later.
 */
object ReminderStore {
    private const val FILE_NAME = "vince_reminders.json"

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): JSONArray {
        val f = file(context)
        if (f.exists()) {
            try {
                return JSONArray(f.readText())
            } catch (e: Exception) {
                // corrupt file - start fresh
            }
        }
        return JSONArray()
    }

    private fun save(context: Context, data: JSONArray) {
        try {
            file(context).writeText(data.toString())
        } catch (e: Exception) {
            // best-effort
        }
    }

    fun getAll(context: Context): List<Reminder> {
        val arr = load(context)
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Reminder(o.getInt("id"), o.getLong("fireAtMillis"), o.getString("message"))
        }
    }

    /** Adds a new reminder and returns its generated id (used to tag the
     * AlarmManager PendingIntent so it can be cancelled individually). */
    fun add(context: Context, fireAtMillis: Long, message: String): Int {
        val arr = load(context)
        val id = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val obj = JSONObject().apply {
            put("id", id)
            put("fireAtMillis", fireAtMillis)
            put("message", message)
        }
        arr.put(obj)
        save(context, arr)
        return id
    }

    fun remove(context: Context, id: Int) {
        val arr = load(context)
        val rebuilt = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getInt("id") != id) rebuilt.put(o)
        }
        save(context, rebuilt)
    }
}
