package com.codex.chat.core.parser

import org.json.JSONObject

class SseStreamParser(
    private val listener: SseEventListener,
    private val requestStartTime: Long = System.currentTimeMillis()
) {

    init {
        // Nueva generación: limpiar huellas de imágenes del stream anterior para que
        // la deduplicación por similitud solo compare imágenes de ESTA respuesta.
        com.codex.chat.core.media.GeneratedMediaStorage.resetRecentImageFingerprints()
    }

    data class CompletedToolCall(
        val id: String,
        val name: String,
        val argumentsJson: String
    )

    interface SseEventListener {
        fun onReasoningDelta(delta: String)
        fun onContentDelta(delta: String)
        fun onComplete(fullContent: String, fullReasoning: String)
        fun onCompleteWithMetrics(
            fullContent: String,
            fullReasoning: String,
            metrics: com.codex.chat.core.metrics.StreamMetrics
        ) {
            onComplete(fullContent, fullReasoning)
        }
        fun onToolCallsReceived(toolCalls: List<CompletedToolCall>) {}
        fun onError(error: Throwable)
    }

    private val completedToolCalls = mutableListOf<CompletedToolCall>()
    fun getCompletedToolCalls(): List<CompletedToolCall> = synchronized(completedToolCalls) { completedToolCalls.toList() }

    private val contentAccumulator = StringBuilder()
    private val reasoningAccumulator = StringBuilder()
    private val lineBuffer = StringBuilder()
    private val inlineTagBuffer = StringBuilder()

    private var serverPromptTokens = 0
    private var serverCompletionTokens = 0
    private var serverTotalTokens = 0

    // Deduplicador de imágenes por stream: evita que proxies que repiten delta.images
    // en chunks intermedios o finales inyecten la misma imagen múltiples veces.
    private val processedImageUris = mutableSetOf<String>()

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
        ensureToolCallBlocksClosed()

        dispatchCompletion()
    }

    private var firstContentTokenTime: Long = 0L

    private fun markContentStarted() {
        if (firstContentTokenTime == 0L) {
            firstContentTokenTime = System.currentTimeMillis()
        }
    }

    private fun dispatchCompletion() {
        if (!isCompleted) {
            isCompleted = true
            val content = getSanitizedContent()
            val reasoning = getSanitizedReasoning()
            val now = System.currentTimeMillis()
            val durationMs = (now - requestStartTime).coerceAtLeast(1L)
            val thinkingDurationMs = if (firstContentTokenTime > 0L) {
                (firstContentTokenTime - requestStartTime).coerceAtLeast(0L)
            } else {
                0L
            }
            val generationDurationMs = if (firstContentTokenTime > 0L) {
                (now - firstContentTokenTime).coerceAtLeast(1L)
            } else {
                durationMs
            }

            val effectiveCompletionTokens = if (serverCompletionTokens > 0) {
                serverCompletionTokens
            } else {
                com.codex.chat.core.metrics.TokenEstimator.estimateTokens(content + " " + reasoning)
            }
            val effectivePromptTokens = serverPromptTokens
            val effectiveTotalTokens = if (serverTotalTokens > 0) {
                serverTotalTokens
            } else {
                effectivePromptTokens + effectiveCompletionTokens
            }
            // Real generation speed decoupled from upfront reasoning latency
            val tpsDuration = if (generationDurationMs > 0L) generationDurationMs else durationMs
            val tps = com.codex.chat.core.metrics.TokenEstimator.calculateTps(effectiveCompletionTokens, tpsDuration)

            val metrics = com.codex.chat.core.metrics.StreamMetrics(
                durationMs = durationMs,
                thinkingDurationMs = thinkingDurationMs,
                generationDurationMs = generationDurationMs,
                promptTokens = effectivePromptTokens,
                completionTokens = effectiveCompletionTokens,
                totalTokens = effectiveTotalTokens,
                tokensPerSecond = tps
            )

            listener.onCompleteWithMetrics(content, reasoning, metrics)
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
            ensureToolCallBlocksClosed()
            dispatchCompletion()
            return
        }

        val payloadTrimmed = dataContent.trim()
        if (!payloadTrimmed.startsWith("{")) {
            return
        }

        try {
            val json = JSONObject(dataContent)

            // Parse token usage if reported by upstream server
            if (json.has("usage")) {
                val usageObj = json.optJSONObject("usage")
                if (usageObj != null) {
                    serverPromptTokens = usageObj.optInt("prompt_tokens", serverPromptTokens)
                    serverCompletionTokens = usageObj.optInt("completion_tokens", serverCompletionTokens)
                    serverTotalTokens = usageObj.optInt("total_tokens", serverTotalTokens)
                }
            }

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
                !delta.isNull("reasoning_content") -> delta.safeString("reasoning_content")
                !delta.isNull("reasoning") -> delta.safeString("reasoning")
                else -> ""
            }

            if (explicitReasoning.isNotEmpty()) {
                reasoningAccumulator.append(explicitReasoning)
                listener.onReasoningDelta(explicitReasoning)
            }

            // 2. Standard content field (may contain inline <think> tags)
            val contentDelta = if (!delta.isNull("content")) delta.safeString("content") else ""

            if (contentDelta.isNotEmpty()) {
                processContentWithPotentialInlineThinking(contentDelta)
            }

            // 3. Imágenes generadas por el modelo (extension delta.images de Gemini/Imagen vía proxy).
            //    El proxy CLIProxyAPI devuelve las imágenes en delta.images[] como data-URL base64;
            //    sin este bloque la APK las descarta silenciosamente y el usuario no ve nada.
            if (!delta.isNull("images")) {
                val imagesArr = delta.optJSONArray("images")
                if (imagesArr != null) {
                    for (i in 0 until imagesArr.length()) {
                        val imgObj = imagesArr.optJSONObject(i) ?: continue
                        // Formato estándar: {"type": "image_url", "image_url": {"url": "data:image/jpeg;base64,...."}}
                        var url = imgObj.optJSONObject("image_url")?.safeString("url") ?: ""
                        if (url.isBlank()) url = imgObj.safeString("url")
                        if (url.isBlank()) url = imgObj.safeString("b64_json")
                        if (url.isBlank()) continue

                        val dataUrl = if (url.startsWith("data:")) {
                            url
                        } else {
                            // b64_json plano -> normalizar a data-URL
                            "data:image/png;base64,$url"
                        }

                        // FIX CRÍTICO (anti-congelamiento y anti-duplicados):
                        // Guardar la imagen en disco de forma determinista (SHA-256) en el hilo de red de OkHttp.
                        val targetUri = com.codex.chat.core.media.GeneratedMediaStorage.saveBase64Image(dataUrl)

                        // Si el proxy repite la imagen en el chunk final (finish_reason: stop), ignorar el duplicado
                        if (processedImageUris.contains(targetUri)) {
                            continue
                        }
                        processedImageUris.add(targetUri)

                        val marker = "\n\n![imagen-generada]($targetUri)\n\n"
                        markContentStarted()
                        contentAccumulator.append(marker)
                        listener.onContentDelta(marker)
                    }
                }
            }

            // 4. Tool calls field (OpenAI / Claude function calling)
            if (!delta.isNull("tool_calls")) {
                val toolCallsArr = delta.optJSONArray("tool_calls")
                if (toolCallsArr != null) {
                    for (i in 0 until toolCallsArr.length()) {
                        val tcObj = toolCallsArr.optJSONObject(i) ?: continue
                        val tcId = tcObj.safeString("id")
                        val fnObj = tcObj.optJSONObject("function")
                        val fnName = fnObj?.safeString("name") ?: ""
                        val fnArgs = fnObj?.safeString("arguments") ?: ""
                        if (fnName.isNotEmpty()) {
                            val header = "\n\n⚙️ **[MCP Tool Call: `$fnName`]**\n```json\n"
                            markContentStarted()
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

            if (!delta.isNull("function_call")) {
                val fnObj = delta.optJSONObject("function_call")
                val fnName = fnObj?.safeString("name") ?: ""
                val fnArgs = fnObj?.safeString("arguments") ?: ""
                if (fnName.isNotEmpty()) {
                    val header = "\n\n⚙️ **[MCP Tool Call: `$fnName`]**\n```json\n"
                    markContentStarted()
                    contentAccumulator.append(header)
                    listener.onContentDelta(header)
                    synchronized(completedToolCalls) {
                        // ID protocolar válido y único (el legacy "fn_call" rompía la correlación role:"tool").
                        completedToolCalls.add(CompletedToolCall("call-" + java.util.UUID.randomUUID().toString().take(8), fnName, ""))
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

    private fun JSONObject.safeString(key: String, fallback: String = ""): String {
        if (this.isNull(key)) return fallback
        return this.optString(key, fallback)
    }

    private fun getSanitizedContent(): String {
        return contentAccumulator.toString()
    }

    private fun getSanitizedReasoning(): String {
        return reasoningAccumulator.toString()
    }

    private fun processContentWithPotentialInlineThinking(rawChunk: String) {
        if (rawChunk.isEmpty()) return
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
                        markContentStarted()
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
                            markContentStarted()
                            contentAccumulator.append(before)
                            listener.onContentDelta(before)
                        }
                        inlineTagBuffer.setLength(0)
                        inlineTagBuffer.append(text.substring(partialTagStart))
                        return
                    } else {
                        if (text.isNotEmpty()) {
                            markContentStarted()
                        }
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

    private fun ensureToolCallBlocksClosed() {
        if (completedToolCalls.isNotEmpty() && contentAccumulator.contains("```json") && !contentAccumulator.endsWith("```\n") && !contentAccumulator.endsWith("```")) {
            contentAccumulator.append("\n```\n")
            listener.onContentDelta("\n```\n")
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
