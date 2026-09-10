package com.godavin.vince

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

// Stage 9 - a real chart-analysis prompt (key levels, trend, structure,
// an honest read), not a generic "describe this image" - matches
// Vincent's actual price-action/SMC trading approach. Used as the
// default whenever a photo/screen capture is sent with no typed
// question; a typed question always overrides this.
private const val DEFAULT_CHART_PROMPT = "You're looking at a trading chart for an " +
    "experienced price-action/smart-money-concepts trader. Give a focused, interactive " +
    "read: the key support and resistance levels or liquidity zones visible, the current " +
    "trend or range, any notable structure (order blocks, fair value gaps, trendlines, " +
    "break of structure), and your honest thoughts on what the chart is suggesting right " +
    "now. Be direct and specific like a second pair of eyes on the chart, not a generic " +
    "disclaimer-heavy description."

@Composable
fun ChatScreen(threadId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    // Stage 5 - whether VINCE speaks its replies out loud. Defaults on for
    // push-to-talk use; a user who only types can flip it off.
    var speakReplies by remember { mutableStateOf(true) }

    // Loads this specific thread's saved history on first open - keyed on
    // threadId so switching threads via the sidebar re-loads correctly
    // instead of reusing whatever was in state from the previous thread.
    val messages = remember(threadId) {
        mutableStateListOf<ChatMessage>().apply {
            ConversationStore.getThread(context, threadId)?.messages?.let { addAll(it) }
        }
    }

    fun send(overrideText: String? = null) {
        val text = (overrideText ?: input).trim()
        if (text.isEmpty() || sending) return

        val userMsg = ChatMessage(fromUser = true, text = text)
        messages.add(userMsg)
        ConversationStore.addMessage(context, threadId, userMsg)
        input = ""
        sending = true

        scope.launch {
            // Stage 6 - check for a deterministic time/price answer first,
            // same discipline PC-VINCE already uses: never let the AI
            // guess at a fact a real source can answer exactly. Only
            // falls through to the AI providers if this isn't a
            // time/price question.
            val localReply = RealTimeTools.handleLocalCommand(context, text)
            val reply = localReply ?: BrainRouter.sendMessage(context, text)
            val replyMsg = ChatMessage(fromUser = false, text = reply)
            messages.add(replyMsg)
            ConversationStore.addMessage(context, threadId, replyMsg)
            sending = false
            if (speakReplies) {
                VoiceOutput.speak(reply)
            }
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    // Launches Android's built-in speech-to-text UI. On a result, the
    // recognized text is sent straight away - push-to-talk, not
    // "transcribe then let me edit it first".
    val speechLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val spoken = result.data
            ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            ?.firstOrNull()
        if (!spoken.isNullOrBlank()) {
            send(overrideText = spoken)
        }
    }

    fun launchSpeechRecognition() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to VINCE")
        }
        speechLauncher.launch(intent)
    }

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchSpeechRecognition()
        }
    }

    fun onMicTapped() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            launchSpeechRecognition()
        } else {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    // Stage 8 - camera vision. Uses the system Camera app via an implicit
    // intent (ACTION_IMAGE_CAPTURE through the TakePicture contract) so
    // VINCE never needs its own CAMERA permission - the Camera app
    // handles that itself. A FileProvider hands it a place to write the
    // full-resolution photo that VINCE can then read back.
    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // Shared by both camera photos and screen captures - sends a bitmap
    // to Gemini vision and posts the result as a normal chat message.
    // label distinguishes "[Photo]" vs "[Screen]" in the chat history.
    fun sendBitmapForAnalysis(bitmap: Bitmap, question: String, label: String) {
        if (sending) return

        val userMsg = ChatMessage(fromUser = true, text = "[$label] $question")
        messages.add(userMsg)
        ConversationStore.addMessage(context, threadId, userMsg)
        input = ""
        sending = true

        scope.launch {
            val reply = try {
                val baos = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, baos)
                val apiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
                val result = GeminiVision.describeImage(apiKey, baos.toByteArray(), question)
                result.fold(
                    onSuccess = { it },
                    onFailure = { e -> "Couldn't analyze the $label. (${e.message})" }
                )
            } catch (e: Exception) {
                "Couldn't process the $label. (${e.message})"
            }

            val replyMsg = ChatMessage(fromUser = false, text = reply)
            messages.add(replyMsg)
            ConversationStore.addMessage(context, threadId, replyMsg)
            sending = false
            if (speakReplies) {
                VoiceOutput.speak(reply)
            }
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
        }
    }

    fun sendImage(uri: Uri, question: String) {
        if (sending) return
        sending = true
        input = ""

        scope.launch {
            val bitmap = try {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            if (bitmap == null) {
                val userMsg = ChatMessage(fromUser = true, text = "[Photo] $question")
                messages.add(userMsg)
                ConversationStore.addMessage(context, threadId, userMsg)
                val replyMsg = ChatMessage(fromUser = false, text = "Couldn't read the captured photo.")
                messages.add(replyMsg)
                ConversationStore.addMessage(context, threadId, replyMsg)
                sending = false
                if (messages.isNotEmpty()) {
                    listState.animateScrollToItem(messages.size - 1)
                }
            } else {
                sending = false // sendBitmapForAnalysis sets its own sending=true right after
                sendBitmapForAnalysis(bitmap, question, "Photo")
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingPhotoUri
        if (success && uri != null) {
            val question = input.trim().ifBlank { DEFAULT_CHART_PROMPT }
            sendImage(uri, question)
        }
    }

    fun onCameraTapped() {
        val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "com.godavin.vince.fileprovider", file)
        pendingPhotoUri = uri
        cameraLauncher.launch(uri)
    }

    // Stage 9 - screen vision. Reads whatever app is currently on screen
    // (TradingView, WhatsApp, anything) rather than a hardcoded app.
    // Android requires the permission prompt fresh each capture session -
    // that's OS design, not something VINCE can skip.
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenCaptureHelper = remember { ScreenCaptureHelper(context) }

    val screenPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val question = input.trim().ifBlank { DEFAULT_CHART_PROMPT }
            scope.launch {
                val bitmap = screenCaptureHelper.captureSingleFrame(
                    mediaProjectionManager, result.resultCode, data
                )
                if (bitmap != null) {
                    sendBitmapForAnalysis(bitmap, question, "Screen")
                } else {
                    val userMsg = ChatMessage(fromUser = true, text = "[Screen] $question")
                    messages.add(userMsg)
                    ConversationStore.addMessage(context, threadId, userMsg)
                    val replyMsg = ChatMessage(
                        fromUser = false,
                        text = "Couldn't capture the screen - the capture may have been " +
                            "refused by Android on this device/version. Try again, and " +
                            "if it keeps failing, that's worth reporting exactly as it happens."
                    )
                    messages.add(replyMsg)
                    ConversationStore.addMessage(context, threadId, replyMsg)
                }
            }
        }
    }

    fun onScreenTapped() {
        screenPermissionLauncher.launch(screenCaptureHelper.createCaptureIntent(mediaProjectionManager))
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "VINCE",
                fontSize = 20.sp,
                color = MaterialTheme.colorScheme.primary
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { speakReplies = !speakReplies }) {
                    Text(if (speakReplies) "Voice: On" else "Voice: Off")
                }
                TextButton(onClick = onBack) {
                    Text("Chats")
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { msg ->
                val label = if (msg.fromUser) "You" else "VINCE"
                val color = if (msg.fromUser)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.primary

                Column {
                    Text(text = label, fontSize = 12.sp, color = color.copy(alpha = 0.6f))
                    Text(text = msg.text, color = color)
                }
            }

            if (sending) {
                item {
                    Text(
                        text = "VINCE is thinking...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message VINCE...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { onMicTapped() }, enabled = !sending) {
                Text("Mic")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { onCameraTapped() }, enabled = !sending) {
                Text("Cam")
            }
            Spacer(modifier = Modifier.width(8.dp))
            OutlinedButton(onClick = { onScreenTapped() }, enabled = !sending) {
                Text("Screen")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(onClick = { send() }, enabled = !sending) {
                Text("Send")
            }
        }
    }
}
