package com.godavin.vince


import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** Settings card for the proactive persona check-ins (see ProactiveEngine). */
@Composable
fun ProactiveSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var master by remember { mutableStateOf(ProactiveEngine.masterOn(context)) }
    var morningOn by remember { mutableStateOf(ProactiveEngine.slotOn(context, ProactiveEngine.Slot.MORNING)) }
    var middayOn by remember { mutableStateOf(ProactiveEngine.slotOn(context, ProactiveEngine.Slot.MIDDAY)) }
    var eveningOn by remember { mutableStateOf(ProactiveEngine.slotOn(context, ProactiveEngine.Slot.EVENING)) }
    var morningMin by remember { mutableStateOf(ProactiveEngine.minutes(context, ProactiveEngine.Slot.MORNING)) }
    var middayMin by remember { mutableStateOf(ProactiveEngine.minutes(context, ProactiveEngine.Slot.MIDDAY)) }
    var eveningMin by remember { mutableStateOf(ProactiveEngine.minutes(context, ProactiveEngine.Slot.EVENING)) }
    var quietStart by remember { mutableStateOf(ProactiveEngine.quietStart(context)) }
    var quietEnd by remember { mutableStateOf(ProactiveEngine.quietEnd(context)) }
    var note by remember { mutableStateOf<String?>(null) }

    fun rearm() = ProactiveEngine.scheduleAll(context)

    Column(modifier = Modifier.fillMaxWidth()) {
        Text("Proactive check-ins", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            "VINCE, DAVINA and CLARA reach out on their own, like JARVIS: one short opinion or idea and an easy question, " +
                "not only about trading (your projects, learning, brand, health, life). At most 3 a day, never during " +
                "quiet hours, and they open in the Check-ins chat.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.65f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        SwitchRow("Proactive check-ins", "Master switch", master) {
            master = it; AlertPrefs.setOn(context, ProactiveEngine.K_MASTER, it); rearm()
        }

        if (master) {
            SlotRow("Morning - VINCE (focus for today)", morningOn, morningMin,
                onToggle = { morningOn = it; AlertPrefs.setOn(context, ProactiveEngine.Slot.MORNING.prefKey, it); rearm() },
                onTime = { morningMin = it; AlertPrefs.setInt(context, ProactiveEngine.Slot.MORNING.minKey, it); rearm() })
            SlotRow("Midday - DAVINA (an idea or a better way)", middayOn, middayMin,
                onToggle = { middayOn = it; AlertPrefs.setOn(context, ProactiveEngine.Slot.MIDDAY.prefKey, it); rearm() },
                onTime = { middayMin = it; AlertPrefs.setInt(context, ProactiveEngine.Slot.MIDDAY.minKey, it); rearm() })
            SlotRow("Evening - CLARA (how the day went)", eveningOn, eveningMin,
                onToggle = { eveningOn = it; AlertPrefs.setOn(context, ProactiveEngine.Slot.EVENING.prefKey, it); rearm() },
                onTime = { eveningMin = it; AlertPrefs.setInt(context, ProactiveEngine.Slot.EVENING.minKey, it); rearm() })

            Spacer(modifier = Modifier.height(8.dp))
            ButtonGrid(
                BtnSpec("Quiet from ${AlertPrefs.formatMinutes(quietStart)} (tap +1h)") {
                    quietStart = (quietStart + 60) % 1440
                    AlertPrefs.setInt(context, ProactiveEngine.K_QUIET_START, quietStart)
                },
                BtnSpec("Quiet until ${AlertPrefs.formatMinutes(quietEnd)} (tap +1h)") {
                    quietEnd = (quietEnd + 60) % 1440
                    AlertPrefs.setInt(context, ProactiveEngine.K_QUIET_END, quietEnd)
                },
                perRow = 2
            )
            Spacer(modifier = Modifier.height(8.dp))
            VButton(
                text = "Send me one now (test)",
                onClick = {
                    note = "Writing one..."
                    scope.launch {
                        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
                        val slot = when {
                            hour < 12 -> ProactiveEngine.Slot.MORNING
                            hour < 17 -> ProactiveEngine.Slot.MIDDAY
                            else -> ProactiveEngine.Slot.EVENING
                        }
                        val text = ProactiveEngine.fire(context, slot, force = true, timeoutMs = 25_000L)
                        note = if (text == null) "Couldn't write one right now - no AI provider answered. Check your keys and connection."
                        else "Sent as ${slot.persona.displayName}. Open the notification or the Check-ins chat."
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
        note?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 6.dp)) }
    }
}

@Composable
private fun SwitchRow(label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(label, fontSize = 14.sp)
            if (hint != null) Text(hint, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f))
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SlotRow(label: String, on: Boolean, minutes: Int, onToggle: (Boolean) -> Unit, onTime: (Int) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Switch(checked = on, onCheckedChange = onToggle)
        }
        if (on) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("At ${AlertPrefs.formatMinutes(minutes)}", fontSize = 12.sp, modifier = Modifier.weight(1f))
                VButton(text = "-30m", onClick = { onTime((minutes - 30 + 1440) % 1440) }, modifier = Modifier.width(72.dp))
                Spacer(modifier = Modifier.width(6.dp))
                VButton(text = "+30m", onClick = { onTime((minutes + 30) % 1440) }, modifier = Modifier.width(72.dp))
            }
        }
    }
}
