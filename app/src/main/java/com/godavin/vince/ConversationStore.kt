package com.godavin.vince

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class ChatMessage(
    val fromUser: Boolean,
    val text: String,
    val timestamp: Long = System.currentTimeMillis()
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
                                timestamp = m.optLong("timestamp", 0L)
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

    fun deleteThread(context: Context, threadId: String) {
        val list = load(context)
        list.removeAll { it.id == threadId }
        persist(context)
    }
}
