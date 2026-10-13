package com.godavin.vince

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/** One remembered place: when, where, and a readable name. */
data class PlacePoint(val t: Long, val lat: Double, val lng: Double, val name: String)

/**
 * v2.6 - the on-device record of places you have been. Stored only on this phone
 * (not in backups), and anything older than the retention window (48 hours by
 * default, or 7 days if you choose) is erased every time something is added or read.
 */
object LocationStore {
    private const val FILE = "vince_places.json"
    private const val PREFS = "vince_location_prefs"
    private val lock = Any()

    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun retentionHours(c: Context): Int = prefs(c).getInt("retention_h", 48)

    fun setRetentionHours(c: Context, h: Int) {
        prefs(c).edit().putInt("retention_h", h).apply()
        prune(c)
    }

    fun trackingEnabled(c: Context): Boolean = prefs(c).getBoolean("tracking", false)

    fun setTrackingFlag(c: Context, on: Boolean) = prefs(c).edit().putBoolean("tracking", on).apply()

    private fun file(c: Context) = File(c.filesDir, FILE)

    private fun readAll(c: Context): MutableList<PlacePoint> {
        val f = file(c)
        if (!f.exists()) return mutableListOf()
        return try {
            val arr = JSONArray(f.readText())
            val out = mutableListOf<PlacePoint>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                out.add(PlacePoint(o.getLong("t"), o.getDouble("lat"), o.getDouble("lng"), o.optString("name")))
            }
            out
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    private fun writeAll(c: Context, list: List<PlacePoint>) {
        val arr = JSONArray()
        for (p in list) {
            arr.put(JSONObject().put("t", p.t).put("lat", p.lat).put("lng", p.lng).put("name", p.name))
        }
        file(c).writeText(arr.toString())
    }

    private fun cutoff(c: Context) = System.currentTimeMillis() - retentionHours(c) * 3_600_000L

    fun prune(c: Context) = synchronized(lock) {
        val keep = readAll(c).filter { it.t >= cutoff(c) }
        writeAll(c, keep)
    }

    fun add(c: Context, p: PlacePoint) = synchronized(lock) {
        val list = readAll(c).filter { it.t >= cutoff(c) }.toMutableList()
        val last = list.lastOrNull()
        // skip near-duplicates: same spot (about 80 m) within 20 minutes
        if (last != null && p.t - last.t < 20 * 60_000L && distanceM(last.lat, last.lng, p.lat, p.lng) < 80) {
            return@synchronized
        }
        list.add(p)
        writeAll(c, list)
    }

    /** Oldest first, already pruned. */
    fun all(c: Context): List<PlacePoint> = synchronized(lock) {
        val list = readAll(c).filter { it.t >= cutoff(c) }.sortedBy { it.t }
        list
    }

    fun clear(c: Context) = synchronized(lock) { file(c).delete(); Unit }

    fun distanceM(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLng, bLat, bLng, r)
        return r[0]
    }
}

/** Receives the background location updates registered by [LocationTracker]. */
class LocationReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!LocationStore.trackingEnabled(context)) return
        @Suppress("DEPRECATION")
        val loc = intent.extras?.get(LocationManager.KEY_LOCATION_CHANGED) as? Location ?: return
        val pending = goAsync()
        Thread {
            try {
                val name = LocationTracker.reverseName(context, loc.latitude, loc.longitude)
                LocationStore.add(context, PlacePoint(System.currentTimeMillis(), loc.latitude, loc.longitude, name))
            } catch (e: Exception) {
            } finally {
                pending.finish()
            }
        }.start()
    }
}

object LocationTracker {
    private const val REQ_CODE = 7421

    fun hasPermission(c: Context): Boolean =
        c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasBackground(c: Context): Boolean =
        Build.VERSION.SDK_INT < 29 ||
            c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun lm(c: Context) = c.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun pendingIntent(c: Context): PendingIntent {
        val i = Intent(c, LocationReceiver::class.java).setAction("com.godavin.vince.LOCATION_UPDATE")
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
            (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
        return PendingIntent.getBroadcast(c, REQ_CODE, i, flags)
    }

    /** Turn the 48h/7-day place memory on or off. Needs location permission first. */
    @SuppressLint("MissingPermission")
    fun setTracking(c: Context, on: Boolean) {
        LocationStore.setTrackingFlag(c, on)
        try {
            val manager = lm(c)
            val pi = pendingIntent(c)
            manager.removeUpdates(pi)
            if (on && hasPermission(c)) {
                // network = low battery; passive = free fixes whenever any app gets one
                if (manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    manager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 10 * 60_000L, 150f, pi)
                }
                manager.requestLocationUpdates(LocationManager.PASSIVE_PROVIDER, 5 * 60_000L, 100f, pi)
            }
            if (!on) LocationStore.prune(c)
        } catch (e: Exception) {
        }
    }

    /** Re-arm after a reboot or an app update (registrations do not survive those). */
    fun restore(c: Context) {
        if (LocationStore.trackingEnabled(c) && hasPermission(c)) setTracking(c, true)
        else LocationStore.prune(c)
    }

    @SuppressLint("MissingPermission")
    private fun bestLastKnown(c: Context): Location? {
        val manager = lm(c)
        var best: Location? = null
        for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { manager.getLastKnownLocation(p) } catch (e: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private suspend fun freshFix(c: Context): Location? {
        val manager = lm(c)
        val provider = when {
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            else -> return null
        }
        return withTimeoutOrNull(5000) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    manager.getCurrentLocation(provider, null, { it.run() }) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    manager.requestSingleUpdate(provider, object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            if (cont.isActive) cont.resume(location)
                        }
                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(p: String?, s: Int, e: Bundle?) {}
                        override fun onProviderEnabled(p: String) {}
                        override fun onProviderDisabled(p: String) {}
                    }, Looper.getMainLooper())
                }
            }
        }
    }

    /** Where the phone is right now (uses a fix under 2 minutes old if there is one). */
    suspend fun current(c: Context): PlacePoint? = withContext(Dispatchers.IO) {
        if (!hasPermission(c)) return@withContext null
        val now = System.currentTimeMillis()
        val last = bestLastKnown(c)
        var loc: Location? = last?.takeIf { now - it.time < 120_000L }
        if (loc == null) loc = freshFix(c)
        if (loc == null) loc = last
        loc?.let { PlacePoint(now, it.latitude, it.longitude, reverseName(c, it.latitude, it.longitude)) }
    }

    /** "Westlands, Nairobi" style name, or plain coordinates if no geocoder is available. */
    fun reverseName(c: Context, lat: Double, lng: Double): String {
        val fallback = String.format(Locale.US, "%.4f, %.4f", lat, lng)
        return try {
            if (!Geocoder.isPresent()) return fallback
            @Suppress("DEPRECATION")
            val list: List<Address>? = Geocoder(c, Locale.getDefault()).getFromLocation(lat, lng, 1)
            val a = list?.firstOrNull() ?: return fallback
            val feature = a.featureName?.takeIf { it.isNotBlank() && !it.all { ch -> ch.isDigit() || ch == '-' } }
            val parts = listOfNotNull(feature, a.subLocality ?: a.thoroughfare, a.locality ?: a.subAdminArea)
                .distinct()
            if (parts.isEmpty()) fallback else parts.joinToString(", ")
        } catch (e: Exception) {
            fallback
        }
    }

    /** Last part of a place name, e.g. the city, for "near me" searches. */
    fun areaOf(p: PlacePoint): String = p.name.substringAfterLast(", ").trim()
}

/** Chat/voice commands and prompt context for location. */
object LocationTools {
    private val WANTS = Regex(
        "where am i|my location|near me|nearby|around here|closest|nearest|where have i been|" +
            "where was i|where did i go|places i('ve| have)? (been|visited|went)|been today|" +
            "how far|distance (to|from)|directions|how long to get|am i near"
    )
    private val NEARBY = Regex("near me|nearby|around here|closest|nearest")
    private val NAV = Regex("^(?:please\\s+)?(?:navigate|directions?|route|take me|drive me|guide me|walk me)\\s+(?:to|me to)\\s+(.+)$")
    private val WHERE = Regex("^(?:please\\s+)?(?:where am i|what'?s my location|what is my location|where am i right now|my location)[?.! ]*$")

    fun wantsLocation(msg: String): Boolean = WANTS.containsMatchIn(msg.lowercase())
    fun wantsNearby(msg: String): Boolean = NEARBY.containsMatchIn(msg.lowercase())
    private fun wantsHistory(m: String) =
        Regex("where have i been|where was i|where did i go|places i|been today|visited").containsMatchIn(m.lowercase())

    /** Instant answers that never need the AI: "where am I" and "navigate to X". */
    suspend fun handleLocalCommand(context: Context, text: String): String? {
        val lower = text.lowercase().trim()
        NAV.find(lower)?.let { m ->
            val dest = m.groupValues[1].trim().trimEnd('.', '!', '?')
            if (dest.isNotBlank()) {
                return try {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest)))
                        .setPackage("com.google.android.apps.maps")
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(i)
                    "Opening Google Maps navigation to $dest."
                } catch (e: Exception) {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(dest)))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        "Opening a map for $dest."
                    } catch (e2: Exception) {
                        "I couldn't open a maps app on this phone."
                    }
                }
            }
        }
        if (WHERE.matches(lower)) {
            if (!LocationTracker.hasPermission(context)) {
                return "I don't have location permission yet. Turn it on in VINCE Settings > Location."
            }
            val p = LocationTracker.current(context)
                ?: return "I couldn't get a location fix right now. Check that GPS or location is switched on."
            return "You're at ${p.name}."
        }
        return null
    }

    /** Context for the AI when the message is location-related. Returns (block, area). */
    suspend fun promptBlock(context: Context, userMessage: String): Pair<String, String?> {
        if (!LocationTracker.hasPermission(context)) {
            return Pair(
                "The user asked something location-related but VINCE has no location permission. " +
                    "Say so in one short phrase and tell them to allow it in VINCE Settings > Location. " +
                    "Do not guess where they are.", null
            )
        }
        val now = LocationTracker.current(context)
            ?: return Pair(
                "The user asked something location-related but no location fix could be obtained " +
                    "(GPS may be off). Say so briefly. Do not guess where they are.", null
            )
        val sb = StringBuilder()
        val acc = SimpleDateFormat("HH:mm", Locale.getDefault())
        sb.append("REAL-TIME LOCATION, read from the phone seconds ago: ${now.name} ")
            .append(String.format(Locale.US, "(%.5f, %.5f). ", now.lat, now.lng))
            .append("Use it for where-am-I, near-me, distance and direction questions. ")
        if (wantsHistory(userMessage)) {
            val hist = LocationStore.all(context)
            if (hist.isEmpty()) {
                sb.append("PLACES VISITED: nothing recorded yet (the place memory is off or has just started). ")
            } else {
                sb.append("PLACES VISITED in the last ${LocationStore.retentionHours(context)} hours, oldest to newest: ")
                var lastName = ""
                var n = 0
                for (p in hist) {
                    if (p.name == lastName) continue
                    lastName = p.name
                    if (n++ >= 25) break
                    sb.append("${acc.format(Date(p.t))} ${p.name}; ")
                }
            }
        }
        return Pair(sb.toString(), LocationTracker.areaOf(now).ifBlank { null })
    }
}
