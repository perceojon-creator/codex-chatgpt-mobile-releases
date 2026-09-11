package com.codex.chat.core.model

import java.util.UUID

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system")
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    var content: String,
    var reasoningContent: String = "",
    val attachments: List<Attachment> = emptyList(),
    var isStreaming: Boolean = false,
    var isThinkingExpanded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
) {
    val hasReasoning: Boolean
        get() = reasoningContent.isNotBlank()
}
