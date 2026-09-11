package com.codex.chat.core.parser

import org.json.JSONObject

class SseStreamParser(private val listener: SseEventListener) {

    interface SseEventListener {
        fun onReasoningDelta(delta: String)
        fun onContentDelta(delta: String)
        fun onComplete(fullContent: String, fullReasoning: String)
        fun onError(error: Throwable)
    }

    private val contentAccumulator = StringBuilder()
    private val reasoningAccumulator = StringBuilder()
    private val lineBuffer = StringBuilder()

    private var inInlineThinkingBlock = false
    private var isCompleted = false

    fun feedChunk(chunk: String) {
        if (isCompleted) return

        lineBuffer.append(chunk)

        var newlineIdx = lineBuffer.indexOf('\n')
        while (newlineIdx != -1) {
            val line = lineBuffer.substring(0, newlineIdx).trim()
            lineBuffer.delete(0, newlineIdx + 1)

            processLine(line)
            if (isCompleted) return

            newlineIdx = lineBuffer.indexOf('\n')
        }
    }

    fun close() {
        if (isCompleted) return

        if (lineBuffer.isNotEmpty()) {
            val trailing = lineBuffer.toString().trim()
            lineBuffer.setLength(0)
            if (trailing.isNotEmpty()) {
                processLine(trailing)
            }
        }

        if (!isCompleted) {
            isCompleted = true
            listener.onComplete(contentAccumulator.toString(), reasoningAccumulator.toString())
        }
    }

    private fun processLine(line: String) {
        if (line.isEmpty() || line.startsWith(":")) {
            return
        }

        val dataPrefix = "data:"
        if (!line.startsWith(dataPrefix)) {
            if (line.startsWith("{") && line.contains("error")) {
                handleJsonError(line)
            }
            return
        }

        val dataContent = line.substring(dataPrefix.length).trim()
        if (dataContent == "[DONE]") {
            isCompleted = true
            listener.onComplete(contentAccumulator.toString(), reasoningAccumulator.toString())
            return
        }

        if (!dataContent.startsWith("{")) {
            return
        }

        try {
            val json = JSONObject(dataContent)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: json.optString("error", "Error desconocido de API")
                isCompleted = true
                listener.onError(RuntimeException(errMsg))
                return
            }

            val choices = json.optJSONArray("choices") ?: return
            if (choices.length() == 0) return

            val firstChoice = choices.getJSONObject(0)
            val delta = firstChoice.optJSONObject("delta") ?: return

            val explicitReasoning = when {
                delta.has("reasoning_content") -> delta.optString("reasoning_content", "")
                delta.has("reasoning") -> delta.optString("reasoning", "")
                else -> ""
            }

            if (explicitReasoning.isNotEmpty()) {
                reasoningAccumulator.append(explicitReasoning)
                listener.onReasoningDelta(explicitReasoning)
            }

            val contentDelta = delta.optString("content", "")
            if (contentDelta.isNotEmpty()) {
                processContentWithPotentialInlineThinking(contentDelta)
            }

        } catch (e: Exception) {
            // Non-fatal chunk error
        }
    }

    private fun processContentWithPotentialInlineThinking(rawChunk: String) {
        var remaining = rawChunk

        while (remaining.isNotEmpty()) {
            if (!inInlineThinkingBlock) {
                val thinkStartIdx = remaining.indexOf("<think>")
                val thoughtStartIdx = remaining.indexOf("<thought>")

                val tagIdx = when {
                    thinkStartIdx != -1 && thoughtStartIdx != -1 -> minOf(thinkStartIdx, thoughtStartIdx)
                    thinkStartIdx != -1 -> thinkStartIdx
                    thoughtStartIdx != -1 -> thoughtStartIdx
                    else -> -1
                }

                if (tagIdx != -1) {
                    if (tagIdx > 0) {
                        val beforeTag = remaining.substring(0, tagIdx)
                        contentAccumulator.append(beforeTag)
                        listener.onContentDelta(beforeTag)
                    }

                    inInlineThinkingBlock = true
                    val tagLen = if (remaining.startsWith("<think>", tagIdx)) 7 else 9
                    remaining = remaining.substring(tagIdx + tagLen)
                } else {
                    contentAccumulator.append(remaining)
                    listener.onContentDelta(remaining)
                    remaining = ""
                }
            } else {
                val thinkEndIdx = remaining.indexOf("</think>")
                val thoughtEndIdx = remaining.indexOf("</thought>")

                val tagIdx = when {
                    thinkEndIdx != -1 && thoughtEndIdx != -1 -> minOf(thinkEndIdx, thoughtEndIdx)
                    thinkEndIdx != -1 -> thinkEndIdx
                    thoughtEndIdx != -1 -> thoughtEndIdx
                    else -> -1
                }

                if (tagIdx != -1) {
                    val thinkingPart = remaining.substring(0, tagIdx)
                    if (thinkingPart.isNotEmpty()) {
                        reasoningAccumulator.append(thinkingPart)
                        listener.onReasoningDelta(thinkingPart)
                    }

                    inInlineThinkingBlock = false
                    val tagLen = if (remaining.startsWith("</think>", tagIdx)) 8 else 10
                    remaining = remaining.substring(tagIdx + tagLen)
                } else {
                    reasoningAccumulator.append(remaining)
                    listener.onReasoningDelta(remaining)
                    remaining = ""
                }
            }
        }
    }

    private fun handleJsonError(rawJson: String) {
        try {
            val json = JSONObject(rawJson)
            val errObj = json.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: "Error de API"
            isCompleted = true
            listener.onError(RuntimeException(errMsg))
        } catch (ignored: Exception) {
            listener.onError(RuntimeException("Respuesta de error inesperada: " + rawJson))
        }
    }
}
