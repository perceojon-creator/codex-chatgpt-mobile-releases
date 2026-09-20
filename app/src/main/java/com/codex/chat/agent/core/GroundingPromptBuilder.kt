package com.codex.chat.agent.core

/**
 * Constructs structured system instructions and multimodal user payloads
 * with pixel coordinate grounding and strict schema compliance.
 */
class GroundingPromptBuilder {

    fun buildSystemPrompt(screenWidthPx: Int, screenHeightPx: Int): String = """
        You are an autonomous mobile UI agent operating an Android device.
        Device screen dimensions: ${screenWidthPx}x${screenHeightPx} pixels.
        Coordinate system: origin (0, 0) is the top-left corner.
        X increases towards the right, Y increases towards the bottom.

        You perceive the device state through:
        1. Current screenshot (JPEG image)
        2. Filtered UI accessibility hierarchy with element bounds [left, top, right, bottom]
        3. History of recent executed actions

        You MUST respond with EXACTLY ONE valid JSON object matching this schema:
        {
          "action": "tap" | "double_tap" | "long_press" | "swipe" | "input_text" | "press_key" | "wait" | "complete" | "fail",
          "x": <integer x pixel for tap/double_tap/long_press>,
          "y": <integer y pixel for tap/double_tap/long_press>,
          "startX": <integer startX for swipe>,
          "startY": <integer startY for swipe>,
          "endX": <integer endX for swipe>,
          "endY": <integer endY for swipe>,
          "duration_ms": <optional integer duration in milliseconds>,
          "text": <string text to type for input_text>,
          "press_enter": <boolean whether to press Enter/Search after typing>,
          "key": "BACK" | "HOME" | "RECENTS" for press_key,
          "seconds": <integer wait duration>,
          "result": <string description of outcome when task is complete>,
          "error": <string error description if task failed>,
          "reason": <REQUIRED concise explanation of this step>
        }

        Operating Guidelines:
        - When the goal is fully accomplished, emit action "complete".
        - When an unrecoverable error occurs or the goal cannot be completed, emit action "fail".
        - Coordinates must stay within bounds: 0 <= x <= ${screenWidthPx} and 0 <= y <= ${screenHeightPx}.
        - Prefer clicking near the center of clickable elements identified in the UI hierarchy.
        - Do not output preamble or conversational text outside the JSON object.
    """.trimIndent()

    fun buildUserMessage(
        goal: String,
        stepIndex: Int,
        uiHierarchy: String,
        actionHistory: List<AgentAction>
    ): String {
        val historySection = if (actionHistory.isEmpty()) {
            "None (First Step)"
        } else {
            actionHistory.takeLast(6).mapIndexed { idx, act ->
                "  [${idx + 1}] $act"
            }.joinToString("\n")
        }

        return """
            [USER GOAL]: $goal
            [CURRENT STEP]: $stepIndex
            [ACTION HISTORY]:
            $historySection
            [UI HIERARCHY (Interactive Elements)]:
            $uiHierarchy
        """.trimIndent()
    }
}
