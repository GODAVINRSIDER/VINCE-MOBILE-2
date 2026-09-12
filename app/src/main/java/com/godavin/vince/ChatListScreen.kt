package com.godavin.vince

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID

@Composable
fun ChatListScreen(onOpenThread: (String) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val threads = remember { mutableStateOf(ConversationStore.getAllThreads(context)) }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Chats",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onBack) {
                Text("Home")
            }
        }

        Button(
            onClick = { onOpenThread(UUID.randomUUID().toString()) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("+ New chat")
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (threads.value.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No chats yet - start one above.",
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize()
            ) {
                items(threads.value) { thread ->
                    val lastMessage = thread.messages.lastOrNull()?.text?.take(60) ?: ""
                    // Stage 14 fix - show which persona last replied in this
                    // thread beside the title, so the list doesn't look
                    // "mixed up" when different threads used different
                    // personas.
                    val lastAiPersona = thread.messages
                        .lastOrNull { !it.fromUser }
                        ?.persona
                        ?.let { Persona.fromName(it) }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onOpenThread(thread.id) }
                            .padding(horizontal = 16.dp, vertical = 12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = thread.title,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 15.sp,
                                modifier = Modifier.weight(1f)
                            )
                            if (lastAiPersona != null) {
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = lastAiPersona.displayName,
                                    color = lastAiPersona.color(),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (lastMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = lastMessage,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                                fontSize = 12.sp
                            )
                        }
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        }
    }
}
