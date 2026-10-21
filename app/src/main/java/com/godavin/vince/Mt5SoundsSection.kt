package com.godavin.vince

import android.content.Intent
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Settings card: custom sounds for MetaTrader 5 mobile (button press, fills, TP, SL, closes, rejections). */
@Composable
fun Mt5SoundsSection() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var message by remember { mutableStateOf("") }
    var pickFor by remember { mutableStateOf<Mt5Sounds.Ev?>(null) }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val ev = pickFor
        if (uri != null && ev != null) {
            val err = Mt5Sounds.setCustom(context, ev, uri)
            message = err ?: "Saved a custom sound for: ${ev.label}."
            tick++
        }
        pickFor = null
    }

    // reading `tick` makes these refresh after returning from a Settings screen
    @Suppress("UNUSED_VARIABLE") val refresh = tick
    var master by remember { mutableStateOf(Mt5Sounds.enabled(context)) }
    val notifOn = VinceNotificationListener.isEnabled(context)
    val accOn = VinceAccessibilityService.isEnabled(context)

    Text("MT5 trade sounds", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(modifier = Modifier.height(4.dp))
    Text(
        "Plays your own sounds when you press Buy / Sell in MetaTrader 5 on this phone, and when orders fill, " +
            "TP or SL hit, positions close or an order is rejected. Only MT5 is watched.",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
    )
    Spacer(modifier = Modifier.height(10.dp))

    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Trade sounds on", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = master, onCheckedChange = { master = it; Mt5Sounds.setEnabled(context, it) })
    }
    Spacer(modifier = Modifier.height(8.dp))

    var debugOn by remember { mutableStateOf(Mt5Sounds.debug(context)) }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Debug: log every tap in MT5", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface)
        Switch(checked = debugOn, onCheckedChange = { debugOn = it; Mt5Sounds.setDebug(context, it) })
    }
    val clickAgo = (System.currentTimeMillis() - Mt5Sounds.lastClickAt) / 1000
    Text(
        "Last tap Android reported to VINCE (any app): " +
            if (Mt5Sounds.lastClickAt == 0L) "none yet. If you've tapped things and this stays empty, switch the VINCE accessibility service off and on again."
            else "${Mt5Sounds.lastClickPkg}, ${clickAgo}s ago",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
    )
    Spacer(modifier = Modifier.height(8.dp))

    Text(
        "Notification access (fills, TP, SL, closes): " + if (notifOn) "ON" else "OFF - turn it on below",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    )
    Text(
        "Phone control / accessibility (Buy-Sell button sound, instant fill sound): " + if (accOn) "ON" else "OFF - turn it on below",
        fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f)
    )
    Spacer(modifier = Modifier.height(8.dp))
    ButtonGrid(
        BtnSpec("Notification access") {
            try { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        },
        BtnSpec("Accessibility") {
            try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (e: Exception) { }
        },
        perRow = 2
    )
    Spacer(modifier = Modifier.height(6.dp))
    Text(
        "In MT5 itself: Settings > Notifications, allow trade notifications, and mute MT5's own order sound so they don't overlap. " +
            "Set VINCE's battery use to Unrestricted so Android doesn't stop it.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )

    Mt5Sounds.Ev.values().forEach { ev ->
        Spacer(modifier = Modifier.height(14.dp))
        var on by remember(ev, tick) { mutableStateOf(Mt5Sounds.evEnabled(context, ev)) }
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                Text(ev.label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    Mt5Sounds.customName(context, ev)?.let { "Your sound: $it" } ?: "Built-in tone",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Switch(checked = on, onCheckedChange = { on = it; Mt5Sounds.setEvEnabled(context, ev, it) })
        }
        Spacer(modifier = Modifier.height(6.dp))
        ButtonGrid(
            BtnSpec("Test") { Mt5Sounds.play(context, ev, force = true) },
            BtnSpec("Choose sound") { pickFor = ev; picker.launch(arrayOf("audio/*")) },
            BtnSpec("Default", enabled = Mt5Sounds.customName(context, ev) != null) {
                Mt5Sounds.clearCustom(context, ev); tick++
            },
            perRow = 3
        )
    }

    if (message.isNotBlank()) {
        Spacer(modifier = Modifier.height(10.dp))
        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }

    Spacer(modifier = Modifier.height(16.dp))
    Text("Recent MT5 activity seen", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
    Text(
        "What VINCE read from MT5 and how it decided. If a sound is wrong or missing, this shows MT5's real wording so the matching can be tuned.",
        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
    )
    Spacer(modifier = Modifier.height(6.dp))
    val lines = Mt5Sounds.logLines(context)
    if (lines.isEmpty()) {
        Text("Nothing yet.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
    } else {
        lines.forEach { Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f), modifier = Modifier.padding(vertical = 2.dp)) }
    }
    Spacer(modifier = Modifier.height(8.dp))
    ButtonGrid(
        BtnSpec("Refresh") {
            tick++
            val stamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            message = "Refreshed at $stamp: ${Mt5Sounds.logLines(context).size} log entries."
        },
        BtnSpec("Clear log", enabled = lines.isNotEmpty()) { Mt5Sounds.clearLog(context); tick++ },
        perRow = 2
    )
}
