package com.codex.chat.core.model

data class SlashCommandInfo(
    val command: String,
    val description: String,
    val iconEmoji: String = "⚡",
    val badge: String = "CMD",
    val actionType: SlashActionType = SlashActionType.AUTOCOMPLETE,
    val targetSkillId: String? = null
)

enum class SlashActionType {
    AUTOCOMPLETE,
    EXECUTE_INSTANT,
    OPEN_STORE,
    INSTALL_SKILL_DIALOG,
    CLEAR_CHAT
}
