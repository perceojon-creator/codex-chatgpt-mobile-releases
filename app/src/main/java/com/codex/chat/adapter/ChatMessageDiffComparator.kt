package com.codex.chat.adapter

import androidx.recyclerview.widget.DiffUtil
import com.codex.chat.core.model.ChatMessage

object ChatMessageDiffComparator : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.content == newItem.content &&
               oldItem.reasoningContent == newItem.reasoningContent &&
               oldItem.role == newItem.role &&
               oldItem.isStreaming == newItem.isStreaming &&
               oldItem.isThinkingExpanded == newItem.isThinkingExpanded
    }
}
