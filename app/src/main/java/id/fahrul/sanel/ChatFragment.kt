package id.fahrul.sanel

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import id.fahrul.sanel.databinding.FragmentChatBinding
import kotlinx.coroutines.launch

class ChatFragment : Fragment() {

    private var _binding: FragmentChatBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ChatViewModel by viewModels()
    private val adapter = ChatAdapter()

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentChatBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.init(requireContext())

        binding.recyclerChat.adapter = adapter
        binding.recyclerChat.layoutManager = LinearLayoutManager(requireContext())

        binding.etMessage.setOnEditorActionListener { _, _, _ ->
            sendMessage(); true
        }
        binding.btnSend.setOnClickListener { sendMessage() }

        binding.inputLayout.setOnClickListener {
            binding.etMessage.requestFocus()
        }

        // Observe full list — for initial load and structural changes
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.messages.collect { msgs ->
                adapter.submitList(msgs)
            }
        }

        // Observe streaming updates — incremental, no full rebind
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.streamingUpdate.collect { text ->
                adapter.updateStreamingDirect(text)
            }
        }

        // Scroll to bottom on new messages — skip if user manually scrolled up
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scrollToBottom.collect {
                if (adapter.itemCount > 0) {
                    val lm = binding.recyclerChat.layoutManager as LinearLayoutManager
                    val lastVisible = lm.findLastVisibleItemPosition()
                    // Only auto-scroll if user is within 3 items of the bottom
                    if (lastVisible < 0 || adapter.itemCount - lastVisible <= 3) {
                        binding.recyclerChat.smoothScrollToPosition(adapter.itemCount - 1)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isStreaming.collect { streaming ->
                binding.btnSend.isEnabled = !streaming
            }
        }

        // Load pending conversation
        val pending = ConversationManager.consumePendingLoad()
        if (pending != null && pending.messages.isNotEmpty()) {
            viewModel.loadMessages(pending.messages)
        }
    }

    override fun onPause() {
        super.onPause()
        // Cancel streaming when app goes to background
        if (StreamingManager.isStreamActive()) {
            StreamingManager.cancelActiveStream("App paused - backgrounded")
        }
    }

    override fun onResume() {
        super.onResume()
        binding.recyclerChat.post {
            if (adapter.itemCount > 0) {
                binding.recyclerChat.scrollToPosition(adapter.itemCount - 1)
                // Second post after layout — scroll to actual bottom of last item
                binding.recyclerChat.post {
                    val lm = binding.recyclerChat.layoutManager as LinearLayoutManager
                    val lastView = lm.findViewByPosition(adapter.itemCount - 1) ?: return@post
                    val overshoot = lastView.bottom - (binding.recyclerChat.height - binding.recyclerChat.paddingBottom)
                    if (overshoot > 0) {
                        binding.recyclerChat.scrollBy(0, overshoot)
                    }
                }
            }
        }
    }

    fun saveCurrentConversation() {
        viewModel.saveConversation()
    }

    fun clearChat() {
        viewModel.loadMessages(emptyList())
    }

    private fun sendMessage() {
        val text = binding.etMessage.text.toString().trim()
        if (text.isEmpty()) return
        binding.etMessage.text?.clear()
        viewModel.sendMessage(text)
        binding.recyclerChat.smoothScrollToPosition(adapter.itemCount - 1)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
