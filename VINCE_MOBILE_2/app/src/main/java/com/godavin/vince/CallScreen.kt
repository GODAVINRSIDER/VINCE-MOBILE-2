package com.godavin.vince

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Live two-way PC-VINCE calling - the feature scoped in
 * vince-ecosystem-plan (both directions, real conversation, not a
 * snapshot). Plumbing (CallRepository.kt, Firestore schema) was already
 * built and proven on GitHub Actions; this screen is what actually uses
 * it: a Call screen, real mic recording chunked to Firestore, polling +
 * playback of the PC's replies, and a Quick Actions entry point.
 *
 * ASSUMPTION TO VERIFY AGAINST vince_relay.py: chunks here are recorded
 * as AAC-in-MP4 (.m4a) and sent with content_type "audio/mp4" - NOT the
 * "audio/ogg" default in CallRepository.sendChunk()'s signature. This is
 * because MediaRecorder's OGG/OPUS output needs API 29+, and this app's
 * minSdk is 26 - AAC/MP4 is supported back to API 10 and is the safe
 * universal choice. If vince_relay.py on the PC assumes Ogg/Opus and
 * decodes by content_type, this needs a matching update on that side -
 * I don't have that file to check.
 */
sealed class CallLaunchMode {
    object Outgoing : CallLaunchMode()
    data class Incoming(val callId: String, val personaName: String) : CallLaunchMode()
}

private enum class CallUiState { OUTGOING, INCOMING, CONNECTED, ENDED, DECLINED, FAILED }

private const val CHUNK_DURATION_MS = 3500L
private const val POLL_INTERVAL_MS = 1500L

@Composable
fun CallScreen(mode: CallLaunchMode, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val persona = remember {
        when (mode) {
            is CallLaunchMode.Outgoing -> PersonaState.getActive(context)
            is CallLaunchMode.Incoming -> Persona.fromName(mode.personaName)
        }
    }

    var callId by remember { mutableStateOf((mode as? CallLaunchMode.Incoming)?.callId) }
    var uiState by remember {
        mutableStateOf(if (mode is CallLaunchMode.Incoming) CallUiState.INCOMING else CallUiState.OUTGOING)
    }
    var isMuted by remember { mutableStateOf(false) }
    var isSpeakerOn by remember { mutableStateOf(true) }
    var elapsedSeconds by remember { mutableStateOf(0) }
    var hasMicPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val recorder = remember { CallAudioRecorder(context) }
    val player = remember { CallAudioPlayer() }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted -> hasMicPermission = granted }

    LaunchedEffect(Unit) {
        if (!hasMicPermission) micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }

    // Route audio for a real call (earpiece/speaker), not media playback.
    DisposableEffect(Unit) {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        onDispose {
            audioManager.mode = AudioManager.MODE_NORMAL
            audioManager.isSpeakerphoneOn = false
            recorder.release()
            player.release()
        }
    }
    LaunchedEffect(isSpeakerOn) { audioManager.isSpeakerphoneOn = isSpeakerOn }

    // Kick off the outgoing call.
    LaunchedEffect(Unit) {
        if (mode is CallLaunchMode.Outgoing) {
            try {
                callId = CallRepository.createCall(initiatedBy = "phone", persona = persona.name)
            } catch (e: Exception) {
                uiState = CallUiState.FAILED
            }
        }
    }

    // While ringing out, watch for the PC accepting/declining/ending.
    LaunchedEffect(callId, uiState) {
        val id = callId ?: return@LaunchedEffect
        if (uiState != CallUiState.OUTGOING) return@LaunchedEffect
        while (isActive && uiState == CallUiState.OUTGOING) {
            delay(POLL_INTERVAL_MS)
            val data = try { CallRepository.getCall(id) } catch (e: Exception) { null } ?: continue
            when (data["status"] as? String) {
                "connected" -> uiState = CallUiState.CONNECTED
                "declined" -> uiState = CallUiState.DECLINED
                "ended" -> uiState = CallUiState.ENDED
            }
        }
    }

    // While connected, watch for the other side ending the call.
    LaunchedEffect(callId, uiState) {
        val id = callId ?: return@LaunchedEffect
        if (uiState != CallUiState.CONNECTED) return@LaunchedEffect
        while (isActive && uiState == CallUiState.CONNECTED) {
            delay(2000)
            val data = try { CallRepository.getCall(id) } catch (e: Exception) { null } ?: continue
            if (data["status"] as? String == "ended") uiState = CallUiState.ENDED
        }
    }

    // Call timer.
    LaunchedEffect(uiState) {
        if (uiState == CallUiState.CONNECTED) {
            while (isActive && uiState == CallUiState.CONNECTED) {
                delay(1000)
                elapsedSeconds++
            }
        }
    }

    // Record-and-send loop: only while connected, unmuted, and mic granted.
    LaunchedEffect(callId, uiState, isMuted, hasMicPermission) {
        val id = callId ?: return@LaunchedEffect
        if (uiState != CallUiState.CONNECTED || isMuted || !hasMicPermission) return@LaunchedEffect
        while (isActive && uiState == CallUiState.CONNECTED && !isMuted) {
            val bytes = try { recorder.recordChunk(CHUNK_DURATION_MS) } catch (e: Exception) { null }
            if (bytes != null && bytes.isNotEmpty()) {
                try {
                    CallRepository.sendChunk(id, fromSide = "phone", audioBytes = bytes, contentType = "audio/mp4")
                } catch (e: Exception) {
                    // A dropped chunk on a flaky connection shouldn't end the
                    // call - just keep looping to the next one.
                }
            }
        }
    }

    // Poll-and-play loop for the PC's replies.
    LaunchedEffect(callId, uiState) {
        val id = callId ?: return@LaunchedEffect
        if (uiState != CallUiState.CONNECTED) return@LaunchedEffect
        while (isActive && uiState == CallUiState.CONNECTED) {
            delay(POLL_INTERVAL_MS)
            val chunks = try { CallRepository.getNewChunks(id, fromSide = "pc") } catch (e: Exception) { emptyList() }
            for ((chunkId, bytes) in chunks) {
                try { player.playAndAwait(bytes) } catch (e: Exception) { /* skip a bad chunk */ }
                try { CallRepository.markChunkProcessed(id, chunkId) } catch (e: Exception) {}
            }
        }
    }

    fun endCall() {
        val id = callId
        uiState = CallUiState.ENDED
        scope.launch {
            if (id != null) try { CallRepository.endCall(id) } catch (e: Exception) {}
        }
    }

    fun acceptCall() {
        val id = callId ?: return
        scope.launch {
            try {
                CallRepository.acceptCall(id)
                uiState = CallUiState.CONNECTED
            } catch (e: Exception) { uiState = CallUiState.FAILED }
        }
    }

    fun declineCall() {
        val id = callId
        uiState = CallUiState.DECLINED
        scope.launch {
            if (id != null) try { CallRepository.declineCall(id) } catch (e: Exception) {}
        }
    }

    val statusText = when (uiState) {
        CallUiState.OUTGOING -> "Calling..."
        CallUiState.INCOMING -> "Incoming Call"
        CallUiState.CONNECTED -> "Connected"
        CallUiState.ENDED -> "Call Ended"
        CallUiState.DECLINED -> "Call Declined"
        CallUiState.FAILED -> "Couldn't Connect"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "←",
                    fontSize = 22.sp,
                    color = Color.White,
                    modifier = Modifier.clickable { onBack() }
                )
                Spacer(Modifier.weight(1f))
                Text(
                    "VINCE PC",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = persona.color()
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.width(22.dp)) // balances the back arrow
            }

            Spacer(Modifier.height(36.dp))

            CallRing(persona = persona, active = uiState == CallUiState.CONNECTED || uiState == CallUiState.OUTGOING)

            Spacer(Modifier.height(20.dp))

            Text(statusText, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(
                when (uiState) {
                    CallUiState.CONNECTED -> formatDuration(elapsedSeconds)
                    CallUiState.OUTGOING -> "Connecting to your PC..."
                    CallUiState.INCOMING -> "Your PC is calling..."
                    CallUiState.ENDED -> "Duration ${formatDuration(elapsedSeconds)}"
                    else -> ""
                },
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.6f)
            )

            Spacer(Modifier.height(28.dp))

            if (uiState == CallUiState.CONNECTED || uiState == CallUiState.OUTGOING) {
                WaveformBars(color = persona.color())
            }

            Spacer(Modifier.weight(1f))

            when (uiState) {
                CallUiState.INCOMING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallCircleButton(emoji = "✕", label = "Decline", bg = Color(0xFFEF4444), onClick = ::declineCall)
                    CallCircleButton(emoji = "✓", label = "Accept", bg = Color(0xFF22C55E), onClick = ::acceptCall)
                }
                CallUiState.OUTGOING -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallCircleButton(
                        emoji = if (isMuted) "🔇" else "🎤", label = "Mute",
                        bg = Color(0xFF1F2937), onClick = { isMuted = !isMuted }
                    )
                    CallCircleButton(emoji = "☎", label = "Cancel", bg = Color(0xFFEF4444), onClick = ::endCall)
                    CallCircleButton(
                        emoji = if (isSpeakerOn) "🔊" else "🔈", label = "Speaker",
                        bg = Color(0xFF1F2937), onClick = { isSpeakerOn = !isSpeakerOn }
                    )
                }
                CallUiState.CONNECTED -> Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CallCircleButton(
                        emoji = if (isMuted) "🔇" else "🎤", label = "Mute",
                        bg = Color(0xFF1F2937), onClick = { isMuted = !isMuted }
                    )
                    CallCircleButton(emoji = "☎", label = "End", bg = Color(0xFFEF4444), onClick = ::endCall)
                    CallCircleButton(
                        emoji = if (isSpeakerOn) "🔊" else "🔈", label = "Speaker",
                        bg = Color(0xFF1F2937), onClick = { isSpeakerOn = !isSpeakerOn }
                    )
                }
                CallUiState.ENDED, CallUiState.DECLINED, CallUiState.FAILED -> {
                    androidx.compose.material3.Button(
                        onClick = onBack,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("Done") }
                }
            }

            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun CallRing(persona: Persona, active: Boolean) {
    val infinite = rememberInfiniteTransition(label = "call_ring_pulse")
    val pulse by infinite.animateFloat(
        initialValue = if (active) 0.9f else 1f,
        targetValue = if (active) 1.05f else 1f,
        animationSpec = infiniteRepeatable(tween(1200, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "pulse"
    )
    Box(
        modifier = Modifier
            .size(180.dp)
            .shadow(elevation = 16.dp, shape = CircleShape, ambientColor = Color(0xFF4CE1FF), spotColor = Color(0xFFFF4FD8))
            .clip(CircleShape)
            .background(Color(0xFF0D0D0F))
            .background(
                Brush.radialGradient(
                    colors = listOf(
                        persona.color().copy(alpha = 0.30f),
                        Color(0xFF8B5CF6).copy(alpha = 0.10f),
                        Color.Transparent
                    )
                )
            )
            .border(
                width = 2.dp,
                brush = Brush.linearGradient(
                    listOf(Color(0xFF4CE1FF), Color(0xFF8B5CF6), Color(0xFFFF4FD8))
                ),
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.Image(
            painter = painterResource(id = R.drawable.ic_vince_triangle),
            contentDescription = null,
            modifier = Modifier
                .size(72.dp)
                .scale(if (active) pulse else 1f)
        )
    }
}

@Composable
private fun WaveformBars(color: Color) {
    val barCount = 24
    val infinite = rememberInfiniteTransition(label = "waveform")
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(40.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { i ->
            val phase by infinite.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(500 + (i % 5) * 90, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse
                ),
                label = "bar_$i"
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height((40 * phase).dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color.copy(alpha = 0.7f))
            )
        }
    }
}

@Composable
private fun CallCircleButton(emoji: String, label: String, bg: Color, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(bg)
                .clickable { onClick() },
            contentAlignment = Alignment.Center
        ) {
            Text(emoji, fontSize = 22.sp)
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = 11.sp, color = Color.White.copy(alpha = 0.7f), textAlign = TextAlign.Center)
    }
}

private fun formatDuration(totalSeconds: Int): String {
    val m = totalSeconds / 60
    val s = totalSeconds % 60
    return "%02d:%02d".format(m, s)
}

/**
 * Records short chunks to a temp .m4a file and returns the raw bytes -
 * see the file-level ASSUMPTION note about content type/codec choice.
 */
private class CallAudioRecorder(private val context: Context) {
    private var current: MediaRecorder? = null

    suspend fun recordChunk(durationMs: Long): ByteArray = withContext(Dispatchers.IO) {
        val file = File.createTempFile("vince_call_out_", ".m4a", context.cacheDir)
        try {
            @Suppress("DEPRECATION")
            val mr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
            current = mr
            try {
                // The delay is the only suspend point below, so it's the
                // only place cancellation (e.g. the user tapping mute
                // mid-chunk) can land. This finally still runs on
                // cancellation - that's what guarantees the recorder always
                // gets stopped/released instead of leaking a live
                // MediaRecorder holding the mic.
                mr.setAudioSource(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                mr.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                mr.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                mr.setAudioEncodingBitRate(32000)
                mr.setAudioSamplingRate(16000)
                mr.setOutputFile(file.absolutePath)
                mr.prepare()
                mr.start()
                delay(durationMs)
            } finally {
                try { mr.stop() } catch (e: Exception) { /* e.g. cancelled before any data was captured */ }
                try { mr.release() } catch (e: Exception) {}
                current = null
            }
            file.readBytes()
        } finally {
            file.delete()
        }
    }

    fun release() {
        current?.let {
            try { it.stop() } catch (e: Exception) { /* may not have started */ }
            try { it.release() } catch (e: Exception) {}
        }
        current = null
    }
}

/** Plays one chunk's bytes via a temp file and suspends until playback
 * actually finishes, so incoming chunks play back-to-back instead of
 * overlapping. */
private class CallAudioPlayer {
    private var current: MediaPlayer? = null

    suspend fun playAndAwait(bytes: ByteArray) {
        val file = File.createTempFile("vince_call_in_", ".m4a")
        file.writeBytes(bytes)
        try {
            suspendCancellableCoroutine<Unit> { cont ->
                val mp = MediaPlayer()
                current = mp
                try {
                    mp.setDataSource(file.absolutePath)
                    mp.setOnCompletionListener {
                        it.release()
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                    }
                    mp.setOnErrorListener { mpErr, _, _ ->
                        mpErr.release()
                        if (cont.isActive) cont.resumeWith(Result.success(Unit))
                        true
                    }
                    mp.prepare()
                    mp.start()
                } catch (e: Exception) {
                    mp.release()
                    if (cont.isActive) cont.resumeWith(Result.success(Unit))
                }
                cont.invokeOnCancellation {
                    try { mp.release() } catch (e: Exception) {}
                }
            }
        } finally {
            file.delete()
        }
    }

    fun release() {
        current?.let { try { it.release() } catch (e: Exception) {} }
        current = null
    }
}
