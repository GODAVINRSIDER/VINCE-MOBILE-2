package com.godavin.vince

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import java.io.RandomAccessFile

/**
 * Dashboard visual pass - real Android system data only. Every value
 * here comes from an actual system API read at call time; nothing is a
 * placeholder or a plausible-looking made-up number. Where Android
 * genuinely can't give a reliable answer (per-app CPU% is restricted
 * since Android 8, and total-device CPU load requires reading /proc/stat
 * which some OEMs/security patches block for non-system apps), this
 * returns null rather than a guessed figure - the Dashboard shows "N/A"
 * in that case instead of pretending to know.
 */
object SystemStats {

    data class Snapshot(
        val ramUsedPercent: Int,
        val batteryPercent: Int,
        val isCharging: Boolean,
        val networkLabel: String,
        val cpuLoadPercent: Int?
    )

    fun read(context: Context): Snapshot {
        return Snapshot(
            ramUsedPercent = readRamUsedPercent(context),
            batteryPercent = readBatteryPercent(context),
            isCharging = readIsCharging(context),
            networkLabel = readNetworkLabel(context),
            cpuLoadPercent = readCpuLoadPercent()
        )
    }

    private fun readRamUsedPercent(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        if (info.totalMem <= 0) return 0
        val usedMem = info.totalMem - info.availMem
        return ((usedMem * 100) / info.totalMem).toInt().coerceIn(0, 100)
    }

    private fun readBatteryPercent(context: Context): Int {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        if (level < 0 || scale <= 0) return 0
        return ((level * 100) / scale).coerceIn(0, 100)
    }

    private fun readIsCharging(context: Context): Boolean {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        val batteryStatus = context.registerReceiver(null, filter)
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun readNetworkLabel(context: Context): String {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return "Unknown"
        val network = cm.activeNetwork ?: return "Offline"
        val caps = cm.getNetworkCapabilities(network) ?: return "Offline"
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile data"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Connected"
        }
    }

    /** Best-effort total-device CPU load from /proc/stat, sampled twice
     * ~200ms apart (a single /proc/stat read is a cumulative counter, not
     * a percentage - you need the delta between two reads to get a real
     * load number). Returns null if the file can't be read at all, which
     * happens on some OEM builds/security patches - shown as "N/A" rather
     * than guessed. */
    private fun readCpuLoadPercent(): Int? {
        fun readStatLine(): LongArray? {
            return try {
                RandomAccessFile("/proc/stat", "r").use { reader ->
                    val line = reader.readLine() ?: return null
                    val parts = line.trim().split(Regex("\\s+")).drop(1).map { it.toLong() }
                    if (parts.size < 4) null else parts.toLongArray()
                }
            } catch (e: Exception) {
                null
            }
        }

        val first = readStatLine() ?: return null
        Thread.sleep(200)
        val second = readStatLine() ?: return null

        val idleFirst = first[3]
        val idleSecond = second[3]
        val totalFirst = first.sum()
        val totalSecond = second.sum()

        val totalDelta = totalSecond - totalFirst
        val idleDelta = idleSecond - idleFirst
        if (totalDelta <= 0) return null

        return (((totalDelta - idleDelta) * 100) / totalDelta).toInt().coerceIn(0, 100)
    }
}
