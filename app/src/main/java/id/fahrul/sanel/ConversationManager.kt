package id.fahrul.sanel

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

data class Conversation(
    val id: Long = System.currentTimeMillis(),
    val title: String = "New Chat",
    val timestamp: Long = System.currentTimeMillis(),
    val messages: List<Map<String, String>> = emptyList()
)

object ConversationManager {
    private const val PREFS_KEY = "conversations"
    private lateinit var prefs: SharedPreferences
    private val gson = Gson()
    private val conversations = mutableListOf<Conversation>()
    private var pendingLoadId: Long? = null
    private var currentConversationId: Long? = null

    var onDataChanged: (() -> Unit)? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("sanel_conv", Context.MODE_PRIVATE)
        load()
    }

    fun getAll(): List<Conversation> = conversations.toList()

    fun save(messages: List<Map<String, String>>): Conversation {
        if (messages.isEmpty()) return Conversation()

        // Use existing conversation ID or create a new one
        val convId = currentConversationId ?: System.currentTimeMillis()
        currentConversationId = convId

        val existingIdx = conversations.indexOfFirst { it.id == convId }
        val title = if (existingIdx >= 0) {
            // Preserve existing title (may have been auto-generated)
            conversations[existingIdx].title
        } else {
            // New conversation — use first user message as initial title
            messages.firstOrNull { it["role"] == "user" }
                ?.get("content")?.take(50)?.trim() ?: "New Chat"
        }
        val conv = Conversation(
            id = convId,
            title = title,
            messages = messages
        )

        if (existingIdx >= 0) {
            conversations[existingIdx] = conversations[existingIdx].copy(
                title = title, messages = messages, timestamp = conv.timestamp
            )
        } else {
            conversations.add(0, conv)
        }
        persist()
        return conv
    }

    fun delete(id: Long) {
        conversations.removeAll { it.id == id }
        if (currentConversationId == id) currentConversationId = null
        persist()
    }

    fun setPendingLoad(id: Long) {
        pendingLoadId = id
        currentConversationId = id
    }

    fun consumePendingLoad(): Conversation? {
        val id = pendingLoadId ?: return null
        pendingLoadId = null
        val conv = conversations.find { it.id == id }
        if (conv != null) currentConversationId = id
        return conv
    }

    fun resetCurrentConversation() {
        currentConversationId = null
    }

    fun getCurrentId(): Long? = currentConversationId

    fun updateTitle(id: Long, title: String) {
        val idx = conversations.indexOfFirst { it.id == id }
        if (idx >= 0) {
            conversations[idx] = conversations[idx].copy(title = title)
            persist()
            onDataChanged?.invoke()
        }
    }

    private fun load() {
        val json = prefs.getString(PREFS_KEY, "[]") ?: "[]"
        val type = object : TypeToken<List<Conversation>>() {}.type
        val arr: List<Conversation> = gson.fromJson(json, type) ?: emptyList()
        conversations.clear()
        conversations.addAll(arr)
    }

    private fun persist() {
        prefs.edit().putString(PREFS_KEY, gson.toJson(conversations)).apply()
    }
}
