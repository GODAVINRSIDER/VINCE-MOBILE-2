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
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
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
    "experienced price-action/smart-money-concepts trader. Give ONE quick summary line " +
    "in plain sentence form (no Markdown, no headers, no tables, no bullet dashes): the " +
    "timeframe if visible, whether it's in a downtrend/uptrend/range, the key level " +
    "spotted (FVG/order block/support/resistance/breakout-retest/etc), and a rough buy/" +
    "sell probability. Then ask if the user wants the fuller breakdown (support/" +
    "resistance zones, structure, your honest read) rather than dumping all of it by " +
    "default - keep it conversational and skimmable, like a second pair of eyes glancing " +
    "at the chart, not a formatted report."

@Composable
fun ChatScreen(threadId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var speakReplies by remember { mutableStateOf(true) }
    // Stage 14 - persona switching. Loaded from PersonaState on open so
    // it's remembered across app restarts/thread switches, not reset to
    // VINCE every time.
    var activePersona by remember { mutableStateOf(PersonaState.getActive(context)) }

    fun switchPersona(persona: Persona) {
        activePersona = persona
        PersonaState.setActive(context, persona)
    }

    val messages = remember(threadId) {
        mutableStateListOf<ChatMessage>().apply {
            ConversationStore.getThread(context, threadId)?.messages?.let { addAll(it) }
        }
    }

    // Fix - explicit chat rename command. Checked before the normal
    // local-command-then-AI pipeline, same "deterministic first"
    // discipline as everywhere else - "call this chat X" is a direct
    // instruction, not something to hand to the AI to interpret.
    val RENAME_PATTERNS = listOf(
        Regex("^(?:rename|call|title|name) this chat (?:to |as )?(.+)$", RegexOption.IGNORE_CASE),
        Regex("^(?:rename|call|title|name) this conversation (?:to |as )?(.+)$", RegexOption.IGNORE_CASE)
    )

    fun send(overrideText: String? = null) {
        val text = (overrideText ?: input).trim()
        if (text.isEmpty() || sending) return

        for (pattern in RENAME_PATTERNS) {
            val match = pattern.find(text)
            if (match != null) {
                val newTitle = match.groupValues[1].trim().trim('"', '\'')
                if (newTitle.isNotBlank()) {
                    ConversationStore.renameThread(context, threadId, newTitle)
                    input = ""
                    val userMsg = ChatMessage(fromUser = true, text = text)
                    val replyMsg = ChatMessage(
                        fromUser = false,
                        text = "Got it, this chat is now called \"$newTitle\".",
                        persona = activePersona.name
                    )
                    messages.add(userMsg)
                    messages.add(replyMsg)
                    ConversationStore.addMessage(context, threadId, userMsg)
                    ConversationStore.addMessage(context, threadId, replyMsg)
                    return
                }
            }
        }

        // Stage 15 fix - snapshot the thread's history BEFORE adding this
        // new user message, so BrainRouter gets everything said so far
        // without double-counting the message being sent right now.
        val historySnapshot = messages.toList()
        val isFirstExchange = historySnapshot.isEmpty()

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
            val reply = localReply ?: BrainRouter.sendMessage(context, text, activePersona, historySnapshot)
            val replyMsg = ChatMessage(fromUser = false, text = reply, persona = activePersona.name)
            messages.add(replyMsg)
            ConversationStore.addMessage(context, threadId, replyMsg)
            ActivityLog.addEvent(context, "Message with ${activePersona.displayName}")
            sending = false
            if (speakReplies) {
                VoiceOutput.speak(reply, activePersona)
            }
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }

            // Fix - auto-title this thread from a real summary of what
            // was actually said, instead of just truncating the first
            // sentence typed. Fired after the reply so it never delays
            // the reply itself; only runs on a thread's genuine first
            // exchange, and only overwrites the auto "New chat" default
            // (a manually-set or already-summarized title is never
            // touched by this).
            if (isFirstExchange) {
                val currentTitle = ConversationStore.getThread(context, threadId)?.title
                if (currentTitle == "New chat" || currentTitle == text.take(40)) {
                    TitleGenerator.generateTitle(context, text, reply)?.let { summary ->
                        ConversationStore.renameThread(context, threadId, summary)
                    }
                }
            }
        }
    }

    // Fix - the in-chat mic was still launching Google's own floating
    // "Speak now" popup (RecognizerIntent as an Activity), unlike the
    // Home mic and the floating widget's mic, both already fixed to use
    // SpeechRecognizer directly with zero system UI. Matches that same
    // treatment here: no popup, just a colored halo around the mic
    // button (see the action row below) while actively listening.
    var isListening by remember { mutableStateOf(false) }

    fun startListening() {
        if (!android.speech.SpeechRecognizer.isRecognitionAvailable(context)) {
            return
        }
        isListening = true
        val recognizer = android.speech.SpeechRecognizer.createSpeechRecognizer(context)
        recognizer.setRecognitionListener(object : android.speech.RecognitionListener {
            override fun onResults(results: android.os.Bundle?) {
                isListening = false
                val spoken = results
                    ?.getStringArrayList(android.speech.SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!spoken.isNullOrBlank()) send(overrideText = spoken)
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
        if (granted) {
            startListening()
        }
    }

    fun onMicTapped() {
        val hasPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        if (hasPermission) {
            startListening()
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
                val geminiKey = ApiKeyStore.getKey(context, Provider.GEMINI)
                val groqKey = ApiKeyStore.getKey(context, Provider.GROQ)
                val openRouterKey = ApiKeyStore.getKey(context, Provider.OPENROUTER)
                val result = VisionRouter.describeImage(geminiKey, groqKey, openRouterKey, baos.toByteArray(), question)
                result.fold(
                    onSuccess = { it },
                    onFailure = { e -> "Couldn't analyze the $kind. (${e.message})" }
                )
            } catch (e: Exception) {
                "Couldn't process the $kind. (${e.message})"
            }

            val replyMsg = ChatMessage(fromUser = false, text = reply, persona = activePersona.name)
            messages.add(replyMsg)
            ConversationStore.addMessage(context, threadId, replyMsg)
            ActivityLog.addEvent(context, "$kind analyzed")
            sending = false
            if (speakReplies) {
                VoiceOutput.speak(reply, activePersona)
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
                text = activePersona.displayName,
                fontSize = 20.sp,
                color = activePersona.color()
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

        // Stage 15 fix - persona tabs redesigned as bordered glowing boxes
        // with an icon above the label (brain/heart/lotus), matching the
        // reference chat layout, instead of plain outlined pill buttons.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Persona.values().forEach { persona ->
                val isActive = persona == activePersona
                Column(
                    modifier = Modifier
                        .width(92.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .border(
                            width = if (isActive) 1.5.dp else 1.dp,
                            color = persona.color().copy(alpha = if (isActive) 1f else 0.35f),
                            shape = RoundedCornerShape(14.dp)
                        )
                        .background(persona.color().copy(alpha = if (isActive) 0.16f else 0.04f))
                        .clickable { switchPersona(persona) }
                        .padding(vertical = 10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Image(
                        painter = painterResource(id = personaIconRes(persona)),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(persona.color()),
                        modifier = Modifier.size(26.dp)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(persona.displayName, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = persona.color())
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Box(modifier = Modifier.weight(1f)) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
            items(messages) { msg ->
                val msgPersona = Persona.fromName(msg.persona)
                val color = if (msg.fromUser) MaterialTheme.colorScheme.onSurface else msgPersona.color()
                val timeLabel = remember(msg.timestamp) {
                    java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(msg.timestamp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color(0xFF0D0D0F))
                        .border(1.dp, color.copy(alpha = 0.25f), RoundedCornerShape(14.dp))
                        .padding(12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!msg.fromUser) {
                            Box(
                                modifier = Modifier
                                    .size(26.dp)
                                    .clip(CircleShape)
                                    .background(color.copy(alpha = 0.15f))
                                    .border(1.dp, color.copy(alpha = 0.5f), CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                Image(
                                    painter = painterResource(id = personaIconRes(msgPersona)),
                                    contentDescription = null,
                                    colorFilter = ColorFilter.tint(color),
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            if (msg.fromUser) "You" else msgPersona.displayName,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = color
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Text(timeLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(msg.text, fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f))
                }
            }

            if (sending) {
                item {
                    Text(
                        text = "${activePersona.displayName} is thinking...",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
            }
            }

            // Fix - jump-to-bottom button, so getting to the latest
            // message doesn't mean scrolling all the way down by hand.
            // Only shown once scrolled away from the bottom.
            val isAtBottom by remember {
                derivedStateOf {
                    val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
                    lastVisible >= messages.size - 1
                }
            }
            if (!isAtBottom && messages.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 8.dp)
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(activePersona.color())
                        .clickable {
                            scope.launch { listState.animateScrollToItem(messages.size - 1) }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u2193", fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Stage 15 - quick-action suggestion chips, same spirit as the
        // reference layout's conversation-starter pills. Tapping one
        // sends that exact phrase through the normal send() pipeline -
        // shortcuts, not decoration.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            SuggestionChip("Check the weather") { send(overrideText = "What's the weather like right now?") }
            SuggestionChip("Search the web") { input = "Search the web for " }
            SuggestionChip("Open an app") { input = "Open " }
            SuggestionChip("Help with something") { send(overrideText = "What can you help me with?") }
        }

        Spacer(modifier = Modifier.height(8.dp))


        // Fix - input field was singleLine, so as you typed a longer
        // message it just scrolled horizontally within one line (earlier
        // text sliding out of view) instead of wrapping - now grows
        // vertically like a normal chat app, up to a reasonable cap so
        // it can't swallow the whole screen.
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp, max = 150.dp),
                    placeholder = { Text("Message ${activePersona.displayName}...") },
                    maxLines = 6,
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (sending) activePersona.color().copy(alpha = 0.4f) else activePersona.color())
                        .clickable(enabled = !sending) { send() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("\u2192", fontSize = 22.sp, color = Color.Black, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(modifier = Modifier.height(10.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ChatActionButton(R.drawable.ic_action_mic, "Voice", activePersona.color(), enabled = !sending, highlighted = isListening) { onMicTapped() }
                ChatActionButton(R.drawable.ic_action_camera, "Camera", activePersona.color(), enabled = !sending) { onCameraTapped() }
                ChatActionButton(R.drawable.ic_action_screen, "Screen", activePersona.color(), enabled = !sending) { onScreenTapped() }
                ChatActionButton(R.drawable.ic_action_image, "Files", activePersona.color(), enabled = !sending) { onUploadTapped() }
            }
        }
    }
}

/** Maps a persona to its icon (brain/heart/lotus) - shared by the
 * persona tabs and each message's avatar. */
private fun personaIconRes(persona: Persona): Int = when (persona) {
    Persona.VINCE -> R.drawable.ic_persona_vince
    Persona.CLARA -> R.drawable.ic_persona_clara
    Persona.DAVINA -> R.drawable.ic_persona_davina
}

@Composable
private fun SuggestionChip(label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .border(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.08f))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.primary)
    }
}

/**
 * Stage 15 fix (v2) - action buttons now show a label under the icon in
 * a bordered rounded-rect box, matching the reference layout, instead of
 * icon-only circles.
 */
@Composable
private fun ChatActionButton(
    iconRes: Int,
    label: String,
    tint: Color,
    enabled: Boolean,
    highlighted: Boolean = false,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .border(
                width = if (highlighted) 2.dp else 1.dp,
                color = if (highlighted) tint else tint.copy(alpha = if (enabled) 0.4f else 0.15f),
                shape = RoundedCornerShape(12.dp)
            )
            .background(tint.copy(alpha = if (highlighted) 0.28f else if (enabled) 0.1f else 0.03f))
            .clickable(enabled = enabled) { onClick() }
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(
            painter = painterResource(id = iconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(if (enabled) tint else tint.copy(alpha = 0.4f)),
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            if (highlighted) "Listening..." else label,
            fontSize = 10.sp,
            color = if (enabled) tint else tint.copy(alpha = 0.4f)
        )
    }
}
