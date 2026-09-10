package com.godavin.vince

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
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
import kotlinx.coroutines.launch

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
            val reply = BrainRouter.sendMessage(context, text)
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
            Button(onClick = { send() }, enabled = !sending) {
                Text("Send")
            }
        }
    }
}
