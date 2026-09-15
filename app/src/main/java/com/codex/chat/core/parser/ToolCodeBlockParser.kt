package com.codex.chat.core.parser

data class ParsedToolCode(
    val hasToolOrCode: Boolean,
    val tagTitle: String = "",
    val statusBadge: String = "",
    val codeContent: String = "",
    val cleanContent: String = ""
)

object ToolCodeBlockParser {

    private val MCP_CALL_PATTERN = Regex(
        """(?:\r?\n){0,2}(?:⚙️|🔧)\s*\**\[?(?:MCP Tool Call|Llamada MCP|Herramienta):\s*`?([a-zA-Z0-9_.-]+)`?\]?\**\s*(?:```(?:json)?\r?\n([\s\S]*?)\r?\n```|(\{[\s\S]*?\})|([^\n\r]+))""",
        RegexOption.IGNORE_CASE
    )

    private val MCP_RESULT_PATTERN = Regex(
        """(?:\r?\n){0,2}(?:([✅❌]))\s*\**\[?(?:Resultado MCP):\s*`?([a-zA-Z0-9_.-]+)`?\]?\**\s*```(?:json|text)?\r?\n([\s\S]*?)\r?\n```""",
        RegexOption.IGNORE_CASE
    )

    private val GENERIC_CODE_PATTERN = Regex(
        """```([a-zA-Z0-9_+-]*)\n([\s\S]*?)\n```"""
    )

    fun parse(content: String): ParsedToolCode {
        if (content.isBlank()) {
            return ParsedToolCode(hasToolOrCode = false, cleanContent = content)
        }

        var working = content.trim()
        var toolName = ""
        var toolArgs = ""
        var toolResult = ""
        var statusBadge = ""
        var hasTool = false

        // 1. Detectar Resultado MCP
        val resMatch = MCP_RESULT_PATTERN.find(working)
        if (resMatch != null) {
            hasTool = true
            val icon = resMatch.groupValues[1]
            toolName = resMatch.groupValues[2]
            toolResult = resMatch.groupValues[3].trim()
            statusBadge = if (icon == "❌") "[❌ Error / Rechazado]" else "[✅ Completado]"
            working = working.replace(resMatch.value, "").trim()
        }

        // 2. Detectar Llamada MCP (Tool Call)
        val callMatch = MCP_CALL_PATTERN.find(working)
        if (callMatch != null) {
            hasTool = true
            if (toolName.isBlank()) {
                toolName = callMatch.groupValues[1]
            }
            val argGroup = callMatch.groupValues[2].ifBlank {
                callMatch.groupValues[3].ifBlank { callMatch.groupValues[4] }
            }
            toolArgs = argGroup.trim()
            if (statusBadge.isBlank()) {
                statusBadge = "[⚙️ Ejecutando...]"
            }
            working = working.replace(callMatch.value, "").trim()
        }

        // 3. Limpiar restos de argumentos JSON sueltos, delimitadores triples huérfanos o cabeceras rotas
        working = working.replace(Regex("^\\s*```(?:[a-zA-Z0-9_-]+)?\\s*$", RegexOption.MULTILINE), "").trim()
        working = working.replace(Regex("^\\s*\\{[\\s\\S]*?\\}\\s*$", RegexOption.MULTILINE), "").trim()
        working = working.replace(Regex("^\\s*(?:\"content\":\\s*\"?|\",?\"file_path\":\\s*\"[^\"]*\"\\s*\\}?)\\s*$", RegexOption.MULTILINE), "").trim()
        if (hasTool) {
            val combined = StringBuilder()
            if (toolArgs.isNotBlank()) {
                combined.append("// Argumentos:\n").append(toolArgs)
            }
            if (toolResult.isNotBlank()) {
                if (combined.isNotEmpty()) combined.append("\n\n")
                combined.append("// Resultado:\n").append(toolResult)
            }
            val finalCode = if (combined.isNotEmpty()) combined.toString() else "Herramienta: " + toolName
            val fallbackMsg = if (statusBadge.contains("✅")) {
                "Ejecución de herramienta '" + toolName + "' completada."
            } else if (statusBadge.contains("❌")) {
                "Ejecución de herramienta '" + toolName + "' cancelada o fallida."
            } else {
                "Ejecutando herramienta '" + toolName + "'..."
            }

            return ParsedToolCode(
                hasToolOrCode = true,
                tagTitle = "⚙️ Herramienta: " + toolName,
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
            val lang = codeMatch.groupValues[1].ifBlank { "código" }
            val code = codeMatch.groupValues[2].trim()
            val lineCount = code.lines().size

            if (lineCount >= 3) {
                val clean = content.replace(codeMatch.value, "").trim()
                return ParsedToolCode(
                    hasToolOrCode = true,
                    tagTitle = "💻 Bloque de Código (" + lang + " · " + lineCount + " líneas)",
                    statusBadge = "[▸ Código]",
                    codeContent = code,
                    cleanContent = clean.ifBlank { "Fragmento de código (" + lang + "):" }
                )
            }
        }

        return ParsedToolCode(hasToolOrCode = false, cleanContent = content)
    }
}