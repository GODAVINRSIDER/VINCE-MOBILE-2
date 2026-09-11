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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
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

// Stage 10 - real chart-analysis prompt: a quick one-line verdict up
// front (trend / key level type / rough probability), THEN the detailed
// interactive breakdown - matches Vincent's actual price-action/SMC
// trading approach and how he wants to skim it at a glance first. Used
// as the default whenever a photo/screen capture is sent with no typed
// question; a typed question always overrides this. This text is sent
// to the AI but never shown in the chat bubble itself (see
// sendBitmapForAnalysis below) - only what Vincent actually typed shows.
private const val DEFAULT_CHART_PROMPT = "You're looking at a trading chart for an " +
    "experienced price-action/smart-money-concepts trader. Start with ONE quick summary " +
    "line in this exact style: 'This chart on this [timeframe if visible] is in a " +
    "[downtrend/uptrend/range]; key level spotted: [FVG/order block/support/resistance/" +
    "breakout-retest/etc]; roughly [XX-YY]% probability for a [buy/sell] position.' Then, " +
    "on a new line, give a focused interactive breakdown: the key support/resistance " +
    "levels or liquidity zones visible, notable structure (order blocks, fair value gaps, " +
    "trendlines, break of structure), and your honest thoughts on what the chart is " +
    "suggesting. Be direct and specific like a second pair of eyes on the chart, not a " +
    "generic disclaimer-heavy description."

@Composable
fun ChatScreen(threadId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(true) }

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
            // Stage 13 - real-time (price/time/news) checked first, then
            // memory/reminder commands, then fall through to the AI.
            val localReply = RealTimeTools.handleLocalCommand(context, text)
                ?: PersonalTools.handleLocalCommand(context, text)
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

    var pendingPhotoUri by remember { mutableStateOf<Uri?>(null) }

    // Shared by both camera photos and screen captures. `question` is
    // the FULL prompt actually sent to the AI (default or typed).
    // `typedByUser` is what Vincent actually typed, or null if he left
    // it blank and the default prompt was used - the chat bubble only
    // ever shows typedByUser, never the full default instruction text,
    // so nothing internal leaks into the visible conversation.
    fun sendBitmapForAnalysis(bitmap: Bitmap, question: String, kind: String, typedByUser: String?) {
        if (sending) return

        val displayText = if (typedByUser.isNullOrBlank()) "[$kind]" else "[$kind] $typedByUser"
        val userMsg = ChatMessage(fromUser = true, text = displayText)
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
                    onFailure = { e -> "Couldn't analyze the $kind. (${e.message})" }
                )
            } catch (e: Exception) {
                "Couldn't process the $kind. (${e.message})"
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

    fun sendImage(uri: Uri, question: String, typedByUser: String?) {
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
                val displayText = if (typedByUser.isNullOrBlank()) "[Photo]" else "[Photo] $typedByUser"
                val userMsg = ChatMessage(fromUser = true, text = displayText)
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
                sending = false
                sendBitmapForAnalysis(bitmap, question, "Photo", typedByUser)
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture()
    ) { success ->
        val uri = pendingPhotoUri
        if (success && uri != null) {
            val typed = input.trim()
            val question = typed.ifBlank { DEFAULT_CHART_PROMPT }
            sendImage(uri, question, typed.ifBlank { null })
        }
    }

    fun onCameraTapped() {
        val dir = File(context.cacheDir, "camera_captures").apply { mkdirs() }
        val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "com.godavin.vince.fileprovider", file)
        pendingPhotoUri = uri
        cameraLauncher.launch(uri)
    }

    // Stage 10 - screen vision now runs through ScreenCaptureService (a
    // proper foreground service), not a bare in-Activity call - the
    // generalized, version-safe fix for the capture failure hit during
    // testing (see ScreenCaptureService's docstring for why).
    val mediaProjectionManager = remember {
        context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }
    val screenCaptureHelper = remember { ScreenCaptureHelper(context) }

    val screenPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            val typed = input.trim()
            val question = typed.ifBlank { DEFAULT_CHART_PROMPT }

            ScreenCaptureBridge.awaitCapture { bitmap ->
                if (bitmap != null) {
                    sendBitmapForAnalysis(bitmap, question, "Screen", typed.ifBlank { null })
                } else {
                    val displayText = if (typed.isBlank()) "[Screen]" else "[Screen] $typed"
                    val userMsg = ChatMessage(fromUser = true, text = displayText)
                    messages.add(userMsg)
                    ConversationStore.addMessage(context, threadId, userMsg)
                    val replyMsg = ChatMessage(
                        fromUser = false,
                        text = "Couldn't capture the screen. Try again - if it keeps " +
                            "failing, that's worth reporting exactly as it happens."
                    )
                    messages.add(replyMsg)
                    ConversationStore.addMessage(context, threadId, replyMsg)
                }
            }

            // Stage 11 fix - granting the permission returns control straight
            // back to VINCE, so capturing immediately just captures VINCE's
            // own screen, not whatever app was open before. Give the user a
            // moment's warning, then send VINCE to the background - this
            // naturally surfaces whatever app was behind it (TradingView,
            // WhatsApp, etc.) BEFORE the actual capture happens.
            android.widget.Toast.makeText(
                context,
                "Switch to what you want VINCE to read - capturing in 3 seconds",
                android.widget.Toast.LENGTH_SHORT
            ).show()

            scope.launch {
                kotlinx.coroutines.delay(3000)
                (context as? Activity)?.moveTaskToBack(true)
                kotlinx.coroutines.delay(400) // let the app-switch animation actually finish

                val serviceIntent = Intent(context, ScreenCaptureService::class.java).apply {
                    putExtra(ScreenCaptureService.EXTRA_RESULT_CODE, result.resultCode)
                    putExtra(ScreenCaptureService.EXTRA_RESULT_DATA, data)
                }
                ContextCompat.startForegroundService(context, serviceIntent)
            }
        }
    }

    fun onScreenTapped() {
        screenPermissionLauncher.launch(screenCaptureHelper.createCaptureIntent(mediaProjectionManager))
    }

    // Stage 13 - manual upload fallback. Lets Vincent screenshot himself
    // (Android's own screenshot gesture) and hand the image straight to
    // VINCE, sidestepping MediaProjection entirely - useful as a reliable
    // backup for any app/device combo where Screen vision still doesn't
    // cooperate. Uses Android's modern Photo Picker (PickVisualMedia),
    // which needs no storage/media permission at all - the system handles
    // access, VINCE only ever sees the one image actually picked. Same
    // "type a question first, tap the button" pattern as Cam/Screen.
    val uploadLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            val typed = input.trim()
            val question = typed.ifBlank { DEFAULT_CHART_PROMPT }
            val bitmap = try {
                context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) {
                null
            }
            if (bitmap != null) {
                sendBitmapForAnalysis(bitmap, question, "Image", typed.ifBlank { null })
            }
        }
    }

    fun onUploadTapped() {
        uploadLauncher.launch(
            androidx.activity.result.PickVisualMediaRequest(
                ActivityResultContracts.PickVisualMedia.ImageOnly
            )
        )
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

        // Stage 13 - button row is now horizontally scrollable, not just
        // evenly-spaced, so it can safely hold more buttons in the future
        // (this stage added Upload) without ever cramping or breaking on
        // a narrow screen again.
        Column(modifier = Modifier.padding(16.dp)) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Message VINCE...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(onClick = { onMicTapped() }, enabled = !sending) {
                    Text("🎤")
                }
                OutlinedButton(onClick = { onCameraTapped() }, enabled = !sending) {
                    Text("📷")
                }
                OutlinedButton(onClick = { onScreenTapped() }, enabled = !sending) {
                    Text("🖥")
                }
                OutlinedButton(onClick = { onUploadTapped() }, enabled = !sending) {
                    Text("🖼")
                }
                Button(onClick = { send() }, enabled = !sending) {
                    Text("Send")
                }
            }
        }
    }
}
