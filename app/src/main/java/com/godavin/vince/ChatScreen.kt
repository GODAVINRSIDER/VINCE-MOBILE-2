package com.godavin.vince

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
import kotlinx.coroutines.launch

@Composable
fun ChatScreen(threadId: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }

    // Loads this specific thread's saved history on first open - keyed on
    // threadId so switching threads via the sidebar re-loads correctly
    // instead of reusing whatever was in state from the previous thread.
    val messages = remember(threadId) {
        mutableStateListOf<ChatMessage>().apply {
            ConversationStore.getThread(context, threadId)?.messages?.let { addAll(it) }
        }
    }

    fun send() {
        val text = input.trim()
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
            if (messages.isNotEmpty()) {
                listState.animateScrollToItem(messages.size - 1)
            }
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
            TextButton(onClick = onBack) {
                Text("Chats")
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
            Button(onClick = { send() }, enabled = !sending) {
                Text("Send")
            }
        }
    }
}
