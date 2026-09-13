package com.codex.chat.core.parser

import org.json.JSONObject

class SseStreamParser(private val listener: SseEventListener) {

    data class CompletedToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    interface SseEventListener {
        fun onReasoningDelta(delta: String)
        fun onContentDelta(delta: String)
        fun onComplete(fullContent: String, fullReasoning: String)
        fun onToolCallsReceived(toolCalls: List<CompletedToolCall>) {}
        fun onError(error: Throwable)
    }

    private val completedToolCalls = mutableListOf<CompletedToolCall>()
    fun getCompletedToolCalls(): List<CompletedToolCall> = synchronized(completedToolCalls) { completedToolCalls.toList() }

    private val contentAccumulator = StringBuilder()
    private val reasoningAccumulator = StringBuilder()
    private val lineBuffer = StringBuilder()
    private val inlineTagBuffer = StringBuilder()

    private var inInlineThinkingBlock = false
    private var isCompleted = false

    fun feedChunk(chunk: String) {
        if (isCompleted) return

        lineBuffer.append(chunk)

        var newlineIdx = lineBuffer.indexOf('\n')
        while (newlineIdx != -1) {
            val line = lineBuffer.substring(0, newlineIdx)
            lineBuffer.delete(0, newlineIdx + 1)

            // Remove carriage return if present
            val cleanLine = if (line.endsWith("\r")) line.substring(0, line.length - 1) else line
            processLine(cleanLine)
            if (isCompleted) return

            newlineIdx = lineBuffer.indexOf('\n')
        }
    }

    fun close() {
        if (isCompleted) return

        if (lineBuffer.isNotEmpty()) {
            val trailing = lineBuffer.toString()
            lineBuffer.setLength(0)
            if (trailing.isNotBlank()) {
                processLine(trailing)
            }
        }

        // Flush any lingering inline tag buffer
        flushLingeringTagBuffer()

        if (!isCompleted) {
            isCompleted = true
            listener.onComplete(getSanitizedContent(), getSanitizedReasoning())
            if (completedToolCalls.isNotEmpty()) {
                listener.onToolCallsReceived(getCompletedToolCalls())
            }
        }
    }

    private fun processLine(rawLine: String) {
        val trimmedForCheck = rawLine.trim()
        if (trimmedForCheck.isEmpty() || trimmedForCheck.startsWith(":")) {
            return // Keep-alive ping or empty line
        }

        val dataPrefix = "data:"
        if (!trimmedForCheck.startsWith(dataPrefix)) {
            if (trimmedForCheck.startsWith("{") && trimmedForCheck.contains("error")) {
                handleJsonError(trimmedForCheck)
            }
            return
        }

        // SSE standard: if there is a space directly after 'data:', strip only that single space!
        // Do NOT call .trim() on the data payload, to preserve code indentation!
        val dataContent = if (rawLine.startsWith("data: ")) {
            rawLine.substring(6)
        } else if (rawLine.startsWith("data:")) {
            rawLine.substring(5)
        } else {
            rawLine.trim().substring(5).trim()
        }

        if (dataContent == "[DONE]" || dataContent.trim() == "[DONE]") {
            flushLingeringTagBuffer()
            isCompleted = true
            listener.onComplete(getSanitizedContent(), getSanitizedReasoning())
            if (completedToolCalls.isNotEmpty()) {
                listener.onToolCallsReceived(getCompletedToolCalls())
            }
            return
        }

        val payloadTrimmed = dataContent.trim()
        if (!payloadTrimmed.startsWith("{")) {
            return
        }

        try {
            val json = JSONObject(dataContent)

            if (json.has("error")) {
                val errObj = json.optJSONObject("error")
                val errMsg = errObj?.optString("message") ?: json.optString("error", "Error desconocido de API")
                flushLingeringTagBuffer()
                isCompleted = true
                listener.onError(RuntimeException(errMsg))
                return
            }

            val choices = json.optJSONArray("choices") ?: return
            if (choices.length() == 0) return

            val firstChoice = choices.getJSONObject(0)
            val delta = firstChoice.optJSONObject("delta") ?: return

            // 1. Explicit reasoning field (DeepSeek / OpenAI o-series)
            val explicitReasoning = when {
                delta.has("reasoning_content") && !delta.isNull("reasoning_content") -> {
                    val r = delta.optString("reasoning_content", "")
                    if (r == "null") "" else r
                }
                delta.has("reasoning") && !delta.isNull("reasoning") -> {
                    val r = delta.optString("reasoning", "")
                    if (r == "null") "" else r
                }
                else -> ""
            }

            if (explicitReasoning.isNotEmpty()) {
                reasoningAccumulator.append(explicitReasoning)
                listener.onReasoningDelta(explicitReasoning)
            }

            // 2. Standard content field (may contain inline <think> tags)
            val contentDelta = if (delta.has("content") && !delta.isNull("content")) {
                val c = delta.optString("content", "")
                if (c == "null") "" else c
            } else {
                ""
            }

            if (contentDelta.isNotEmpty()) {
                processContentWithPotentialInlineThinking(contentDelta)
            }

            // 3. Tool calls field (OpenAI / Claude function calling)
            if (delta.has("tool_calls") && !delta.isNull("tool_calls")) {
                val toolCallsArr = delta.optJSONArray("tool_calls")
                if (toolCallsArr != null) {
                    for (i in 0 until toolCallsArr.length()) {
                        val tcObj = toolCallsArr.optJSONObject(i) ?: continue
                        val tcId = tcObj.optString("id", "")
                        val fnObj = tcObj.optJSONObject("function")
                        val fnName = fnObj?.optString("name", "") ?: ""
                        val fnArgs = fnObj?.optString("arguments", "") ?: ""
                        if (fnName.isNotEmpty()) {
                            val header = "\n\n⚙️ **[MCP Tool Call: `$fnName`]**\n"
                            contentAccumulator.append(header)
                            listener.onContentDelta(header)
                            synchronized(completedToolCalls) {
                                completedToolCalls.add(CompletedToolCall(tcId, fnName, ""))
                            }
                        }
                        if (fnArgs.isNotEmpty()) {
                            contentAccumulator.append(fnArgs)
                            listener.onContentDelta(fnArgs)
                            synchronized(completedToolCalls) {
                                val last = completedToolCalls.lastOrNull()
                                if (last != null) {
                                    completedToolCalls[completedToolCalls.size - 1] = last.copy(argumentsJson = last.argumentsJson + fnArgs)
                                }
                            }
                        }
                    }
                }
            }

            if (delta.has("function_call") && !delta.isNull("function_call")) {
                val fnObj = delta.optJSONObject("function_call")
                val fnName = fnObj?.optString("name", "") ?: ""
                val fnArgs = fnObj?.optString("arguments", "") ?: ""
                if (fnName.isNotEmpty()) {
                    val header = "\n\n⚙️ **[MCP Tool Call: `$fnName`]**\n"
                    contentAccumulator.append(header)
                    listener.onContentDelta(header)
                    synchronized(completedToolCalls) {
                        completedToolCalls.add(CompletedToolCall("fn_call", fnName, ""))
                    }
                }
                if (fnArgs.isNotEmpty()) {
                    contentAccumulator.append(fnArgs)
                    listener.onContentDelta(fnArgs)
                    synchronized(completedToolCalls) {
                        val last = completedToolCalls.lastOrNull()
                        if (last != null) {
                            completedToolCalls[completedToolCalls.size - 1] = last.copy(argumentsJson = last.argumentsJson + fnArgs)
                        }
                    }
                }
            }

        } catch (e: Exception) {
            // Non-fatal chunk parsing error
        }
    }

    private fun getSanitizedContent(): String {
        var str = contentAccumulator.toString()
        while (str.startsWith("null")) {
            str = str.substring(4).trimStart()
        }
        return str
    }

    private fun getSanitizedReasoning(): String {
        var str = reasoningAccumulator.toString()
        while (str.startsWith("null")) {
            str = str.substring(4).trimStart()
        }
        return str
    }

    private fun processContentWithPotentialInlineThinking(rawChunk: String) {
        if (rawChunk.isEmpty() || rawChunk == "null") return
        inlineTagBuffer.append(rawChunk)
        var text = inlineTagBuffer.toString()

        while (text.isNotEmpty()) {
            if (!inInlineThinkingBlock) {
                val thinkStart = text.indexOf("<think>")
                val thoughtStart = text.indexOf("<thought>")

                val tagIdx = when {
                    thinkStart != -1 && thoughtStart != -1 -> minOf(thinkStart, thoughtStart)
                    thinkStart != -1 -> thinkStart
                    thoughtStart != -1 -> thoughtStart
                    else -> -1
                }

                if (tagIdx != -1) {
                    // Everything before the tag is normal content
                    if (tagIdx > 0) {
                        val before = text.substring(0, tagIdx)
                        contentAccumulator.append(before)
                        listener.onContentDelta(before)
                    }

                    inInlineThinkingBlock = true
                    val tagLen = if (text.startsWith("<think>", tagIdx)) 7 else 9
                    text = text.substring(tagIdx + tagLen)
                } else {
                    // Check if the end of text could be a partial opening tag (e.g. "<th", "<thought")
                    val partialTagStart = findPartialTagStart(text, listOf("<think>", "<thought>"))
                    if (partialTagStart != -1) {
                        if (partialTagStart > 0) {
                            val before = text.substring(0, partialTagStart)
                            contentAccumulator.append(before)
                            listener.onContentDelta(before)
                        }
                        inlineTagBuffer.setLength(0)
                        inlineTagBuffer.append(text.substring(partialTagStart))
                        return
                    } else {
                        contentAccumulator.append(text)
                        listener.onContentDelta(text)
                        text = ""
                    }
                }
            } else {
                // Inside thinking block
                val thinkEnd = text.indexOf("</think>")
                val thoughtEnd = text.indexOf("</thought>")

                val tagIdx = when {
                    thinkEnd != -1 && thoughtEnd != -1 -> minOf(thinkEnd, thoughtEnd)
                    thinkEnd != -1 -> thinkEnd
                    thoughtEnd != -1 -> thoughtEnd
                    else -> -1
                }

                if (tagIdx != -1) {
                    val thinkingPart = text.substring(0, tagIdx)
                    if (thinkingPart.isNotEmpty()) {
                        reasoningAccumulator.append(thinkingPart)
                        listener.onReasoningDelta(thinkingPart)
                    }

                    inInlineThinkingBlock = false
                    val tagLen = if (text.startsWith("</think>", tagIdx)) 8 else 10
                    text = text.substring(tagIdx + tagLen)
                } else {
                    // Check if end has partial closing tag (e.g. "</th")
                    val partialClose = findPartialTagStart(text, listOf("</think>", "</thought>"))
                    if (partialClose != -1) {
                        if (partialClose > 0) {
                            val before = text.substring(0, partialClose)
                            reasoningAccumulator.append(before)
                            listener.onReasoningDelta(before)
                        }
                        inlineTagBuffer.setLength(0)
                        inlineTagBuffer.append(text.substring(partialClose))
                        return
                    } else {
                        reasoningAccumulator.append(text)
                        listener.onReasoningDelta(text)
                        text = ""
                    }
                }
            }
        }

        inlineTagBuffer.setLength(0)
    }

    private fun findPartialTagStart(str: String, fullTags: List<String>): Int {
        for (i in 1..str.length.coerceAtMost(9)) {
            val tail = str.substring(str.length - i)
            if (fullTags.any { it.startsWith(tail) && it != tail }) {
                return str.length - i
            }
        }
        return -1
    }

    private fun flushLingeringTagBuffer() {
        if (inlineTagBuffer.isNotEmpty()) {
            val lingering = inlineTagBuffer.toString()
            inlineTagBuffer.setLength(0)
            if (inInlineThinkingBlock) {
                reasoningAccumulator.append(lingering)
                listener.onReasoningDelta(lingering)
            } else {
                contentAccumulator.append(lingering)
                listener.onContentDelta(lingering)
            }
        }
    }

    private fun handleJsonError(rawJson: String) {
        try {
            val json = JSONObject(rawJson)
            val errObj = json.optJSONObject("error")
            val errMsg = errObj?.optString("message") ?: "Error de API"
            flushLingeringTagBuffer()
            isCompleted = true
            listener.onError(RuntimeException(errMsg))
        } catch (ignored: Exception) {
            listener.onError(RuntimeException("Error inesperado en stream: " + rawJson))
        }
    }
}
