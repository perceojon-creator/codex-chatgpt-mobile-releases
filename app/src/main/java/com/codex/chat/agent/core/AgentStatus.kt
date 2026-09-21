package com.codex.chat.agent.core

/**
 * Immutable telemetry and progress snapshot representing the live agent state.
 * Emitted by AutonomousAgentLoop and collected by FloatingAgentOverlay.
 */
data class AgentStatus(
    val goal: String = "",
    val stepIndex: Int = 0,
    val maxSteps: Int = 1_000_000,
    val statusText: String = "",
    val lastAction: AgentAction? = null,
    val isAborted: Boolean = false,
    val isComplete: Boolean = false
)
