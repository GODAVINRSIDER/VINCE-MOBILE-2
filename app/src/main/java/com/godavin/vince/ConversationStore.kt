package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis(),
    // Stage 14 - which persona sent this reply (ignored for user messages).
    // Defaults to VINCE for backward compatibility with threads saved
    // before persona switching existed.
    val persona: String = "VINCE",
    // Fix - stable per-message id, needed for swipe-to-reply to point at
    // a specific earlier message. Old stored messages (saved before this
    // existed) get a freshly generated one on load - fine, since nothing
    // needed to reference them by id before now.
    val id: String = UUID.randomUUID().toString(),
    // Fix - persisted absolute file path to an image attached to this
    // message (see ImageStore.kt), so the actual photo/screenshot stays
    // visible in the chat and survives app restarts, instead of only a
    // "[Photo]" placeholder with the image itself gone forever. Null for
    // ordinary text messages and for messages saved before this existed.
    val imagePath: String? = null,
    // Fix - swipe-to-reply. Set when this message was sent as an
    // explicit reply to an earlier one - replyToPreview is a short
    // denormalized snippet of what was replied to, stored directly so
    // rendering never needs to look the original message back up.
    val replyToId: String? = null,
    val replyToPreview: String? = null
)

data class ChatThread(
    val id: String,
    var title: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    var updatedAt: Long = System.currentTimeMillis()
)

/**
 * Stage 3 - multiple separate chat threads, each with its own saved
 * history, surviving app relaunch. Stored as one plain JSON file in the
 * app's private internal storage - no PC, no cloud, nothing leaves the
 * device. A thread isn't written to disk until its first real message is
 * sent, so tapping "New chat" and backing out without typing anything
 * doesn't clutter the sidebar with empty threads.
 */
object ConversationStore {
    private const val FILE_NAME = "vince_conversations.json"
    private var cache: MutableList<ChatThread>? = null

    private fun file(context: Context) = File(context.filesDir, FILE_NAME)

    private fun load(context: Context): MutableList<ChatThread> {
        cache?.let { return it }
        val list = mutableListOf<ChatThread>()
        val f = file(context)
        if (f.exists()) {
            try {
                val root = JSONArray(f.readText())
                for (i in 0 until root.length()) {
                    val obj = root.getJSONObject(i)
                    val msgs = mutableListOf<ChatMessage>()
                    val msgArr = obj.getJSONArray("messages")
                    for (j in 0 until msgArr.length()) {
                        val m = msgArr.getJSONObject(j)
                        msgs.add(
                            ChatMessage(
                                fromUser = m.getBoolean("fromUser"),
                                text = m.getString("text"),
                                timestamp = m.optLong("timestamp", 0L),
                                persona = m.optString("persona", "VINCE"),
                                id = m.optString("id").ifBlank { UUID.randomUUID().toString() },
                                imagePath = if (m.has("imagePath") && !m.isNull("imagePath")) m.optString("imagePath") else null,
                                replyToId = if (m.has("replyToId") && !m.isNull("replyToId")) m.optString("replyToId") else null,
                                replyToPreview = if (m.has("replyToPreview") && !m.isNull("replyToPreview")) m.optString("replyToPreview") else null
                            )
                        )
                    }
                    list.add(
                        ChatThread(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            messages = msgs,
                            updatedAt = obj.optLong("updatedAt", 0L)
                        )
                    )
                }
            } catch (e: Exception) {
                // Corrupt or unreadable file - start fresh rather than crash the app.
            }
        }
        cache = list
        return list
    }

    private fun persist(context: Context) {
        val list = cache ?: return
        val root = JSONArray()
        for (thread in list) {
            val obj = JSONObject()
            obj.put("id", thread.id)
            obj.put("title", thread.title)
            obj.put("updatedAt", thread.updatedAt)
            val msgArr = JSONArray()
            for (m in thread.messages) {
                val mo = JSONObject()
                mo.put("fromUser", m.fromUser)
                mo.put("text", m.text)
                mo.put("timestamp", m.timestamp)
                mo.put("persona", m.persona)
                mo.put("id", m.id)
                mo.put("imagePath", m.imagePath)
                mo.put("replyToId", m.replyToId)
                mo.put("replyToPreview", m.replyToPreview)
                msgArr.put(mo)
            }
            obj.put("messages", msgArr)
            root.put(obj)
        }
        file(context).writeText(root.toString())
    }

    /** Most recently updated thread first, so the sidebar reads like Claude's. */
    fun getAllThreads(context: Context): List<ChatThread> {
        return load(context).sortedByDescending { it.updatedAt }
    }

    fun getThread(context: Context, id: String): ChatThread? {
        return load(context).find { it.id == id }
    }

    /** Appends a message, auto-creating the thread on first use, and
     * titles the thread from the first user message. */
    fun addMessage(context: Context, threadId: String, message: ChatMessage) {
        val list = load(context)
        var thread = list.find { it.id == threadId }
        if (thread == null) {
            thread = ChatThread(id = threadId, title = "New chat")
            list.add(thread)
        }
        thread.messages.add(message)
        thread.updatedAt = System.currentTimeMillis()
        if (message.fromUser && thread.title == "New chat") {
            thread.title = message.text.take(40)
        }
        persist(context)
    }

    /** Ensures a thread exists with a fixed, human-readable title -
     * used for reserved fixed-id threads (like the Home mic's dedicated
     * Voice Chat thread) where we want a real title from the start
     * instead of letting it auto-title from whatever's first said.
     * No-ops if the thread already exists (never clobbers a real title). */
    fun ensureThread(context: Context, threadId: String, title: String) {
        val list = load(context)
        if (list.none { it.id == threadId }) {
            list.add(ChatThread(id = threadId, title = title))
            persist(context)
        }
    }

    fun deleteThread(context: Context, threadId: String) {
        val list = load(context)
        list.removeAll { it.id == threadId }
        persist(context)
    }

    /** Sets a thread's title directly - used both by the "call this chat
     * X" voice/typed command and by the auto-generated summary title
     * after a thread's first exchange. */
    fun renameThread(context: Context, threadId: String, newTitle: String) {
        val list = load(context)
        val thread = list.find { it.id == threadId } ?: return
        thread.title = newTitle.take(60)
        persist(context)
    }
}
