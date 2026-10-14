package com.godavin.vince

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** Settings card: real-time location and the 48-hour / 7-day place memory. */
@Composable
fun LocationSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tracking by remember { mutableStateOf(LocationStore.trackingEnabled(context)) }
    var hours by remember { mutableStateOf(LocationStore.retentionHours(context)) }
    var count by remember { mutableStateOf(LocationStore.all(context).size) }
    var perm by remember { mutableStateOf(LocationTracker.hasPermission(context)) }
    var bg by remember { mutableStateOf(LocationTracker.hasBackground(context)) }
    var nowText by remember { mutableStateOf<String?>(null) }
    var gps by remember { mutableStateOf(LocationStore.gpsEnabled(context)) }
    var showPlaces by remember { mutableStateOf(false) }

    val bgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        bg = LocationTracker.hasBackground(context)
    }
    val fgLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        perm = LocationTracker.hasPermission(context)
        if (perm) {
            if (tracking) LocationTracker.setTracking(context, true)
            if (Build.VERSION.SDK_INT >= 29 && !LocationTracker.hasBackground(context)) {
                bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
            }
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Location & places", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "VINCE can read your real-time location (for \"where am I\", \"near me\", directions and distances) and " +
                "remember the places you've been for a limited time. It's stored only on this phone, never in backups, and " +
                "anything older than the window you pick is erased automatically.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(10.dp))

        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Remember places I go", fontSize = 14.sp)
                Text(
                    if (!perm) "Location permission needed - turn this on to grant it."
                    else if (!bg) "Allowed while the app is open. For all-day memory choose \"Allow all the time\"."
                    else "Location allowed all the time.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Switch(checked = tracking, onCheckedChange = { on ->
                tracking = on
                LocationStore.setTrackingFlag(context, on)
                if (on) {
                    if (!perm) {
                        fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                    } else {
                        LocationTracker.setTracking(context, true)
                        if (Build.VERSION.SDK_INT >= 29 && !bg) bgLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                } else {
                    LocationTracker.setTracking(context, false)
                }
            })
        }

        // v2.7 - exact (GPS) places, optional because it uses more battery
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Exact places (GPS)", fontSize = 14.sp)
                Text(
                    "Also uses GPS for the place memory: tighter positions, a little more battery. \"Where am I\" always uses GPS either way.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Switch(checked = gps, onCheckedChange = { on ->
                gps = on
                LocationStore.setGpsEnabled(context, on)
                if (LocationStore.trackingEnabled(context) && perm) LocationTracker.setTracking(context, true)
            })
        }

        Text("Keep places for:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp))
        ButtonGrid(
            BtnSpec("48 hours", primary = hours == 48) {
                hours = 48; LocationStore.setRetentionHours(context, 48); count = LocationStore.all(context).size
            },
            BtnSpec("7 days", primary = hours == 168) {
                hours = 168; LocationStore.setRetentionHours(context, 168); count = LocationStore.all(context).size
            },
            perRow = 2
        )

        Spacer(modifier = Modifier.height(10.dp))
        Text("$count places saved right now.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        nowText?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp)) }

        Spacer(modifier = Modifier.height(10.dp))
        ButtonGrid(
            BtnSpec("Where am I now?") {
                if (!perm) {
                    fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    nowText = "Getting an exact fix..."
                    scope.launch {
                        nowText = LocationTools.exactReply(context)
                        count = LocationStore.all(context).size
                    }
                }
            },
            BtnSpec(if (showPlaces) "Hide my places" else "Show my places") {
                showPlaces = !showPlaces
                count = LocationStore.all(context).size
            },
            BtnSpec("Clear history now") {
                LocationStore.clear(context); count = 0; showPlaces = false
            },
            BtnSpec("Refresh") {
                perm = LocationTracker.hasPermission(context)
                bg = LocationTracker.hasBackground(context)
                count = LocationStore.all(context).size
            },
            perRow = 2
        )

        if (showPlaces) {
            Spacer(modifier = Modifier.height(10.dp))
            val list = remember(count, hours, showPlaces) { LocationTools.placesListText(context) }
            Text(
                if (list.isBlank()) "No places recorded yet. They appear here as you move around while place memory is on." else list,
                fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f)
            )
        }
    }
}
