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
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

@Composable
fun ChatListScreen(onOpenThread: (String, String?) -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val threads = remember { mutableStateOf(ConversationStore.getAllThreads(context)) }
    // Delete-thread confirmation - holds the thread awaiting a Yes/No
    // before anything is actually removed, so a stray tap can't wipe a
    // conversation by accident.
    var pendingDelete by remember { mutableStateOf<ChatThread?>(null) }

    // v2.8 - search across every chat (see ChatSearch)
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<SearchHit>>(emptyList()) }
    var searching by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    LaunchedEffect(query) {
        if (query.isBlank()) {
            hits = emptyList(); searching = false
        } else {
            searching = true
            delay(180)                       // wait for a pause in typing
            hits = withContext(Dispatchers.Default) { ChatSearch.search(context, query) }
            searching = false
        }
    }

    pendingDelete?.let { thread ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete this chat?") },
            text = { Text("\"${thread.title}\" will be permanently deleted. This can't be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    ConversationStore.deleteThread(context, thread.id)
                    threads.value = ConversationStore.getAllThreads(context)
                    pendingDelete = null
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) {
                    Text("Cancel")
                }
            }
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
                text = "Chats",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            TextButton(onClick = onBack) {
                Text("Home")
            }
        }

        // v2.8 - search box, directly above "New chat"
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            placeholder = { Text("Search all chats", fontSize = 14.sp) },
            leadingIcon = { Text("\uD83D\uDD0D", fontSize = 16.sp) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    Text(
                        "\u2715", fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                        modifier = Modifier
                            .clip(RoundedCornerShape(20.dp))
                            .clickable { query = "" }
                            .padding(12.dp)
                    )
                }
            },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() })
        )
        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = { onOpenThread(UUID.randomUUID().toString(), null) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            Text("+ New chat")
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (query.isNotBlank()) {
            val toks = remember(query) { ChatSearch.tokens(query) }
            Text(
                text = when {
                    searching -> "Searching..."
                    hits.isEmpty() -> "No chats match \"${query.trim()}\"."
                    hits.size == 1 -> "1 match"
                    else -> "${hits.size} matches"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                items(hits, key = { (it.message?.id ?: it.threadId) + "|" + it.timestamp }) { hit ->
                    val msg = hit.message
                    val hitPersona = msg?.takeIf { !it.fromUser }?.let { Persona.fromName(it.persona) }
                    val accent = hitPersona?.color() ?: MaterialTheme.colorScheme.primary
                    val whenLabel = remember(hit.timestamp) {
                        java.text.SimpleDateFormat("d MMM, h:mm a", java.util.Locale.getDefault()).format(java.util.Date(hit.timestamp))
                    }
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                focus.clearFocus()
                                onOpenThread(hit.threadId, msg?.id)
                            }
                            .padding(horizontal = 16.dp, vertical = 10.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = hit.threadTitle,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = if (msg == null) "chat title" else if (msg.fromUser) "You" else (hitPersona?.displayName ?: ""),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (msg == null || msg.fromUser) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f) else accent
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(whenLabel, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f))
                        }
                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = highlightMatches(hit.snippet, toks, accent),
                            fontSize = 13.sp,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f)
                        )
                    }
                    Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        } else if (threads.value.isEmpty()) {
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
                items(threads.value, key = { it.id }) { thread ->
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
                            .clickable { onOpenThread(thread.id, null) }
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
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Delete",
                                color = MaterialTheme.colorScheme.error.copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                modifier = Modifier.clickable { pendingDelete = thread }
                            )
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

/** Marks every occurrence of every searched word in [text] (case-insensitive) with the hit's accent colour. */
private fun highlightMatches(text: String, tokens: List<String>, accent: androidx.compose.ui.graphics.Color): AnnotatedString {
    val low = text.lowercase()
    val marks = BooleanArray(text.length)
    for (t in tokens) {
        if (t.isEmpty()) continue
        var i = low.indexOf(t)
        while (i >= 0) {
            for (k in i until minOf(i + t.length, marks.size)) marks[k] = true
            i = low.indexOf(t, i + t.length)
        }
    }
    return buildAnnotatedString {
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && marks[j] == marks[i]) j++
            if (marks[i]) {
                withStyle(SpanStyle(background = accent.copy(alpha = 0.35f), fontWeight = FontWeight.Bold, color = androidx.compose.ui.graphics.Color.White)) {
                    append(text.substring(i, j))
                }
            } else {
                append(text.substring(i, j))
            }
            i = j
        }
    }
}
