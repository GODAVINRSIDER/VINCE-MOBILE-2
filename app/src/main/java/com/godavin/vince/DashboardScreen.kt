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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
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

/** Fixed thread id for the Home mic's dedicated voice-only conversation -
 * kept separate from typed Chat threads (its own scrollback/history),
 * but sharing the exact same StructuredMemory facts and BrainRouter
 * pipeline as every other thread, so it "remembers" the same way. */
private const val VOICE_THREAD_ID = "home_voice_chat"

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
    onOpenMemoryFull: () -> Unit,
    onOpenCall: () -> Unit
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

    var isListening by remember { mutableStateOf(false) }

    fun sendVoiceTurn(spokenText: String) {
        scope.launch {
            ConversationStore.ensureThread(context, VOICE_THREAD_ID, "Voice Chat")
            // Snapshot BEFORE adding this turn's user message, same
            // discipline as typed Chat, so BrainRouter gets real prior
            // context without double-counting the current message.
            val historySnapshot = ConversationStore.getThread(context, VOICE_THREAD_ID)?.messages?.toList()
                ?: emptyList()
            val userMsg = ChatMessage(fromUser = true, text = spokenText)
            ConversationStore.addMessage(context, VOICE_THREAD_ID, userMsg)

            val localReply = RealTimeTools.handleLocalCommand(context, spokenText)
                ?: PersonalTools.handleLocalCommand(context, spokenText)
            val reply = localReply
                ?: BrainRouter.sendMessage(context, spokenText, activePersona, historySnapshot)

            val replyMsg = ChatMessage(fromUser = false, text = reply, persona = activePersona.name)
            ConversationStore.addMessage(context, VOICE_THREAD_ID, replyMsg)

            ActivityLog.addEvent(context, "Voice chat with ${activePersona.displayName}")
            recentActivity = ActivityLog.getRecent(context, 4)
            VoiceOutput.speak(reply, activePersona)
        }
    }

    // Direct SpeechRecognizer, not the RecognizerIntent activity - this
    // is what avoids Google's own floating "Speak now" UI popping up;
    // the halo drawn around the mic button below is VINCE's own
    // listening indicator instead, matching the floating widget's look.
    fun startListening() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            Toast.makeText(context, "Speech recognition isn't available on this device.", Toast.LENGTH_SHORT).show()
            return
        }
        isListening = true
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                val text = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!text.isNullOrBlank()) sendVoiceTurn(text)
                recognizer.destroy()
            }
            override fun onReadyForSpeech(params: android.os.Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() { isListening = false }
            override fun onError(error: Int) { isListening = false; recognizer.destroy() }
            override fun onPartialResults(partialResults: android.os.Bundle?) {}
            override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
        })
        recognizer.startListening(
            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            }
        )
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening()
    }

    fun onMicTapped() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            startListening()
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
                            WidgetState.setEnabled(context, true)
                        } else {
                            context.stopService(Intent(context, OverlayService::class.java))
                            overlayOn = false
                            WidgetState.setEnabled(context, false)
                        }
                    }
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            CallQuickActionTile(onClick = onOpenCall)
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
                modifier = Modifier.size(80.dp),
                contentAlignment = Alignment.Center
            ) {
                // Halo indicator while actively listening - same visual
                // language as the floating widget, not a system dialog.
                if (isListening) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(CircleShape)
                            .background(Color.Transparent)
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawCircle(
                                color = activePersona.color(),
                                radius = size.minDimension / 2f - 3.dp.toPx(),
                                style = Stroke(width = 3.dp.toPx())
                            )
                        }
                    }
                }
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

// Shared with SettingsScreen (MainActivity.kt) for consistent card
// grouping across the app - not private anymore for that reason.
@Composable
fun PanelCard(content: @Composable ColumnScope.() -> Unit) {
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

/** Full-width entry point into the live PC-VINCE call screen - visually
 * distinct from the plain grey QuickActionTiles above it so it reads as
 * the "special" action, matching the reference sheet's gradient ring
 * language (cyan -> purple -> pink) instead of blending in. */
@Composable
private fun CallQuickActionTile(onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(
                        Color(0xFF4CE1FF).copy(alpha = 0.18f),
                        Color(0xFF8B5CF6).copy(alpha = 0.18f),
                        Color(0xFFFF4FD8).copy(alpha = 0.18f)
                    )
                )
            )
            .border(
                width = 1.dp,
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color(0xFF4CE1FF), Color(0xFF8B5CF6), Color(0xFFFF4FD8))
                ),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable { onClick() }
            .padding(vertical = 14.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_vince_triangle),
            contentDescription = null,
            modifier = Modifier.size(20.dp)
        )
        Text("Call VINCE PC", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color.White)
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
 * The persona ring, rebuilt to the "cleaner" satellite style Vincent
 * picked from the reference sheet: three small persona circles (each
 * showing that persona's icon - brain/heart/lotus) connected to a
 * center V hub by thin lines, instead of a solid wedge-donut. Two real
 * pieces of motion:
 *
 * - Tap the CENTER hub: cycles to the next persona and the satellites
 *   genuinely rotate their positions ("orbit") so the active one ends
 *   up at the top - not a recolor, an actual repositioning.
 * - Tap a specific satellite directly: jumps straight to that persona,
 *   same orbit motion.
 * - The active satellite gently pulses (scale + connecting-line
 *   brightness) so it's always clear which one is active even mid-orbit.
 *
 * Hit-testing is just each satellite's own Compose clickable bounds, not
 * manual angle math, so it stays accurate through every animation frame.
 */
@Composable
private fun PersonaWedgeRing(active: Persona, onPersonaTapped: (Persona) -> Unit) {
    val diameterDp = 220.dp
    val radiusDp = 82.dp
    val satelliteDp = 58.dp
    val centerDp = 66.dp

    fun baseAngle(p: Persona): Float = when (p) {
        Persona.VINCE -> -90f
        Persona.CLARA -> 150f
        Persona.DAVINA -> 30f
    }

    fun targetRotation(p: Persona): Float {
        var r = -90f - baseAngle(p)
        while (r < 0f) r += 360f
        while (r >= 360f) r -= 360f
        return r
    }

    val rotation = remember { Animatable(targetRotation(active)) }
    LaunchedEffect(active) {
        rotation.animateTo(targetRotation(active), animationSpec = tween(700, easing = FastOutSlowInEasing))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulse by infiniteTransition.animateFloat(
        initialValue = 0.88f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1300, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse_scale"
    )

    fun cycleNext() {
        val next = when (active) {
            Persona.VINCE -> Persona.CLARA
            Persona.CLARA -> Persona.DAVINA
            Persona.DAVINA -> Persona.VINCE
        }
        onPersonaTapped(next)
    }

    fun iconFor(p: Persona): Int = when (p) {
        Persona.VINCE -> R.drawable.ic_persona_vince
        Persona.CLARA -> R.drawable.ic_persona_clara
        Persona.DAVINA -> R.drawable.ic_persona_davina
    }

    val density = LocalDensity.current
    val radiusPx = with(density) { radiusDp.toPx() }
    val personas = listOf(Persona.VINCE, Persona.CLARA, Persona.DAVINA)

    // Lively motion #2 - a small bright "energy pulse" travels along each
    // connecting line, hub-to-satellite, looping continuously. Combined
    // with the curved (not straight) connectors and the radial-gradient
    // glow on every circle below, this is the "lively, more dimensional"
    // pass on top of the orbit rotation - real depth cues (glow + colored
    // shadow + motion) rather than a flat static ring, without needing an
    // actual 3D engine.
    val pulseTransition = rememberInfiniteTransition(label = "energy_pulse")
    val pulseT by pulseTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "energy_pulse_t"
    )

    Box(
        modifier = Modifier.size(diameterDp),
        contentAlignment = Alignment.Center
    ) {
        // Connecting lines - drawn first, so satellites/hub sit on top.
        Canvas(modifier = Modifier.fillMaxSize()) {
            val centerOffset = Offset(this.size.width / 2f, this.size.height / 2f)
            personas.forEach { p ->
                val isActive = p == active
                val angleRad = Math.toRadians((baseAngle(p) + rotation.value).toDouble())
                val end = Offset(
                    centerOffset.x + (radiusPx * kotlin.math.cos(angleRad)).toFloat(),
                    centerOffset.y + (radiusPx * kotlin.math.sin(angleRad)).toFloat()
                )
                // Curve the connector outward slightly (perpendicular to
                // the hub-satellite line) instead of a straight line -
                // gives the ring real dimensional bow instead of flat spokes.
                val mid = Offset((centerOffset.x + end.x) / 2f, (centerOffset.y + end.y) / 2f)
                val perpAngle = angleRad + Math.PI / 2
                val bow = radiusPx * 0.12f
                val control = Offset(
                    mid.x + (bow * kotlin.math.cos(perpAngle)).toFloat(),
                    mid.y + (bow * kotlin.math.sin(perpAngle)).toFloat()
                )

                val path = androidx.compose.ui.graphics.Path().apply {
                    moveTo(centerOffset.x, centerOffset.y)
                    quadraticBezierTo(control.x, control.y, end.x, end.y)
                }

                drawPath(
                    path = path,
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isActive) 0.5f else 0.15f),
                            p.color().copy(alpha = if (isActive) 0.9f else 0.3f)
                        ),
                        start = centerOffset,
                        end = end
                    ),
                    style = Stroke(width = if (isActive) 3.dp.toPx() else 1.5.dp.toPx())
                )

                // Traveling energy pulse along the curve (quadratic Bezier
                // point at parameter t), brighter on the active connector.
                val t = pulseT
                val oneMinusT = 1 - t
                val pulseX = oneMinusT * oneMinusT * centerOffset.x + 2 * oneMinusT * t * control.x + t * t * end.x
                val pulseY = oneMinusT * oneMinusT * centerOffset.y + 2 * oneMinusT * t * control.y + t * t * end.y
                drawCircle(
                    color = p.color().copy(alpha = if (isActive) 0.9f else 0.35f),
                    radius = if (isActive) 4.dp.toPx() else 2.5.dp.toPx(),
                    center = Offset(pulseX, pulseY)
                )
            }
        }

        // Satellites - each one's own clickable, so tap targeting stays
        // correct through the whole orbit animation automatically.
        personas.forEach { p ->
            val isActive = p == active
            val scale = if (isActive) pulse else 0.85f
            val satellitePx = with(density) { (satelliteDp * scale).toPx() }
            Box(
                modifier = Modifier
                    .offset {
                        val angleRad = Math.toRadians((baseAngle(p) + rotation.value).toDouble())
                        androidx.compose.ui.unit.IntOffset(
                            (radiusPx * kotlin.math.cos(angleRad)).toInt(),
                            (radiusPx * kotlin.math.sin(angleRad)).toInt()
                        )
                    }
                    .size(satelliteDp * scale)
                    .shadow(
                        elevation = if (isActive) 14.dp else 4.dp,
                        shape = CircleShape,
                        ambientColor = p.color(),
                        spotColor = p.color()
                    )
                    .clip(CircleShape)
                    .background(Color(0xFF0D0D0F))
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(
                                p.color().copy(alpha = if (isActive) 0.45f else 0.16f),
                                p.color().copy(alpha = 0.02f)
                            )
                        )
                    )
                    // Off-center highlight (light source from the upper
                    // left) - a real, if simple, bevel/sphere cue that a
                    // flat centered gradient can't give.
                    .background(
                        androidx.compose.ui.graphics.Brush.radialGradient(
                            colors = listOf(Color.White.copy(alpha = if (isActive) 0.28f else 0.1f), Color.Transparent),
                            center = Offset(satellitePx * 0.32f, satellitePx * 0.28f),
                            radius = satellitePx * 0.5f
                        )
                    )
                    .border(
                        width = 1.dp,
                        brush = androidx.compose.ui.graphics.Brush.linearGradient(
                            listOf(Color.White.copy(alpha = if (isActive) 0.55f else 0.2f), p.color().copy(alpha = 0.4f))
                        ),
                        shape = CircleShape
                    )
                    .clickable { onPersonaTapped(p) },
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = iconFor(p)),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(p.color().copy(alpha = if (isActive) 1f else 0.55f)),
                    modifier = Modifier.size(satelliteDp * scale * 0.5f)
                )
            }
        }

        // Center hub - tap to cycle to the next persona.
        val centerPx = with(density) { centerDp.toPx() }
        Box(
            modifier = Modifier
                .size(centerDp)
                .shadow(elevation = 10.dp, shape = CircleShape, ambientColor = Color(0xFF4CE1FF), spotColor = Color(0xFFFF4FD8))
                .clip(CircleShape)
                .background(Color(0xFF0D0D0F))
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(
                            Color(0xFF4CE1FF).copy(alpha = 0.25f),
                            Color(0xFF8B5CF6).copy(alpha = 0.08f),
                            Color.Transparent
                        )
                    )
                )
                .background(
                    androidx.compose.ui.graphics.Brush.radialGradient(
                        colors = listOf(Color.White.copy(alpha = 0.3f), Color.Transparent),
                        center = Offset(centerPx * 0.32f, centerPx * 0.28f),
                        radius = centerPx * 0.5f
                    )
                )
                .border(
                    width = 1.dp,
                    brush = androidx.compose.ui.graphics.Brush.linearGradient(
                        listOf(Color.White.copy(alpha = 0.5f), Color(0xFF8B5CF6).copy(alpha = 0.5f))
                    ),
                    shape = CircleShape
                )
                .clickable { cycleNext() },
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.ic_vince_triangle),
                contentDescription = null,
                modifier = Modifier.size(centerDp * 0.55f)
            )
        }
    }
}
