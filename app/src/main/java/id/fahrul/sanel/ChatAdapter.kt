package id.fahrul.sanel

import android.animation.ValueAnimator
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import io.noties.markwon.Markwon
import kotlin.math.abs
import kotlin.math.sin
import java.lang.ref.WeakReference

class ChatAdapter(
    private val onToggleCommand: (position: Int) -> Unit = {}
) : RecyclerView.Adapter<ChatAdapter.ViewHolder>() {

    private val messages = mutableListOf<ChatMessage>()
    private var streamingPosition: Int = -1
    private var streamingViewHolder: WeakReference<ViewHolder>? = null
    private var markwon: Markwon? = null

    override fun getItemCount(): Int = messages.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        if (markwon == null) {
            markwon = Markwon.create(parent.context)
        }
        return ViewHolder(view, this)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (position == streamingPosition) {
            streamingViewHolder = WeakReference(holder)
        }
        holder.bind(messages[position], position)
    }

    override fun onViewRecycled(holder: ViewHolder) {
        super.onViewRecycled(holder)
        holder.stopDotAnimation()
    }

    fun appendMessage(msg: ChatMessage) {
        streamingPosition = if (msg.isStreaming) messages.size else -1
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateStreaming(text: String) {
        if (streamingPosition < 0 || streamingPosition >= messages.size) return
        messages[streamingPosition] = messages[streamingPosition].copy(content = messages[streamingPosition].content + text)

        val vh = streamingViewHolder?.get()
        if (vh != null && vh.bindingAdapterPosition == streamingPosition) {
            vh.setAssistantText(messages[streamingPosition].content)
        }
    }

    fun updateStreamingDirect(text: String) {
        if (streamingPosition < 0 || streamingPosition >= messages.size) return
        messages[streamingPosition] = messages[streamingPosition].copy(content = messages[streamingPosition].content + text)

        val vh = streamingViewHolder?.get()
        if (vh != null && vh.bindingAdapterPosition == streamingPosition) {
            vh.setAssistantText(messages[streamingPosition].content)
        }
    }

    fun finishStreaming(): ChatMessage? {
        if (streamingPosition < 0 || streamingPosition >= messages.size) return null
        val finished = messages[streamingPosition].copy(isStreaming = false)
        messages[streamingPosition] = finished
        streamingViewHolder = null
        notifyItemChanged(streamingPosition)
        streamingPosition = -1
        return finished
    }

    fun updateItem(position: Int, msg: ChatMessage) {
        if (position < 0 || position >= messages.size) return
        messages[position] = msg
        notifyItemChanged(position)
    }

    fun getMessagesForApi(): List<Map<String, Any>> {
        val result = mutableListOf<Map<String, Any>>()
        for (msg in messages) {
            if (msg.isToolCall) {
                // Emit proper OpenAI tool_calls structure
                result.add(mapOf(
                    "role" to "assistant",
                    "content" to "",
                    "tool_calls" to listOf(mapOf(
                        "id" to msg.toolCallId,
                        "type" to "function",
                        "function" to mapOf(
                            "name" to msg.toolCallName,
                            "arguments" to msg.toolCallArgs
                        )
                    ))
                ))
            } else if (msg.isCommand) {
                // Skip command messages — display-only, not API data
            } else if (msg.role == "tool" && msg.toolCallId.isNotEmpty()) {
                result.add(mapOf("role" to "tool", "content" to msg.content, "tool_call_id" to msg.toolCallId))
            } else if (msg.role == "assistant" && msg.content.isEmpty()) {
                // Skip empty assistant messages (streaming or not)
            } else {
                result.add(mapOf("role" to msg.role, "content" to msg.content))
            }
        }
        return result
    }

    fun getMessagesForSave(): List<Map<String, String>> {
        return messages.map { msg ->
            if (msg.isCommand) {
                mapOf("role" to "assistant", "content" to "EXECUTE:${msg.content}|||${msg.commandOutput}")
            } else if (msg.role == "tool" && msg.toolCallId.isNotEmpty()) {
                mapOf("role" to "tool", "content" to msg.content, "tool_call_id" to msg.toolCallId)
            } else if (msg.isToolCall) {
                mapOf("role" to msg.role, "content" to "TOOLCALL:${msg.toolCallName}(${msg.toolCallArgs})")
            } else {
                mapOf("role" to msg.role, "content" to msg.content)
            }
        }
    }

    fun loadFromMessages(apiMessages: List<Map<String, String>>) {
        messages.clear()
        streamingPosition = -1
        streamingViewHolder = null
        for (m in apiMessages) {
            val content = m["content"] ?: ""
            val role = m["role"] ?: "user"
            if (content.startsWith("TOOLCALL:")) {
                val raw = content.removePrefix("TOOLCALL:")
                val paren = raw.indexOf('(')
                if (paren >= 0) {
                    val name = raw.substring(0, paren)
                    val args = raw.substring(paren + 1, raw.length - 1)
                    messages.add(ChatMessage(
                        role = "assistant",
                        isToolCall = true,
                        toolCallName = name,
                        toolCallArgs = args,
                        toolCallId = "call_${System.nanoTime()}"
                    ))
                }
            } else if (content.startsWith("EXECUTE:")) {
                val raw = content.removePrefix("EXECUTE:")
                val sep = raw.indexOf("|||")
                val cmd = if (sep >= 0) raw.substring(0, sep) else raw
                val output = if (sep >= 0) raw.substring(sep + 3) else ""
                messages.add(ChatMessage(
                    role = "assistant",
                    content = cmd,
                    isCommand = true,
                    isStreaming = false,
                    commandOutput = output
                ))
            } else {
                messages.add(ChatMessage(role = role, content = content))
            }
        }
        notifyDataSetChanged()
    }

    fun clearAll() {
        messages.clear()
        streamingPosition = -1
        streamingViewHolder = null
        notifyDataSetChanged()
    }

    fun submitList(newList: List<ChatMessage>) {
        val oldList = messages.toList()
        messages.clear()
        messages.addAll(newList)
        streamingPosition = messages.indexOfLast { it.isStreaming }
        if (streamingPosition < 0) streamingViewHolder = null

        if (oldList.isEmpty() && newList.isEmpty()) return
        if (oldList.isEmpty() || newList.isEmpty()) {
            notifyDataSetChanged()
            return
        }

        // Diff — only rebind what actually changed
        val diff = androidx.recyclerview.widget.DiffUtil.calculateDiff(object : androidx.recyclerview.widget.DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldPos: Int, newPos: Int): Boolean = oldList[oldPos].id == newList[newPos].id
            override fun areContentsTheSame(oldPos: Int, newPos: Int): Boolean = oldList[oldPos] == newList[newPos]
        })
        diff.dispatchUpdatesTo(this)
    }

    inner class ViewHolder(itemView: View, private val adapter: ChatAdapter) : RecyclerView.ViewHolder(itemView) {
        private val bubbleUser = itemView.findViewById<View>(R.id.bubble_user)
        private val bubbleAssistant = itemView.findViewById<View>(R.id.bubble_assistant)
        private val commandContainer = itemView.findViewById<View>(R.id.command_container)
        private val tvUserText = itemView.findViewById<TextView>(R.id.tv_user_text)
        val tvAssistantText = itemView.findViewById<TextView>(R.id.tv_assistant_text)
        private val dotsContainer = itemView.findViewById<View>(R.id.dots_container)
        private val dot1 = itemView.findViewById<View>(R.id.dot1)
        private val dot2 = itemView.findViewById<View>(R.id.dot2)
        private val dot3 = itemView.findViewById<View>(R.id.dot3)
        private val tvCommandTitle = itemView.findViewById<TextView>(R.id.tv_command_title)
        private val ivCommandChevron = itemView.findViewById<ImageView>(R.id.iv_command_chevron)
        private val commandOutputPanel = itemView.findViewById<View>(R.id.command_output_panel)
        private val tvCommandOutput = itemView.findViewById<TextView>(R.id.tv_command_output)
        private val commandHeader = itemView.findViewById<View>(R.id.command_header)
        private val toolCallContainer = itemView.findViewById<View>(R.id.toolcall_container)
        private val tvToolCall = itemView.findViewById<TextView>(R.id.tv_toolcall)

        private var dotAnimator: ValueAnimator? = null

        fun bind(msg: ChatMessage, position: Int) {
            // Hide all containers first
            bubbleUser.visibility = View.GONE
            bubbleAssistant.visibility = View.GONE
            commandContainer.visibility = View.GONE
            stopDotAnimation()

            when {
                msg.isCommand -> {
                    commandContainer.visibility = View.VISIBLE
                    toolCallContainer.visibility = View.GONE
                    bindCommand(msg, position)
                }
                msg.role == "user" -> {
                    bubbleUser.visibility = View.VISIBLE
                    toolCallContainer.visibility = View.GONE
                    tvUserText.text = msg.content
                }
                msg.isToolCall -> {
                    // Hidden — raw tool call is redundant with the command display below
                }
                msg.role == "tool" -> {
                    // Hidden — only used for API history, not visual display
                }
                else -> {
                    toolCallContainer.visibility = View.GONE
                    val hasContent = msg.content.isNotEmpty() || msg.isStreaming
                    bubbleAssistant.visibility = if (hasContent) View.VISIBLE else View.GONE
                    if (hasContent) {
                        if (msg.isStreaming) {
                            tvAssistantText.text = msg.content
                            dotsContainer.visibility = View.VISIBLE
                            startDotAnimation()
                        } else {
                            dotsContainer.visibility = View.GONE
                            if (msg.content.isNotEmpty()) {
                                markwon?.setMarkdown(tvAssistantText, msg.content)
                                // Override Markwon's movement method — ArrowKeyMovementMethod supports
                                // text selection and won't scroll on single tap
                                tvAssistantText.movementMethod = android.text.method.ArrowKeyMovementMethod.getInstance()
                                tvAssistantText.setTextIsSelectable(true)
                            } else {
                                tvAssistantText.text = ""
                            }
                        }
                    }
                }
            }
        }

        private fun bindCommand(msg: ChatMessage, position: Int) {
            val title = if (msg.commandLabel.isNotEmpty()) msg.commandLabel else msg.content
            if (msg.isStreaming) {
                // Executing
                tvCommandTitle.text = "🔧  $title"
                ivCommandChevron.visibility = View.GONE
                commandOutputPanel.visibility = View.GONE
            } else {
                // Executed
                tvCommandTitle.text = "✅  $title"
                ivCommandChevron.visibility = View.VISIBLE
                ivCommandChevron.rotation = if (msg.commandExpanded) 180f else 0f
                commandOutputPanel.visibility = if (msg.commandExpanded) View.VISIBLE else View.GONE
                tvCommandOutput.text = msg.commandOutput
            }

            commandHeader.setOnClickListener {
                if (!msg.isStreaming) {
                    val newExpanded = !msg.commandExpanded
                    adapter.messages[position] = msg.copy(commandExpanded = newExpanded)
                    notifyItemChanged(position)
                }
            }
        }

        fun setAssistantText(text: String) {
            tvAssistantText.text = text
        }

        private fun startDotAnimation() {
            if (dotAnimator?.isRunning == true) return
            val dots = listOf(dot1, dot2, dot3)
            dotAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1200
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { anim ->
                    val fraction = anim.animatedFraction
                    dots.forEachIndexed { i, dot ->
                        val phase = (fraction + i * 0.33f) % 1f
                        val scale = 0.3f + 0.7f * abs(sin(phase * Math.PI.toFloat()))
                        dot.scaleX = scale
                        dot.scaleY = scale
                    }
                }
                start()
            }
        }

        fun stopDotAnimation() {
            dotAnimator?.let {
                if (it.isRunning) it.cancel()
            }
            dotAnimator = null
            listOf(dot1, dot2, dot3).forEach {
                it.scaleX = 1f
                it.scaleY = 1f
            }
        }
    }
}
