package id.fahrul.sanel

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ChatHistoryAdapter(
    private val onItemClick: (Conversation) -> Unit,
    private val onItemLongClick: (Conversation) -> Unit
) : RecyclerView.Adapter<ChatHistoryAdapter.ViewHolder>() {

    private val items = mutableListOf<Conversation>()
    private val dateFormat = SimpleDateFormat("MMM d, HH:mm", Locale.getDefault())

    fun submitList(list: List<Conversation>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvTitle = itemView.findViewById<TextView>(R.id.tv_history_title)
        private val tvTime = itemView.findViewById<TextView>(R.id.tv_history_time)

        fun bind(conv: Conversation) {
            tvTitle.text = conv.title
            tvTime.text = dateFormat.format(Date(conv.timestamp))
            itemView.setOnClickListener { onItemClick(conv) }
            itemView.setOnLongClickListener {
                onItemLongClick(conv)
                true
            }
        }
    }
}
