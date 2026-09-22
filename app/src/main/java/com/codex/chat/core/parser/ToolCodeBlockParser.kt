package com.codex.chat.core.parser

data class ParsedToolCode(
    val hasToolOrCode: Boolean,
    val tagTitle: String = "",
    val statusBadge: String = "",
    val codeContent: String = "",
    val cleanContent: String = ""
)

object ToolCodeBlockParser {

    val MCP_CALL_HEADER_REGEX = Regex(
        """(?:\r?\n){0,2}(?:⚙️|🔧)?\s*\**\[?(?:MCP Tool Call|Llamada MCP|Herramienta|Tool Call):\s*`?([a-zA-Z0-9_.:/-]+)`?\]?\**""",
        RegexOption.IGNORE_CASE
    )

    val MCP_RESULT_HEADER_REGEX = Regex(
        """(?:\r?\n){0,2}(?:([✅❌✓]))?\s*\**\[?(?:Resultado MCP|MCP Result|Resultado Herramienta|Tool Result|Resultado):\s*`?([a-zA-Z0-9_.:/-]+)`?\]?\**""",
        RegexOption.IGNORE_CASE
    )

    val MCP_EXECUTION_NOTICE_REGEX = Regex(
        """(?:\r?\n){0,2}(?:⚙️|🔧)?\s*\**Ejecutando\s+(\d+)\s+herramienta\(s\)\s+MCP:\**\s*`?([^\n`]+)`?…*""",
        RegexOption.IGNORE_CASE
    )

    val MCP_SYNTHESIS_NOTICE_REGEX = Regex(
        """(?:\r?\n){0,2}⚡\s*\**Sintetizando\s+respuesta[^\n*]*\**(?:?\n)?""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_CODE_PATTERN = Regex(
        """```([a-zA-Z0-9_+-]*)\n([\s\S]*?)\n```"""
    )

    /**
     * Reemplaza cadenas base64 gigantescas de capturas de pantalla móviles para
     * no saturar la vista previa del código en el chat.
     */
    fun sanitizeForDisplay(text: String): String {
        if (!text.contains("screenshot_base64")) return text
        return try {
            // Reemplazo regex rápido para bloques JSON de salida
            text.replace(
                Regex(""""screenshot_base64"\s*:\s*"[^"]+""""),
                """"screenshot_base64": "<Base64 JPEG (entregado al modelo)>""""
            )
        } catch (_: Throwable) {
            text
        }
    }

    /**
     * Poda proactiva de resultados de herramientas para conservar la ventana de contexto (Hermes/Apex standard: 16k/8k/4k).
     * Dispara si el resultado supera maxChars (16.384 caracteres), conservando los primeros headChars (8.192)
     * y los últimos tailChars (4.096), insertando un marcador informativo en el centro.
     */
    fun pruneToolResult(
        result: String,
        maxChars: Int = 16384,
        headChars: Int = 8192,
        tailChars: Int = 4096
    ): String {
        if (result.length <= maxChars) return result
        val prunedChars = result.length - headChars - tailChars
        if (prunedChars <= 0) return result
        val head = result.substring(0, headChars)
        val tail = result.substring(result.length - tailChars)
        return "$head\n\n... [PRUNED $prunedChars CHARS - RESULT TRUNCATED PROACTIVELY TO CONSERVE CONTEXT] ...\n\n$tail"
    }

    /**
     * Detección ultra-rápida de presencia de herramientas para evitar bypass en streaming fast-path.
     */
    fun hasToolMarkers(text: CharSequence?): Boolean {
        if (text.isNullOrEmpty()) return false
        return text.contains("Tool Call", ignoreCase = true) ||
               text.contains("Resultado MCP", ignoreCase = true) ||
               text.contains("Llamada MCP", ignoreCase = true) ||
               text.contains("MCP Result", ignoreCase = true) ||
               text.contains("Herramienta:", ignoreCase = true) ||
               text.contains("Tool Result", ignoreCase = true) ||
               text.contains("Resultado:", ignoreCase = true) ||
               text.contains("Ejecutando", ignoreCase = true) ||
               text.contains("Sintetizando", ignoreCase = true) ||
               text.contains("⚙️") ||
               text.contains("🔧")
    }

    private data class ExtractedCall(
        val toolName: String,
        val toolArgs: String,
        val range: IntRange
    )

    private data class ExtractedResult(
        val icon: String,
        val toolName: String,
        val toolResult: String,
        val range: IntRange
    )

    private fun extractToolCall(text: String): ExtractedCall? {
        val headerMatch = MCP_CALL_HEADER_REGEX.find(text) ?: return null
        val toolName = headerMatch.groupValues[1]
        val startIdx = headerMatch.range.first
        val afterHeaderIdx = headerMatch.range.last + 1

        val nextCall = MCP_CALL_HEADER_REGEX.find(text, afterHeaderIdx)
        val nextResult = MCP_RESULT_HEADER_REGEX.find(text, afterHeaderIdx)
        val searchBound = listOfNotNull(nextCall?.range?.first, nextResult?.range?.first).minOrNull() ?: text.length

        val blockSegment = text.substring(afterHeaderIdx, searchBound)
        val codeBlockOpen = blockSegment.indexOf("```")

        val toolArgs: String
        val matchEnd: Int

        if (codeBlockOpen != -1) {
            val afterCodeOpen = blockSegment.indexOf('\n', codeBlockOpen)
            val argsStart = if (afterCodeOpen != -1) afterCodeOpen + 1 else codeBlockOpen + 3
            val codeBlockClose = blockSegment.indexOf("```", argsStart)
            if (codeBlockClose != -1) {
                toolArgs = blockSegment.substring(argsStart, codeBlockClose).trim()
                val afterClose = codeBlockClose + 3
                val endOfLine = blockSegment.indexOf('\n', afterClose)
                matchEnd = afterHeaderIdx + (if (endOfLine != -1) endOfLine + 1 else afterClose)
            } else {
                toolArgs = blockSegment.substring(argsStart).trim()
                matchEnd = searchBound
            }
        } else {
            val openBrace = blockSegment.indexOf('{')
            if (openBrace != -1) {
                val closeBrace = blockSegment.lastIndexOf('}')
                if (closeBrace != -1 && closeBrace > openBrace) {
                    toolArgs = blockSegment.substring(openBrace, closeBrace + 1).trim()
                    val endOfLine = blockSegment.indexOf('\n', closeBrace + 1)
                    matchEnd = afterHeaderIdx + (if (endOfLine != -1) endOfLine + 1 else closeBrace + 1)
                } else {
                    toolArgs = blockSegment.substring(openBrace).trim()
                    matchEnd = searchBound
                }
            } else {
                val firstLineEnd = blockSegment.indexOf('\n')
                val lineEnd = if (firstLineEnd != -1) firstLineEnd + 1 else blockSegment.length
                toolArgs = blockSegment.substring(0, lineEnd).trim()
                matchEnd = afterHeaderIdx + lineEnd
            }
        }

        return ExtractedCall(toolName, toolArgs, startIdx until matchEnd.coerceAtMost(text.length))
    }

    private fun extractToolResult(text: String): ExtractedResult? {
        val headerMatch = MCP_RESULT_HEADER_REGEX.find(text) ?: return null
        val icon = headerMatch.groupValues[1].ifBlank { "✅" }
        val toolName = headerMatch.groupValues[2]
        val startIdx = headerMatch.range.first
        val afterHeaderIdx = headerMatch.range.last + 1

        val nextCall = MCP_CALL_HEADER_REGEX.find(text, afterHeaderIdx)
        val nextResult = MCP_RESULT_HEADER_REGEX.find(text, afterHeaderIdx)
        val searchBound = listOfNotNull(nextCall?.range?.first, nextResult?.range?.first).minOrNull() ?: text.length

        val blockSegment = text.substring(afterHeaderIdx, searchBound)
        val codeBlockOpen = blockSegment.indexOf("```")

        val toolResult: String
        val matchEnd: Int

        if (codeBlockOpen != -1) {
            val afterCodeOpen = blockSegment.indexOf('\n', codeBlockOpen)
            val resStart = if (afterCodeOpen != -1) afterCodeOpen + 1 else codeBlockOpen + 3
            val codeBlockClose = blockSegment.indexOf("```", resStart)
            if (codeBlockClose != -1) {
                toolResult = blockSegment.substring(resStart, codeBlockClose).trim()
                val afterClose = codeBlockClose + 3
                val endOfLine = blockSegment.indexOf('\n', afterClose)
                matchEnd = afterHeaderIdx + (if (endOfLine != -1) endOfLine + 1 else afterClose)
            } else {
                toolResult = blockSegment.substring(resStart).trim()
                matchEnd = searchBound
            }
        } else {
            val openBrace = blockSegment.indexOf('{')
            if (openBrace != -1) {
                val closeBrace = blockSegment.lastIndexOf('}')
                if (closeBrace != -1 && closeBrace > openBrace) {
                    toolResult = blockSegment.substring(openBrace, closeBrace + 1).trim()
                    val endOfLine = blockSegment.indexOf('\n', closeBrace + 1)
                    matchEnd = afterHeaderIdx + (if (endOfLine != -1) endOfLine + 1 else closeBrace + 1)
                } else {
                    toolResult = blockSegment.substring(openBrace).trim()
                    matchEnd = searchBound
                }
            } else {
                val firstLineEnd = blockSegment.indexOf('\n')
                val lineEnd = if (firstLineEnd != -1) firstLineEnd + 1 else blockSegment.length
                toolResult = blockSegment.substring(0, lineEnd).trim()
                matchEnd = afterHeaderIdx + lineEnd
            }
        }

        return ExtractedResult(icon, toolName, toolResult, startIdx until matchEnd.coerceAtMost(text.length))
    }

    // Caché LRU de memoization estilo Valdi/memo para evitar re-ejecutar regexes costosas en streams idénticos
    private val parseMemoCache = object : java.util.LinkedHashMap<String, ParsedToolCode>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ParsedToolCode>?): Boolean {
            return size > 64
        }
    }

    fun parse(content: String): ParsedToolCode {
        if (content.isBlank()) {
            return ParsedToolCode(hasToolOrCode = false, cleanContent = content)
        }

        synchronized(parseMemoCache) {
            val cached = parseMemoCache[content]
            if (cached != null) return cached
        }

        val result = internalParse(content)
        synchronized(parseMemoCache) {
            parseMemoCache[content] = result
        }
        return result
    }

    private fun internalParse(content: String): ParsedToolCode {
        var working = content.trim()
        val toolNames = linkedSetOf<String>()
        var toolArgs = ""
        var toolResult = ""
        var statusBadge = ""
        var hasTool = false
        val executionNotices = mutableListOf<String>()

        // 0. Extraer avisos de progreso de ejecución MCP y síntesis
        val matchesExecution = MCP_EXECUTION_NOTICE_REGEX.findAll(working).toList()
        for (m in matchesExecution) {
            hasTool = true
            val noticeTools = m.groupValues[2].split(",").map { it.trim().removeSurrounding("`") }
            toolNames.addAll(noticeTools)
            executionNotices.add(m.value.trim())
        }
        working = MCP_EXECUTION_NOTICE_REGEX.replace(working, "").trim()

        val hasSynthesis = MCP_SYNTHESIS_NOTICE_REGEX.containsMatchIn(working)
        working = MCP_SYNTHESIS_NOTICE_REGEX.replace(working, "").trim()

        // 1. Extraer Resultados MCP (completos o en progreso)
        while (true) {
            val res = extractToolResult(working) ?: break
            hasTool = true
            toolNames.add(res.toolName)
            if (toolResult.isNotBlank()) toolResult += "\n\n"
            toolResult += res.toolResult
            if (statusBadge.isBlank()) {
                statusBadge = if (res.icon == "❌") "[❌ Error / Rechazado]" else "[✅ Completado]"
            }
            working = (working.substring(0, res.range.first) + working.substring(res.range.last + 1)).trim()
        }

        // 2. Extraer Llamadas MCP (Tool Calls, completos o en progreso streaming)
        while (true) {
            val call = extractToolCall(working) ?: break
            hasTool = true
            toolNames.add(call.toolName)
            if (toolArgs.isNotBlank()) toolArgs += "\n\n"
            toolArgs += call.toolArgs
            if (statusBadge.isBlank()) {
                statusBadge = "[⚙️ Ejecutando...]"
            }
            working = (working.substring(0, call.range.first) + working.substring(call.range.last + 1)).trim()
        }

        // 3. Limpiar restos de JSON sueltos huérfanos o bloques de código vacíos residuales
        working = working.replace(Regex("(?m)^\\s*```(?:json|text)?\\s*```\\s*$"), "").trim()
        working = working.replace(Regex("^\\s*\\{[\\s\\S]*?\\}\\s*$", RegexOption.MULTILINE), "").trim()
        working = working.replace(Regex("^\\s*(?:\"content\":\\s*\"?|\",?\"file_path\":\\s*\"[^\"]*\"\\s*\\}?)\\s*$", RegexOption.MULTILINE), "").trim()
        working = working.replace(Regex("(?m)^\\s*```(?:json|text)?\\s*```\\s*$"), "").trim()
        working = working.replace(Regex("(?:\\r?\\n){3,}"), "\n\n").trim()

        if (hasTool || executionNotices.isNotEmpty()) {
            val combined = StringBuilder()
            for (notice in executionNotices) {
                combined.append(notice).append("\n\n")
            }
            if (toolArgs.isNotBlank()) {
                combined.append("// Argumentos:\n").append(toolArgs).append("\n\n")
            }
            if (toolResult.isNotBlank()) {
                combined.append("// Resultado:\n").append(toolResult).append("\n\n")
            }
            if (hasSynthesis) {
                combined.append("⚡ Sintetizando respuesta con los datos obtenidos…\n")
            }
            val primaryTool = toolNames.firstOrNull() ?: "Herramienta"
            val rawCode = if (combined.isNotEmpty()) combined.toString().trim() else "Herramienta: " + primaryTool
            val finalCode = sanitizeForDisplay(rawCode)
            val tagTitle = if (toolNames.size > 1) {
                "⚙️ Herramientas (" + toolNames.size + "): " + toolNames.joinToString(", ")
            } else {
                "⚙️ Herramienta: " + primaryTool
            }
            val computedBadge = when {
                statusBadge.isNotBlank() -> statusBadge
                working.isBlank() -> "[⚙️ Ejecutando...]"
                else -> "[✅ Completado]"
            }

            val fallbackMsg = if (computedBadge.contains("✅")) {
                "Ejecución de herramienta '" + primaryTool + "' completada."
            } else if (computedBadge.contains("❌")) {
                "Ejecución de herramienta '" + primaryTool + "' cancelada o fallida."
            } else {
                "Ejecutando herramienta '" + primaryTool + "'..."
            }

            return ParsedToolCode(
                hasToolOrCode = true,
                tagTitle = tagTitle,
                statusBadge = computedBadge,
                codeContent = finalCode,
                cleanContent = working.ifBlank { fallbackMsg }
            )
        }

        // 4. Verificar si es salida de Python E2B Cloud
        if (content.contains("Salida E2B Cloud")) {
            val codeMatch = GENERIC_CODE_PATTERN.find(content)
            val code = codeMatch?.groupValues?.get(2)?.trim() ?: content.trim()
            val clean = content.replace(codeMatch?.value ?: "", "")
                .replace("### 🐍 Salida E2B Cloud (MicroVM en la nube)", "")
                .trim()
            return ParsedToolCode(
                hasToolOrCode = true,
                tagTitle = "🐍 Código E2B Cloud (MicroVM)",
                statusBadge = "[⚡ Salida]",
                codeContent = code,
                cleanContent = clean.ifBlank { "Ejecución en MicroVM Cloud finalizada." }
            )
        }

        // 5. Bloques de código genéricos (>= 3 líneas)
        val codeMatch = GENERIC_CODE_PATTERN.find(content)
        if (codeMatch != null) {
            val lang = codeMatch.groupValues[1].lowercase().trim()
            val code = codeMatch.groupValues[2].trim()

            // Si es un artefacto visual interactivo (Mermaid, SVG, o HTML con Canvas/SVG/Doctype),
            // debe ser procesado por VisualMediaParser, NO capturado como bloque de código genérico.
            val isVisualArtifact = lang == "mermaid" ||
                lang == "svg" ||
                (lang == "html" && (code.contains("<canvas", ignoreCase = true) || code.contains("<svg", ignoreCase = true) || code.contains("<!DOCTYPE", ignoreCase = true))) ||
                code.startsWith("<svg", ignoreCase = true) ||
                code.startsWith("<!DOCTYPE", ignoreCase = true)

            if (!isVisualArtifact) {
                val lineCount = code.lines().size
                if (lineCount >= 3) {
                    val clean = content.replace(codeMatch.value, "").trim()
                    val displayLang = if (lang.isNotBlank()) lang else "código"
                    return ParsedToolCode(
                        hasToolOrCode = true,
                        tagTitle = "💻 Bloque de Código (" + displayLang + " · " + lineCount + " líneas)",
                        statusBadge = "[▸ Código]",
                        codeContent = code,
                        cleanContent = clean.ifBlank { "Fragmento de código (" + displayLang + "):" }
                    )
                }
            }
        }

        return ParsedToolCode(hasToolOrCode = false, cleanContent = content)
    }
}