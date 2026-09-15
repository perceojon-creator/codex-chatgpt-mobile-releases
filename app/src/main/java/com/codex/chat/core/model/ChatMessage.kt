package com.codex.chat.core.model

import java.util.UUID

enum class MessageRole(val value: String) {
    USER("user"),
    ASSISTANT("assistant"),
    SYSTEM("system"),
    TOOL("tool")
}

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: MessageRole,
    var content: String,
    var reasoningContent: String = "",
    val toolCallId: String = "",
    val toolName: String = "",
    val toolCallsJson: String = "",
    val attachments: List<Attachment> = emptyList(),
    var isStreaming: Boolean = false,
    var isThinkingExpanded: Boolean = false,
    var isToolExpanded: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    var durationMs: Long = 0L,
    var thinkingDurationMs: Long = 0L,
    var generationDurationMs: Long = 0L,
    var completionTokens: Int = 0,
    var promptTokens: Int = 0,
    var totalTokens: Int = 0,
    var tokensPerSecond: Double = 0.0,
    var canContinueTask: Boolean = false
) {
    val hasReasoning: Boolean
        get() = reasoningContent.isNotBlank()

    val hasPerformanceMetrics: Boolean
        get() = role == MessageRole.ASSISTANT && (durationMs > 0L || completionTokens > 0 || tokensPerSecond > 0.0)

    fun toStreamMetrics(): com.codex.chat.core.metrics.StreamMetrics {
        return com.codex.chat.core.metrics.StreamMetrics(
            durationMs = durationMs,
            thinkingDurationMs = thinkingDurationMs,
            generationDurationMs = generationDurationMs,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            tokensPerSecond = tokensPerSecond
        )
    }

    fun applyStreamMetrics(metrics: com.codex.chat.core.metrics.StreamMetrics) {
        this.durationMs = metrics.durationMs
        this.thinkingDurationMs = metrics.thinkingDurationMs
        this.generationDurationMs = metrics.generationDurationMs
        this.promptTokens = metrics.promptTokens
        this.completionTokens = metrics.completionTokens
        this.totalTokens = metrics.totalTokens
        this.tokensPerSecond = metrics.tokensPerSecond
    }
}
