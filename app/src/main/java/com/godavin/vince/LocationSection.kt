package com.godavin.vince

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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

        Text("Keep places for:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pad = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            if (hours == 48) Button(onClick = {}, contentPadding = pad) { Text("48 hours", fontSize = 12.sp) }
            else OutlinedButton(onClick = {
                hours = 48; LocationStore.setRetentionHours(context, 48); count = LocationStore.all(context).size
            }, contentPadding = pad) { Text("48 hours", fontSize = 12.sp) }
            if (hours == 168) Button(onClick = {}, contentPadding = pad) { Text("7 days", fontSize = 12.sp) }
            else OutlinedButton(onClick = {
                hours = 168; LocationStore.setRetentionHours(context, 168); count = LocationStore.all(context).size
            }, contentPadding = pad) { Text("7 days", fontSize = 12.sp) }
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text("$count places saved right now.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f))
        nowText?.let { Text("Now: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary) }

        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val pad = PaddingValues(horizontal = 12.dp, vertical = 2.dp)
            OutlinedButton(onClick = {
                if (!perm) {
                    fgLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
                } else {
                    nowText = "checking..."
                    scope.launch {
                        nowText = LocationTracker.current(context)?.name ?: "no location fix (is GPS on?)"
                        count = LocationStore.all(context).size
                    }
                }
            }, contentPadding = pad) { Text("Where am I now?", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                LocationStore.clear(context); count = 0
            }, contentPadding = pad) { Text("Clear history now", fontSize = 12.sp) }
            OutlinedButton(onClick = {
                perm = LocationTracker.hasPermission(context)
                bg = LocationTracker.hasBackground(context)
                count = LocationStore.all(context).size
            }, contentPadding = pad) { Text("Refresh", fontSize = 12.sp) }
        }
    }
}
