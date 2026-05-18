package id.fahrul.sanel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

class ChatViewModel : ViewModel() {

    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingUpdate = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val streamingUpdate: SharedFlow<String> = _streamingUpdate.asSharedFlow()

    private val _scrollToBottom = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val scrollToBottom: SharedFlow<Unit> = _scrollToBottom.asSharedFlow()

    private val _errorEvent = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorEvent: SharedFlow<String> = _errorEvent.asSharedFlow()

    private val messagesList = mutableListOf<ChatMessage>()
    private var streamingPosition = -1
    private val toolCallMutex = Mutex()

    // Guardrails
    private var loopCount = 0
    private var lastCommand = ""
    private var sameCommandRepeat = 0
    private var totalFailures = 0
    private val maxLoops = 30
    private val maxSameCommand = 3
    private val maxFailures = 5

    private var appContext: android.content.Context? = null
    private var autoTitleGenerated = false
    private var titleTimerStarted = false

    fun init(context: android.content.Context) {
        if (appContext == null) {
            appContext = context.applicationContext
        }
    }

    fun sendMessage(text: String) {
        if (text.isEmpty() || _isStreaming.value) return

        // Reset guardrails on user input
        loopCount = 0
        lastCommand = ""
        sameCommandRepeat = 0
        totalFailures = 0

        // Start 10-second timer for auto-title (first message only)
        if (!titleTimerStarted && messagesList.count { it.role == "user" } == 0) {
            titleTimerStarted = true
            viewModelScope.launch {
                kotlinx.coroutines.delay(10_000)
                if (!autoTitleGenerated) {
                    autoTitleGenerated = true
                    autoGenerateTitle()
                }
            }
        }

        appendChatMessage(ChatMessage(role = "user", content = text))
        startStreaming()
    }

    fun loadMessages(apiMessages: List<Map<String, String>>) {
        autoTitleGenerated = false
        titleTimerStarted = false
        messagesList.clear()
        streamingPosition = -1
        for (m in apiMessages) {
            val content = m["content"] ?: ""
            val role = m["role"] ?: "user"
            when {
                content.startsWith("TOOLCALL:") -> {
                    val raw = content.removePrefix("TOOLCALL:")
                    val paren = raw.indexOf('(')
                    if (paren >= 0) {
                        val name = raw.substring(0, paren)
                        val args = raw.substring(paren + 1, raw.length - 1)
                        messagesList.add(ChatMessage(
                            role = "assistant", isToolCall = true,
                            toolCallName = name, toolCallArgs = args,
                            toolCallId = "call_${System.nanoTime()}"
                        ))
                    }
                }
                content.startsWith("EXECUTE:") -> {
                    val raw = content.removePrefix("EXECUTE:")
                    val sep = raw.indexOf("|||")
                    val cmd = if (sep >= 0) raw.substring(0, sep) else raw
                    val rest = if (sep >= 0) raw.substring(sep + 3) else ""
                    val labelSep = rest.lastIndexOf("|||")
                    val label = if (labelSep >= 0) rest.substring(labelSep + 3) else ""
                    val output = if (labelSep >= 0) rest.substring(0, labelSep) else rest
                    messagesList.add(ChatMessage(
                        role = "assistant", content = cmd, commandLabel = label,
                        isCommand = true, isStreaming = false, commandOutput = output
                    ))
                }
                else -> {
                    messagesList.add(ChatMessage(role = role, content = content))
                }
            }
        }
        _messages.value = messagesList.toList()
        _scrollToBottom.tryEmit(Unit)
    }

    fun getMessagesForSave(): List<Map<String, String>> {
        return messagesList.map { msg ->
            when {
                msg.isCommand -> mapOf("role" to "assistant", "content" to "EXECUTE:${msg.content}|||${msg.commandOutput}|||${msg.commandLabel}")
                msg.role == "tool" && msg.toolCallId.isNotEmpty() -> mapOf("role" to "tool", "content" to msg.content, "tool_call_id" to msg.toolCallId)
                msg.isToolCall -> mapOf("role" to msg.role, "content" to "TOOLCALL:${msg.toolCallName}(${msg.toolCallArgs})")
                else -> mapOf("role" to msg.role, "content" to msg.content)
            }
        }
    }

    fun saveConversation() {
        val msgs = getMessagesForSave()
        if (msgs.isNotEmpty()) {
            ConversationManager.save(msgs)
        }
    }

    private fun appendChatMessage(msg: ChatMessage) {
        messagesList.add(msg)
        _messages.value = messagesList.toList()
        _scrollToBottom.tryEmit(Unit)
    }

    private fun updateStreaming(text: String) {
        if (streamingPosition < 0 || streamingPosition >= messagesList.size) return
        val updated = messagesList[streamingPosition].copy(
            content = messagesList[streamingPosition].content + text
        )
        messagesList[streamingPosition] = updated
        _streamingUpdate.tryEmit(text)
    }

    private fun finishStreaming(): ChatMessage? {
        if (streamingPosition < 0 || streamingPosition >= messagesList.size) return null
        val finished = messagesList[streamingPosition].copy(isStreaming = false)
        messagesList[streamingPosition] = finished
        _messages.value = messagesList.toList()
        _scrollToBottom.tryEmit(Unit)
        streamingPosition = -1
        _isStreaming.value = false
        saveConversation()
        // Auto-generate title after enough conversation
        val userCount = messagesList.count { it.role == "user" }
        if (!autoTitleGenerated && userCount >= 3) {
            autoTitleGenerated = true
            autoGenerateTitle()
        }
        return finished
    }

    private fun updateItem(position: Int, msg: ChatMessage) {
        if (position < 0 || position >= messagesList.size) return
        messagesList[position] = msg
        _messages.value = messagesList.toList()
    }

    private fun getMessagesForApi(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        for (msg in messagesList) {
            when {
                msg.isToolCall -> result.add(mapOf(
                    "role" to "assistant", "content" to "",
                    "tool_calls" to listOf(mapOf(
                        "id" to msg.toolCallId, "type" to "function",
                        "function" to mapOf("name" to msg.toolCallName, "arguments" to msg.toolCallArgs)
                    ))
                ))
                msg.isCommand -> { /* skip display-only */ }
                msg.role == "tool" && msg.toolCallId.isNotEmpty() ->
                    result.add(mapOf("role" to "tool", "content" to msg.content, "tool_call_id" to msg.toolCallId))
                msg.role == "assistant" && msg.content.isEmpty() -> { /* skip empty */ }
                else -> result.add(mapOf("role" to msg.role, "content" to msg.content))
            }
        }
        return result
    }

    private fun startStreaming(forceToolChoice: Boolean = false) {
        val assistantMsg = ChatMessage(role = "assistant", isStreaming = true)
        streamingPosition = messagesList.size
        appendChatMessage(assistantMsg)
        _isStreaming.value = true

        val msgs = getMessagesForApi().toMutableList()
        val tools = ToolExecutor.buildTools()
        val ctx = appContext ?: return
        val systemPrompt = BuildInfo.getSystemPrompt(ctx)
        val convId = ConversationManager.getCurrentId()
        val rawLogPath = if (convId != null && convId > 0) {
            File(ctx.filesDir, "debug").also { it.mkdirs() }
            File(ctx.filesDir, "debug/${convId}_stream.jsonl").absolutePath
        } else null

        LLMClient.streamChat(
            messages = msgs,
            tools = tools,
            rawLogPath = rawLogPath,
            systemPrompt = systemPrompt,
            onToken = { token ->
                viewModelScope.launch { updateStreaming(token) }
            },
            onReasoningToken = { },
            onToolCall = { id, name, args ->
                viewModelScope.launch {
                    toolCallMutex.withLock { handleToolCall(id, name, args, ctx) }
                }
            },
            onDone = {
                viewModelScope.launch { finishStreaming() }
            },
            onError = { error ->
                viewModelScope.launch {
                    // Don't show error if stream was intentionally cancelled (backgrounded)
                    if (!error.contains("backgrounded")) {
                        updateStreaming("\n\n[Error: $error]")
                        _errorEvent.emit(error)
                    }
                    finishStreaming()
                }
            }
        )
    }

    private suspend fun handleToolCall(toolCallId: String, name: String, args: String, ctx: android.content.Context) {
        finishStreaming()

        // Guardrail: iteration cap
        loopCount++
        if (loopCount > maxLoops) {
            appendChatMessage(ChatMessage(
                role = "assistant", content = "🛑  Stopped after $maxLoops iterations."
            ))
            _isStreaming.value = false
            saveConversation()
            return
        }

        // Show the raw tool call
        appendChatMessage(ChatMessage(
            role = "assistant", isToolCall = true,
            toolCallName = name, toolCallArgs = args, toolCallId = toolCallId
        ))

        if (name == "execute_command") {
            try {
                val cmd = try {
                    val parsed = com.google.gson.Gson().fromJson(args, Map::class.java)
                    parsed["command"]?.toString() ?: ""
                } catch (_: Exception) { "" }
                val label = try {
                    val parsed = com.google.gson.Gson().fromJson(args, Map::class.java)
                    parsed["label"]?.toString()?.take(60)?.trim() ?: cmd.take(60)
                } catch (_: Exception) { cmd.take(60) }

                if (cmd.isNotEmpty()) {
                    // Same-command loop guardrail
                    if (cmd == lastCommand) sameCommandRepeat++ else sameCommandRepeat = 0
                    lastCommand = cmd

                    if (sameCommandRepeat >= maxSameCommand) {
                        sameCommandRepeat = 0
                        appendChatMessage(ChatMessage(
                            role = "tool",
                            content = "{\"error\":\"Blocked: same command repeated $maxSameCommand times\",\"guardrail\":\"same_command_repeat\",\"command\":\"$cmd\"}",
                            toolCallId = toolCallId
                        ))
                        saveConversation()
                        _isStreaming.value = false
                        startStreaming()
                        return
                    }

                    val execPos = messagesList.size
                    appendChatMessage(ChatMessage(
                        role = "assistant", content = cmd, commandLabel = label, isCommand = true, isStreaming = true
                    ))

                    val result = withContext(Dispatchers.IO) {
                        ToolExecutor.executeCommand(ctx, cmd)
                    }

                    updateItem(execPos, ChatMessage(
                        role = "assistant", content = cmd, commandLabel = label, isCommand = true,
                        isStreaming = false, commandOutput = result
                    ))

                    // Track failures
                    val exitCode = try {
                        com.google.gson.Gson().fromJson(result, Map::class.java)["exit_code"] as? Double
                    } catch (_: Exception) { null }
                    if (exitCode != null && exitCode != 0.0) totalFailures++

                    val enrichedResult = appendTermuxHint(result, cmd)

                    if (totalFailures >= maxFailures) {
                        totalFailures = 0
                        appendChatMessage(ChatMessage(
                            role = "tool",
                            content = "{\"error\":\"Blocked: $maxFailures commands failed\",\"guardrail\":\"max_failures\"}",
                            toolCallId = toolCallId
                        ))
                        saveConversation()
                        _isStreaming.value = false
                        startStreaming()
                        return
                    }

                    appendChatMessage(ChatMessage(
                        role = "tool", content = enrichedResult, toolCallId = toolCallId
                    ))
                }
            } catch (e: Exception) {
                appendChatMessage(ChatMessage(
                    role = "assistant", content = "❌  $name failed: ${e.message}",
                    isCommand = true, isStreaming = false, commandOutput = "Error: ${e.message}"
                ))
            }
        }

        saveConversation()
        _isStreaming.value = false
        startStreaming()
    }

    companion object {
        private val termuxHints = listOf(
            Triple(127, Regex("which: .*command not found"), "Use `command -v <name>` instead of `which` — `which` is not installed in Termux by default."),
            Triple(127, Regex("create-vite: not found|cva: not found"), "npx spawns `sh -c` internally. Install globally instead: `npm install -g create-vite && termux-fix-shebang \\\$(which create-vite)` or similar."),
            Triple(126, Regex("bad interpreter|/usr/bin/env"), "Termux has no `/usr/bin/env`. Fix with `termux-fix-shebang \\\$(which <name>)` or run via `node \\\$(npm root -g)/<pkg>/index.js`"),
            Triple(127, Regex("env:.*node.*: No such file or directory|node: not found"), "Node not found in PATH. Use full path: `/data/data/com.termux/files/usr/bin/node`"),
            Triple(0, Regex("Command timed out"), "Command exceeded timeout. Try simpler commands or increase timeout in the tool call."),
            Triple(127, Regex("npx.*not found|npm.*not found"), "npm/npx not found. Install: `pkg install nodejs`"),
        )

        private fun appendTermuxHint(result: String, command: String): String {
            try {
                val parsed = com.google.gson.Gson().fromJson(result, Map::class.java)
                val exitCode = (parsed["exit_code"] as? Double)?.toInt() ?: return result
                val stderr = (parsed["stderr"] as? String) ?: ""
                val stdout = (parsed["stdout"] as? String) ?: ""
                val combined = "$stderr\n$stdout"
                for ((code, pattern, hint) in termuxHints) {
                    if (exitCode == code && pattern.containsMatchIn(combined)) {
                        return result.trimEnd('}') + ",\"_hint\":\"$hint\"}"
                    }
                }
            } catch (_: Exception) {}
            return result
        }
    }

    private fun autoGenerateTitle() {
        val ctx = appContext ?: return
        // Take first 2 exchanges (4 messages) for context
        val contextMsgs = messagesList.take(4).joinToString(" | ") {
            when {
                it.role == "user" -> "User: ${it.content.take(100)}"
                it.isToolCall -> ""
                it.isCommand -> ""
                else -> "Assistant: ${it.content.take(100)}"
            }
        }.trimEnd(' ', '|', ' ', '|', ' ').trim()
        if (contextMsgs.isBlank()) {
            writeTitleDebug("skip: blank messages")
            return
        }

        viewModelScope.launch {
            try {
                val bodyJson = com.google.gson.Gson().toJson(mapOf(
                    "model" to SettingsManager.model,
                    "messages" to listOf(
                        mapOf("role" to "system", "content" to "Generate a short 3-5 word title for this conversation. Return ONLY the title — no sentences, no punctuation, no quotes. Example: [Writing a Blog Post, Python API Setup, Docker Debugging]"),
                        mapOf("role" to "user", "content" to contextMsgs)
                    ),
                    "max_tokens" to 30,
                    "stream" to false,
                    "thinking" to mapOf("type" to "disabled")
                ))

                writeTitleDebug("request: $bodyJson")

                val client = okhttp3.OkHttpClient.Builder()
                    .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
                    .build()

                val response = withContext(Dispatchers.IO) {
                    val req = okhttp3.Request.Builder()
                        .url(SettingsManager.endpoint)
                        .header("Authorization", "Bearer ${SettingsManager.apiKey}")
                        .header("Content-Type", "application/json")
                        .post(bodyJson.toRequestBody("application/json".toMediaType()))
                        .build()
                    client.newCall(req).execute()
                }

                val body = response.body?.string()
                writeTitleDebug("response code=${response.code} body=${body?.take(300)}")

                if (response.isSuccessful && body != null) {
                    val parsed = com.google.gson.Gson().fromJson(body, Map::class.java)
                    @Suppress("UNCHECKED_CAST")
                    val choices = parsed["choices"] as? List<Map<String, Any>>
                    val title = choices?.firstOrNull()
                        ?.let { it["message"] as? Map<String, Any> }
                        ?.let { it["content"] as? String }
                        ?.trim(' ', '"', '\'', '.', '\n')
                        ?.split("\\s+".toRegex())
                        ?.take(5)
                        ?.joinToString(" ")
                        ?.take(40)
                    writeTitleDebug("extracted title: $title")
                    if (!title.isNullOrBlank()) {
                        val convId = ConversationManager.getCurrentId()
                        writeTitleDebug("convId: $convId")
                        if (convId != null) {
                            ConversationManager.updateTitle(convId, title)
                            writeTitleDebug("title updated")
                        }
                    }
                }
            } catch (e: Exception) {
                writeTitleDebug("error: ${e::class.simpleName} msg=${e.message}")
            }
        }
    }

    private fun writeTitleDebug(msg: String) {
        try {
            val f = java.io.File(appContext?.filesDir, "debug/title_debug.log")
            f.parentFile?.mkdirs()
            f.appendText("${System.currentTimeMillis()}: $msg\n")
        } catch (_: Exception) {}
    }
}
