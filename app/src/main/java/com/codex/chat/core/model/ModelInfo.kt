package com.codex.chat.core.model

enum class ReasoningEffort(val value: String) {
    LOW("low"),
    MEDIUM("medium"),
    HIGH("high"),
    XHIGH("xhigh");

    companion object {
        fun fromString(str: String?): ReasoningEffort {
            return entries.firstOrNull { it.value.equals(str, ignoreCase = true) } ?: MEDIUM
        }
    }
}

data class ModelInfo(
    val id: String,
    val displayName: String,
    val provider: String,
    val supportsReasoning: Boolean = false,
    val defaultReasoningEffort: ReasoningEffort = ReasoningEffort.MEDIUM
)
