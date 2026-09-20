package com.codex.chat.agent.core

/**
 * Sealed hierarchy of atomic actions executable by the autonomous mobile agent.
 */
sealed class AgentAction {
    enum class GlobalKey { BACK, HOME, RECENTS }

    data class Tap(
        val x: Int,
        val y: Int,
        val reason: String = ""
    ) : AgentAction()

    data class DoubleTap(
        val x: Int,
        val y: Int,
        val reason: String = ""
    ) : AgentAction()

    data class LongPress(
        val x: Int,
        val y: Int,
        val durationMs: Long = 1000L,
        val reason: String = ""
    ) : AgentAction()

    data class Swipe(
        val startX: Int,
        val startY: Int,
        val endX: Int,
        val endY: Int,
        val durationMs: Long = 300L,
        val reason: String = ""
    ) : AgentAction()

    data class InputText(
        val text: String,
        val pressEnter: Boolean = false,
        val reason: String = ""
    ) : AgentAction()

    data class PressKey(
        val key: GlobalKey,
        val reason: String = ""
    ) : AgentAction()

    data class Wait(
        val seconds: Int,
        val reason: String = ""
    ) : AgentAction()

    data class Complete(
        val result: String
    ) : AgentAction()

    data class Fail(
        val error: String
    ) : AgentAction()
}
