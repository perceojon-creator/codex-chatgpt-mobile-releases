package com.codex.chat.adapter

import androidx.recyclerview.widget.DiffUtil
import com.codex.chat.core.model.ChatMessage

/**
 * Comparador canónico de ChatMessage para DiffUtil y AsyncListDiffer.
 *
 * Fase 4 – Task 16: La versión anterior sólo comparaba 5 campos mientras que
 * ChatAdapter.setMessages() verificaba 11. Esa discrepancia provocaba que
 * AsyncListDiffer no notificara cambios en métricas (durationMs, tokensPerSecond)
 * ni en el estado expandido de herramientas (isToolExpanded). Ahora los 11 campos
 * están sincronizados.
 */
object ChatMessageDiffComparator : DiffUtil.ItemCallback<ChatMessage>() {
    override fun areItemsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.id == newItem.id
    }

    override fun areContentsTheSame(oldItem: ChatMessage, newItem: ChatMessage): Boolean {
        return oldItem.content           == newItem.content           &&
               oldItem.reasoningContent  == newItem.reasoningContent  &&
               oldItem.role              == newItem.role              &&
               oldItem.isStreaming       == newItem.isStreaming       &&
               oldItem.isThinkingExpanded == newItem.isThinkingExpanded &&
               oldItem.isToolExpanded    == newItem.isToolExpanded    &&
               oldItem.durationMs        == newItem.durationMs        &&
               oldItem.thinkingDurationMs == newItem.thinkingDurationMs &&
               oldItem.generationDurationMs == newItem.generationDurationMs &&
               oldItem.completionTokens  == newItem.completionTokens  &&
               oldItem.tokensPerSecond   == newItem.tokensPerSecond   &&
               oldItem.canContinueTask   == newItem.canContinueTask
    }
}
