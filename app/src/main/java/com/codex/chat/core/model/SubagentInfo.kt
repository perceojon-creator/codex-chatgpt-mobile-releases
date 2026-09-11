package com.codex.chat.core.model

data class SubagentInfo(
    val id: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val iconEmoji: String = "🤖",
    val defaultModel: String = "gpt-5.6-sol",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH
)
