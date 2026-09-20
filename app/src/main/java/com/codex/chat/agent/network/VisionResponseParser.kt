package com.codex.chat.agent.network

import com.codex.chat.agent.core.AgentAction
import org.json.JSONObject

/**
 * Robust extractor that parses model text outputs (markdown code blocks,
 * raw JSON, or conversational output containing a JSON object) into
 * strongly-typed AgentAction instances with graceful error recovery.
 */
class VisionResponseParser {

    fun parse(response: String): AgentAction {
        val cleanedJson = extractJsonObject(response)
            ?: return AgentAction.Fail("No valid JSON action object found in response")

        return try {
            val json = JSONObject(cleanedJson)
            mapJsonToAction(json)
        } catch (e: Throwable) {
            AgentAction.Fail("Failed to parse action JSON: ${e.message}")
        }
    }

    private fun extractJsonObject(text: String): String? {
        val trimmed = text.trim()

        // 1. Try markdown fenced block: look for ``` (or ```json) and trailing ```
        val codeFenceStart = trimmed.indexOf("```")
        if (codeFenceStart != -1) {
            val firstBrace = trimmed.indexOf('{', startIndex = codeFenceStart)
            val codeFenceEnd = trimmed.lastIndexOf("```")
            if (firstBrace != -1 && codeFenceEnd > firstBrace) {
                val candidate = trimmed.substring(firstBrace, codeFenceEnd).trim()
                val lastBrace = candidate.lastIndexOf('}')
                if (lastBrace != -1) {
                    return candidate.substring(0, lastBrace + 1)
                }
            }
        }

        // 2. Try raw balanced braces search
        val firstBrace = trimmed.indexOf('{')
        if (firstBrace == -1) return null

        var depth = 0
        for (i in firstBrace until trimmed.length) {
            when (trimmed[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) {
                        return trimmed.substring(firstBrace, i + 1).trim()
                    }
                }
            }
        }

        return null
    }

    private fun mapJsonToAction(json: JSONObject): AgentAction {
        val actionName = json.optString("action", "").trim().lowercase()
        val reason = json.optString("reason", "")

        return when (actionName) {
            "tap" -> AgentAction.Tap(
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                reason = reason
            )

            "double_tap" -> AgentAction.DoubleTap(
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                reason = reason
            )

            "long_press" -> AgentAction.LongPress(
                x = json.optInt("x", 0),
                y = json.optInt("y", 0),
                durationMs = json.optLong("duration_ms", 1000L),
                reason = reason
            )

            "swipe" -> AgentAction.Swipe(
                startX = json.optInt("startX", 0),
                startY = json.optInt("startY", 0),
                endX = json.optInt("endX", 0),
                endY = json.optInt("endY", 0),
                durationMs = json.optLong("duration_ms", 300L),
                reason = reason
            )

            "input_text" -> AgentAction.InputText(
                text = json.optString("text", ""),
                pressEnter = json.optBoolean("press_enter", false),
                reason = reason
            )

            "press_key" -> {
                val keyStr = json.optString("key", "BACK").uppercase()
                val key = runCatching { AgentAction.GlobalKey.valueOf(keyStr) }
                    .getOrElse { AgentAction.GlobalKey.BACK }
                AgentAction.PressKey(key = key, reason = reason)
            }

            "wait" -> AgentAction.Wait(
                seconds = json.optInt("seconds", 1).coerceAtLeast(1),
                reason = reason
            )

            "complete" -> AgentAction.Complete(
                result = json.optString("result", "Goal completed successfully")
            )

            "fail" -> AgentAction.Fail(
                error = json.optString("error", "Action failed without specific error message")
            )

            else -> AgentAction.Fail("Unknown agent action: '$actionName'")
        }
    }
}
