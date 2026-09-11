package com.codex.chat

enum class MessageRole {
    USER, ASSISTANT
}

data class ChatMessage(
    val role: MessageRole,
    var content: String,
    val isStreaming: Boolean = false
)
