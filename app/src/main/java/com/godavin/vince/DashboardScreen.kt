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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
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
 * three equal arcs (one per persona). Two real (not merely decorative)
 * pieces of motion, since a literal 3D render is out of reach for a
 * Compose UI without pulling in a full 3D/game-engine layer:
 *
 * - A slow ambient outer ring that continuously rotates - the "rings
 *   circling" cinematic feel, always alive even when nothing's tapped.
 * - Tapping the CENTER logo cycles to the next persona and the whole
 *   colored ring genuinely rotates ("orbits") so that persona's arc
 *   animates around to the top - not just a recolor, an actual rotation.
 *   Tapping a specific third of the ring still jumps straight to that
 *   persona, same motion either way.
 *
 * A soft neon glow is layered behind the sharp ring via a blurred copy
 * (Compose's blur() modifier) - this only renders on Android 12+
 * (API 31), since RenderEffect-based blur isn't available below that;
 * older phones still get the full rotation/orbit behavior, just without
 * the glow softening.
 */
@Composable
private fun PersonaWedgeRing(active: Persona, onPersonaTapped: (Persona) -> Unit) {
    val diameterDp = 200.dp
    val strokeDp = 18.dp

    fun baseCenterAngle(p: Persona): Float = when (p) {
        Persona.VINCE -> -30.5f
        Persona.CLARA -> 89.5f
        Persona.DAVINA -> 209.5f
    }

    fun targetRotation(p: Persona): Float {
        var r = -90f - baseCenterAngle(p)
        while (r < 0f) r += 360f
        while (r >= 360f) r -= 360f
        return r
    }

    val rotation = remember { Animatable(targetRotation(active)) }
    LaunchedEffect(active) {
        rotation.animateTo(targetRotation(active), animationSpec = tween(700, easing = FastOutSlowInEasing))
    }

    val infiniteTransition = rememberInfiniteTransition(label = "ambient_ring")
    val ambientAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(24000, easing = LinearEasing), RepeatMode.Restart),
        label = "ambient_angle"
    )

    fun cycleNext() {
        val next = when (active) {
            Persona.VINCE -> Persona.CLARA
            Persona.CLARA -> Persona.DAVINA
            Persona.DAVINA -> Persona.VINCE
        }
        onPersonaTapped(next)
    }

    fun androidx.compose.ui.graphics.drawscope.DrawScope.ringContent(strokeMultiplier: Float = 1f) {
        val strokePx = strokeDp.toPx() * strokeMultiplier
        val arcSize = androidx.compose.ui.geometry.Size(size.width - strokePx, size.height - strokePx)
        val topLeft = Offset(strokePx / 2, strokePx / 2)

        rotate(degrees = rotation.value) {
            fun arcAlpha(p: Persona) = if (p == active) 1f else 0.3f
            drawArc(
                color = Persona.VINCE.color().copy(alpha = arcAlpha(Persona.VINCE)),
                startAngle = -90f, sweepAngle = 119f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = strokePx)
            )
            drawArc(
                color = Persona.CLARA.color().copy(alpha = arcAlpha(Persona.CLARA)),
                startAngle = 30f, sweepAngle = 119f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = strokePx)
            )
            drawArc(
                color = Persona.DAVINA.color().copy(alpha = arcAlpha(Persona.DAVINA)),
                startAngle = 150f, sweepAngle = 119f, useCenter = false,
                topLeft = topLeft, size = arcSize, style = Stroke(width = strokePx)
            )
        }
    }

    Box(
        modifier = Modifier.size(diameterDp + 28.dp),
        contentAlignment = Alignment.Center
    ) {
        // Ambient decorative outer ring - continuously rotating, purely
        // visual, not tap-mapped, faint HUD-style ticks for cinematic motion.
        Canvas(modifier = Modifier.fillMaxSize()) {
            rotate(degrees = ambientAngle) {
                val r = size.minDimension / 2f
                for (i in 0 until 24) {
                    val tickAngle = Math.toRadians((i * 15).toDouble())
                    val inner = r - 4.dp.toPx()
                    val outer = r
                    val start = Offset(
                        (size.width / 2 + inner * kotlin.math.cos(tickAngle)).toFloat(),
                        (size.height / 2 + inner * kotlin.math.sin(tickAngle)).toFloat()
                    )
                    val end = Offset(
                        (size.width / 2 + outer * kotlin.math.cos(tickAngle)).toFloat(),
                        (size.height / 2 + outer * kotlin.math.sin(tickAngle)).toFloat()
                    )
                    drawLine(
                        color = Color(0xFF2A3B45),
                        start = start, end = end, strokeWidth = 1.5f
                    )
                }
            }
        }

        // Glow layer - blurred duplicate of the ring, API 31+ only.
        Canvas(
            modifier = Modifier
                .size(diameterDp)
                .blur(18.dp)
        ) { ringContent(strokeMultiplier = 1.3f) }

        // Sharp ring on top - this is the one that handles taps.
        Canvas(
            modifier = Modifier
                .size(diameterDp)
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = offset.x - center.x
                        val dy = offset.y - center.y
                        var angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 90 - rotation.value
                        while (angle < 0) angle += 360
                        while (angle >= 360) angle -= 360
                        val picked = when {
                            angle < 120 -> Persona.VINCE
                            angle < 240 -> Persona.CLARA
                            else -> Persona.DAVINA
                        }
                        onPersonaTapped(picked)
                    }
                }
        ) { ringContent() }

        Box(
            modifier = Modifier
                .size(diameterDp - strokeDp * 2 - 16.dp)
                .clip(CircleShape)
                .background(Color(0xFF0D0D0F))
                .clickable { cycleNext() },
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
