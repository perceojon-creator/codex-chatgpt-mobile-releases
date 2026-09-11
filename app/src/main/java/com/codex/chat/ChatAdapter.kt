package com.codex.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole

class ChatAdapter(private val messages: MutableList<ChatMessage>) :
    RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val VIEW_TYPE_USER = 1
        private const val VIEW_TYPE_ASSISTANT = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (messages[position].role) {
            MessageRole.USER -> VIEW_TYPE_USER
            MessageRole.ASSISTANT, MessageRole.SYSTEM -> VIEW_TYPE_ASSISTANT
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == VIEW_TYPE_USER) {
            val view = inflater.inflate(R.layout.item_message_user, parent, false)
            UserViewHolder(view)
        } else {
            val view = inflater.inflate(R.layout.item_message_assistant, parent, false)
            AssistantViewHolder(view)
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val msg = messages[position]
        if (holder is UserViewHolder) {
            holder.bind(msg)
        } else if (holder is AssistantViewHolder) {
            holder.bind(msg)
        }
    }

    override fun getItemCount(): Int = messages.size

    fun addMessage(msg: ChatMessage) {
        messages.add(msg)
        notifyItemInserted(messages.size - 1)
    }

    fun updateLastMessage(newContent: String, newReasoning: String = "") {
        if (messages.isNotEmpty()) {
            val lastIdx = messages.size - 1
            val last = messages[lastIdx]
            last.content = newContent
            if (newReasoning.isNotEmpty()) {
                last.reasoningContent = newReasoning
            }
            notifyItemChanged(lastIdx)
        }
    }

    class UserViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvUserContent)
        fun bind(msg: ChatMessage) {
            tvContent.text = msg.content
        }
    }

    class AssistantViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val tvContent: TextView = itemView.findViewById(R.id.tvAssistantContent)
        private val layoutThinking: LinearLayout = itemView.findViewById(R.id.layoutThinking)
        private val tvThinkingHeader: TextView = itemView.findViewById(R.id.tvThinkingHeader)
        private val tvThinkingBody: TextView = itemView.findViewById(R.id.tvThinkingBody)
        private val btnCopy: TextView = itemView.findViewById(R.id.btnCopy)

        fun bind(msg: ChatMessage) {
            tvContent.text = msg.content

            // Thinking block binding
            if (msg.hasReasoning) {
                layoutThinking.visibility = View.VISIBLE
                tvThinkingBody.text = msg.reasoningContent
                if (msg.isThinkingExpanded) {
                    tvThinkingBody.visibility = View.VISIBLE
                    tvThinkingHeader.text = "💭 Ocultar razonamiento ▴"
                } else {
                    tvThinkingBody.visibility = View.GONE
                    tvThinkingHeader.text = "💭 Proceso de razonamiento ▾"
                }

                tvThinkingHeader.setOnClickListener {
                    msg.isThinkingExpanded = !msg.isThinkingExpanded
                    if (msg.isThinkingExpanded) {
                        tvThinkingBody.visibility = View.VISIBLE
                        tvThinkingHeader.text = "💭 Ocultar razonamiento ▴"
                    } else {
                        tvThinkingBody.visibility = View.GONE
                        tvThinkingHeader.text = "💭 Proceso de razonamiento ▾"
                    }
                }
            } else {
                layoutThinking.visibility = View.GONE
            }

            // Copy action
            btnCopy.setOnClickListener {
                val context = itemView.context
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("ChatGPT response", msg.content)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Copiado al portapapeles", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
