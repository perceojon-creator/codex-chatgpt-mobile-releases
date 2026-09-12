package com.codex.chat.core.model

data class SkillInfo(
    val id: String,
    val name: String,
    val description: String,
    val category: String = "Claude & Codex",
    val systemPrompt: String,
    val iconEmoji: String = "⚡",
    val author: String = "OpenAI Codex",
    var isInstalled: Boolean = true,
    val isCustom: Boolean = false,
    val defaultModel: String = "gpt-5.6-sol",
    val reasoningEffort: ReasoningEffort = ReasoningEffort.HIGH
) {
    fun toSubagent(): SubagentInfo = SubagentInfo(
        id = id,
        name = name,
        description = description,
        systemPrompt = systemPrompt,
        iconEmoji = iconEmoji,
        defaultModel = defaultModel,
        reasoningEffort = reasoningEffort
    )
}
