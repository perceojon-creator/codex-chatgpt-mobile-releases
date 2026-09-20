package com.codex.chat.agent.network

import com.codex.chat.agent.core.AgentAction

/**
 * Abstraction layer for multimodal grounding reasoning.
 * Communicates with CLIProxyAPI or mock vision server.
 */
interface IVisionClient {
    suspend fun think(
        goal: String,
        stepIndex: Int,
        screenshotBase64: String,
        uiHierarchy: String,
        actionHistory: List<AgentAction>
    ): AgentAction
}
