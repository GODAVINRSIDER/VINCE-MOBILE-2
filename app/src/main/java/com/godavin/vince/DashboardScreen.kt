package com.godavin.vince

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.speech.RecognizerIntent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.min

/**
 * Dashboard visual pass - the actual locked reference layout ("VINCE 2.0
 * MOBILE - ONE CORE, THREE MINDS"), replacing the old plain button-list
 * Home screen. Every number/status shown here comes from a real read
 * (SystemStats, ActivityLog, ConversationStore, StructuredMemory) - none
 * of it is a placeholder chosen to look good. Where a real value can't
 * be determined (e.g. CPU load on some OEM builds), it shows "N/A"
 * rather than inventing one.
 */

/** Whether the "display over other apps" permission is granted -
 * required before OverlayService can add its floating view. */
fun canDrawOverlays(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

@Composable
fun DashboardScreen(
    onOpenSettings: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenNewChat: () -> Unit,
    onOpenActivityFull: () -> Unit,
    onOpenMemoryFull: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activePersona by remember { mutableStateOf(PersonaState.getActive(context)) }
    var overlayOn by remember { mutableStateOf(canDrawOverlays(context)) }
    var stats by remember { mutableStateOf<SystemStats.Snapshot?>(null) }
    var recentActivity by remember { mutableStateOf(ActivityLog.getRecent(context, 4)) }
    var lastPersonaMessage by remember { mutableStateOf<String?>(null) }
    var factCount by remember { mutableStateOf(0) }
    var threadCount by remember { mutableStateOf(0) }
    var messageCount by remember { mutableStateOf(0) }

    // Refreshes real system stats every 3s while this screen is visible -
    // cheap reads (RAM/battery/network), plus the /proc/stat CPU sample
    // (itself ~200ms) all done off the main thread.
    LaunchedEffect(Unit) {
        while (true) {
            stats = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                SystemStats.read(context)
            }
            delay(3000)
        }
    }

    // Refreshes the activity/memory/last-message panels whenever this
    // screen is (re)composed fresh - e.g. coming back from Chat after
    // sending a message.
    LaunchedEffect(Unit) {
        recentActivity = ActivityLog.getRecent(context, 4)
        val threads = ConversationStore.getAllThreads(context)
        threadCount = threads.size
        messageCount = threads.sumOf { it.messages.size }
        factCount = StructuredMemory.getFacts(context).size
        lastPersonaMessage = threads
            .flatMap { it.messages }
            .filter { !it.fromUser && it.persona == activePersona.name }
            .maxByOrNull { it.timestamp }
            ?.text
    }

    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            scope.launch {
                val localReply = RealTimeTools.handleLocalCommand(context, spoken)
                    ?: PersonalTools.handleLocalCommand(context, spoken)
                val reply = localReply ?: BrainRouter.sendMessage(context, spoken, activePersona)
                ActivityLog.addEvent(context, "Quick voice with ${activePersona.displayName}")
                recentActivity = ActivityLog.getRecent(context, 4)
                VoiceOutput.speak(reply, activePersona)
                Toast.makeText(context, reply, Toast.LENGTH_LONG).show()
            }
        }
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ${activePersona.displayName}")
                }
            )
        }
    }

    fun onMicTapped() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            speechLauncher.launch(
                Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ${activePersona.displayName}")
                }
            )
        } else {
            micPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "GODAVINRSIDER",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "ONE CORE - THREE MINDS",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
            Text(
                text = "Settings",
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.clickable { onOpenSettings() }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // System overview panel - real values only
        PanelCard {
            Text("System overview", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                StatTile("CPU", stats?.cpuLoadPercent?.let { "$it%" } ?: "N/A")
                StatTile("RAM", stats?.let { "${it.ramUsedPercent}%" } ?: "…")
                StatTile("BATTERY", stats?.let { "${it.batteryPercent}%${if (it.isCharging) " +" else ""}" } ?: "…")
                StatTile("NETWORK", stats?.networkLabel ?: "…")
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // Wedge ring - tap a third of the ring to switch persona
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            PersonaWedgeRing(
                active = activePersona,
                onPersonaTapped = { picked ->
                    activePersona = picked
                    PersonaState.setActive(context, picked)
                }
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Active persona card
        PanelCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(activePersona.color())
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    activePersona.displayName,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = activePersona.color()
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Chat Available", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = lastPersonaMessage?.take(140)
                    ?: "Ready - say hello to get started.",
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Activity panel - real recent events only
        PanelCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Activity", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                Text(
                    "See All",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenActivityFull() }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            if (recentActivity.isEmpty()) {
                Text("Nothing yet - your recent activity will show up here.", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            } else {
                recentActivity.forEach { entry ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(entry.label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f))
                        Text(ActivityLog.relativeLabel(entry.timestamp), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Memory panel - real counts only
        PanelCard {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Memory", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
                Text(
                    "See All",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onOpenMemoryFull() }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            MemoryRow("Structured facts", "$factCount saved")
            MemoryRow("Chat threads", "$threadCount")
            MemoryRow("Messages saved", "$messageCount")
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Quick actions
        PanelCard {
            Text("Quick actions", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
            Spacer(modifier = Modifier.height(10.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionTile("New chat", Modifier.weight(1f), onClick = onOpenNewChat)
                QuickActionTile("All chats", Modifier.weight(1f), onClick = onOpenChat)
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickActionTile("Settings", Modifier.weight(1f), onClick = onOpenSettings)
                QuickActionTile(
                    if (!canDrawOverlays(context)) "Enable widget"
                    else if (overlayOn) "Widget: On"
                    else "Widget: Off",
                    Modifier.weight(1f),
                    onClick = {
                        if (!canDrawOverlays(context)) {
                            val intent = Intent(
                                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                Uri.parse("package:${context.packageName}")
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            context.startActivity(intent)
                        } else if (!overlayOn) {
                            context.startService(Intent(context, OverlayService::class.java))
                            overlayOn = true
                        } else {
                            context.stopService(Intent(context, OverlayService::class.java))
                            overlayOn = false
                        }
                    }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Bottom row: Chat tile | mic | Status tile
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .clickable { onOpenChat() }
                    .padding(12.dp)
            ) {
                Text("Chat", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("Text with VINCE, CLARA or DAVINA", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }

            Spacer(modifier = Modifier.width(10.dp))

            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(activePersona.color())
                    .clickable { onMicTapped() },
                contentAlignment = Alignment.Center
            ) {
                Text("\uD83C\uDF99", fontSize = 24.sp)
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                    .padding(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                Text(
                    if (stats?.networkLabel == "Offline") "Offline" else "Online",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text("3 personas ready", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0D0D0F))
            .padding(16.dp),
        content = content
    )
}

@Composable
private fun RowScope.StatTile(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
        Text(value, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
        Text(label, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f))
    }
}

@Composable
private fun MemoryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f))
        Text(value, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f))
    }
}

@Composable
private fun QuickActionTile(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f))
            .clickable { onClick() }
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * The "wedge ring" from the locked reference image - a circle split into
 * three equal arcs (one per persona), the active one drawn at full
 * brightness and the other two dimmed, with the VINCE triangle logo
 * centered inside. Tapping a third of the ring switches to that persona.
 */
@Composable
private fun PersonaWedgeRing(active: Persona, onPersonaTapped: (Persona) -> Unit) {
    val diameterDp = 200.dp
    val strokeDp = 18.dp

    Box(
        modifier = Modifier.size(diameterDp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        // atan2 gives -180..180 from the positive x-axis;
                        // shift so 0 degrees is "top" (matches how the
                        // arcs below are drawn starting at -90).
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90
                        if (angle < 0) angle += 360
                        val picked = when {
                            angle < 120 -> Persona.VINCE
                            angle < 240 -> Persona.CLARA
                            else -> Persona.DAVINA
                        }
                        onPersonaTapped(picked)
                    }
                }
        ) {
            val strokePx = strokeDp.toPx()
            val arcSize = androidx.compose.ui.geometry.Size(
                size.width - strokePx, size.height - strokePx
            )
            val topLeft = Offset(strokePx / 2, strokePx / 2)

            fun arcAlpha(p: Persona) = if (p == active) 1f else 0.3f

            drawArc(
                color = Persona.VINCE.color().copy(alpha = arcAlpha(Persona.VINCE)),
                startAngle = -90f, sweepAngle = 119f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokePx)
            )
            drawArc(
                color = Persona.CLARA.color().copy(alpha = arcAlpha(Persona.CLARA)),
                startAngle = 30f, sweepAngle = 119f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokePx)
            )
            drawArc(
                color = Persona.DAVINA.color().copy(alpha = arcAlpha(Persona.DAVINA)),
                startAngle = 150f, sweepAngle = 119f, useCenter = false,
                topLeft = topLeft, size = arcSize,
                style = Stroke(width = strokePx)
            )
        }

        Box(
            modifier = Modifier
                .size(diameterDp - strokeDp * 2 - 16.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D0D0F)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_vince_triangle),
                contentDescription = null,
                modifier = Modifier.size(min(diameterDp.value, 56f).dp)
            )
        }
    }
}
