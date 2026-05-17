package id.fahrul.sanel

data class ChatMessage(
    val id: Long = System.nanoTime(),
    val role: String,
    val content: String = "",
    val isStreaming: Boolean = false,
    val isCommand: Boolean = false,
    val commandLabel: String = "",
    val commandOutput: String = "",
    val commandExpanded: Boolean = false,
    val toolCallId: String = "",
    val isToolCall: Boolean = false,
    val toolCallName: String = "",
    val toolCallArgs: String = ""
)
