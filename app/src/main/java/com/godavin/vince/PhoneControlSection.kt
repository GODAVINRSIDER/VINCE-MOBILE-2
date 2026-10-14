package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

/** Settings card: switch on phone control, notification access, and the MT5 trading rails. */
@Composable
fun PhoneControlSection() {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }      // bump to re-read statuses after returning from Settings
    var mt5On by remember { mutableStateOf(Mt5Trader.enabled(context)) }

    var guided by remember { mutableStateOf(false) }
    var lastDone by remember { mutableStateOf(0) }

    // v2.6 - re-check every status the moment you come back from a Settings screen
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) tick++
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    // guided mode: when a step gets done, open the next one automatically
    LaunchedEffect(tick) {
        if (guided) {
            val done = PhoneSetup.doneCount(context)
            if (done > lastDone) {
                lastDone = done
                if (done >= 3) guided = false
                PhoneSetup.openNextStep(context)
            }
        }
    }

    val accOn = tick >= 0 && VinceAccessibilityService.isEnabled(context)
    val accLive = VinceAccessibilityService.isRunning
    val notifOn = tick >= 0 && VinceNotificationListener.isEnabled(context)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Phone control", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "Lets VINCE read the screen and tap, type and scroll when you ask. Anything that sends, pays, posts, " +
                "deletes, buys or sells waits for your \"yes\". Say \"stop\", tap the widget, or press STOP in the " +
                "notification to halt it instantly.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(10.dp))

        val restricted = if (tick >= 0) PhoneSetup.restrictedUnlocked(context) else null
        StatusLine(
            "Step 1 - Restricted settings unlocked",
            when (restricted) { true -> "ON"; false -> "LOCKED"; null -> "unknown" }
        )
        StatusLine("Step 2 - Phone control (Accessibility)", if (accLive) "ON" else if (accOn) "On, starting..." else "OFF")
        StatusLine("Step 3 - Notification access", if (notifOn) "ON" else "OFF")
        VButton(
            text = "Set up phone control (guided)", primary = true,
            onClick = {
                guided = true
                lastDone = PhoneSetup.doneCount(context)
                PhoneSetup.openNextStep(context)
            },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Works on any phone, no laptop needed. VINCE opens each screen for you, tells you the exact taps, and moves to " +
                "the next step by itself when you come back.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp, bottom = 10.dp)
        )

        StatusLine("Phone control (Accessibility)", if (accLive) "ON" else if (accOn) "On, starting..." else "OFF")
        ButtonGrid(
            BtnSpec("1. Accessibility", primary = true) {
                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            },
            BtnSpec("App info") { openAppInfo(context) },
            BtnSpec("Refresh") { tick++ },
            perRow = 3
        )
        Text(
            "Steps: Accessibility > Installed apps (or Downloaded apps) > VINCE phone control > On. If Android says " +
                "\"restricted setting\", press App info > top-right menu (three dots) > Allow restricted settings, then try again.",
            fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
            modifier = Modifier.padding(top = 4.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))
        StatusLine("Notification access (read messages, reply)", if (notifOn) "ON" else "OFF")
        VButton(text = "2. Notification access", onClick = {
            context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, modifier = Modifier.fillMaxWidth())

        Spacer(modifier = Modifier.height(12.dp))
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text("MetaTrader 5 trade execution", fontSize = 14.sp)
                Text(
                    "Needs a full instruction + your yes. Max lot ${Mt5Trader.maxLot(context)} (say \"set mt5 max lot to 0.05\" to change). Use a DEMO account first.",
                    fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                )
            }
            Switch(checked = mt5On, onCheckedChange = {
                mt5On = it
                Mt5Trader.setEnabled(context, it)
            })
        }

        Spacer(modifier = Modifier.height(8.dp))
        VButton(text = "STOP phone control now", onClick = { PhoneAgent.stop(context) }, modifier = Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatusLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), fontSize = 13.sp)
        Text(
            value, fontSize = 13.sp, fontWeight = FontWeight.Bold,
            color = if (value.startsWith("ON")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )
    }
}

private fun openAppInfo(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    } catch (e: Exception) { }
}
