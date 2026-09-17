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
        """(?:\r?\n){0,2}(?:([✅❌]))?\s*\**\[?(?:Resultado MCP|MCP Result|Resultado Herramienta|Tool Result):\s*`?([a-zA-Z0-9_.:/-]+)`?\]?\**""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_CODE_PATTERN = Regex(
        """```([a-zA-Z0-9_+-]*)\n([\s\S]*?)\n```"""
    )

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

    fun parse(content: String): ParsedToolCode {
        if (content.isBlank()) {
            return ParsedToolCode(hasToolOrCode = false, cleanContent = content)
        }

        var working = content.trim()
        val toolNames = linkedSetOf<String>()
        var toolArgs = ""
        var toolResult = ""
        var statusBadge = ""
        var hasTool = false

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

        if (hasTool) {
            val combined = StringBuilder()
            if (toolArgs.isNotBlank()) {
                combined.append("// Argumentos:\n").append(toolArgs)
            }
            if (toolResult.isNotBlank()) {
                if (combined.isNotEmpty()) combined.append("\n\n")
                combined.append("// Resultado:\n").append(toolResult)
            }
            val primaryTool = toolNames.firstOrNull() ?: "Herramienta"
            val finalCode = if (combined.isNotEmpty()) combined.toString() else "Herramienta: " + primaryTool
            val tagTitle = if (toolNames.size > 1) {
                "⚙️ Herramientas (" + toolNames.size + "): " + toolNames.joinToString(", ")
            } else {
                "⚙️ Herramienta: " + primaryTool
            }
            val fallbackMsg = if (statusBadge.contains("✅")) {
                "Ejecución de herramienta '" + primaryTool + "' completada."
            } else if (statusBadge.contains("❌")) {
                "Ejecución de herramienta '" + primaryTool + "' cancelada o fallida."
            } else {
                "Ejecutando herramienta '" + primaryTool + "'..."
            }

            return ParsedToolCode(
                hasToolOrCode = true,
                tagTitle = tagTitle,
                statusBadge = statusBadge,
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