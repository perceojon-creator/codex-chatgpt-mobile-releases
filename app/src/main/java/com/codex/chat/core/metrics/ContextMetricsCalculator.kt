package com.codex.chat.core.metrics

import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.SkillInfo
import com.codex.chat.core.model.SubagentInfo
import com.codex.chat.core.network.CodexPayloadBuilder
import org.json.JSONArray
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

data class ContextBreakdown(
    val systemPromptTokens: Int,
    val toolSchemasTokens: Int,
    val toolResponsesTokens: Int,
    val userMessagesTokens: Int,
    val assistantMessagesTokens: Int,
    val totalContextUsed: Int,
    val contextWindowLimit: Int,
    val userMessagesCount: Int,
    val assistantMessagesCount: Int,
    val toolCallsCount: Int,
    val activeToolsCount: Int,
    val modelDisplayName: String,
    val modelProvider: String
) {
    val totalToolsTokens: Int get() = toolSchemasTokens + toolResponsesTokens
    val percentUsed: Double
        get() = if (contextWindowLimit > 0) {
            ((totalContextUsed.toDouble() / contextWindowLimit.toDouble()) * 100.0).coerceIn(0.0, 100.0)
        } else 0.0

    val remainingTokens: Int
        get() = (contextWindowLimit - totalContextUsed).coerceAtLeast(0)
}

object ContextMetricsCalculator {

    fun calculate(
        messages: List<ChatMessage>,
        activeModel: ModelInfo,
        activeSkill: SkillInfo? = null,
        activeSubagent: SubagentInfo? = null,
        mcpRegistry: McpRegistry? = null
    ): ContextBreakdown {
        // 1. System Prompt Tokens
        val systemPromptText = CodexPayloadBuilder.buildSystemPrompt(
            activeSubagent = activeSubagent,
            activeSkill = activeSkill,
            webGrounding = "",
            mcpRegistry = mcpRegistry,
            provider = activeModel.provider
        )
        // System prompt contains rich prose and instructions: ~3.9 chars per token (BPE / cl100k / o200k)
        val systemTokens = estimateTextTokens(systemPromptText)

        // 2. Tool Schemas Tokens
        val activeTools = mcpRegistry?.getAllActiveTools() ?: emptyList()
        val toolsJson = JSONArray()
        for (t in activeTools) {
            toolsJson.put(t.toOpenAiToolSchema())
        }
        val toolsJsonStr = toolsJson.toString()
        // JSON schemas have high redundancy of keys: ~4.4 chars per token
        val toolSchemasTokens = estimateJsonTokens(toolsJsonStr)

        // 3. Conversation Messages Tokens Breakdown
        var userTokens = 0
        var assistantTokens = 0
        var toolResponsesTokens = 0
        var userCount = 0
        var assistantCount = 0
        var toolExecCount = 0

        for (msg in messages) {
            when (msg.role) {
                MessageRole.USER -> {
                    userCount++
                    var tokens = estimateTextTokens(msg.content)
                    for (att in msg.attachments) {
                        tokens += if (att.isImage) 1600 else estimateTextTokens(att.fileName) + 150
                    }
                    userTokens += tokens
                }
                MessageRole.ASSISTANT -> {
                    assistantCount++
                    val contentTokens = estimateTextTokens(msg.content)
                    val reasoningTokens = if (msg.hasReasoning) estimateTextTokens(msg.reasoningContent) else 0
                    assistantTokens += (contentTokens + reasoningTokens)
                    if (msg.toolCallsJson.isNotBlank()) {
                        toolResponsesTokens += estimateJsonTokens(msg.toolCallsJson)
                        toolExecCount++
                    }
                }
                MessageRole.TOOL -> {
                    toolExecCount++
                    toolResponsesTokens += estimateJsonTokens(msg.content)
                }
                MessageRole.SYSTEM -> {
                    // Raw system messages in history are rarely present; count as system
                }
            }
        }

        val totalUsed = systemTokens + toolSchemasTokens + toolResponsesTokens + userTokens + assistantTokens
        val windowLimit = resolveContextWindow(activeModel)

        return ContextBreakdown(
            systemPromptTokens = systemTokens,
            toolSchemasTokens = toolSchemasTokens,
            toolResponsesTokens = toolResponsesTokens,
            userMessagesTokens = userTokens,
            assistantMessagesTokens = assistantTokens,
            totalContextUsed = totalUsed,
            contextWindowLimit = windowLimit,
            userMessagesCount = userCount,
            assistantMessagesCount = assistantCount,
            toolCallsCount = toolExecCount,
            activeToolsCount = activeTools.size,
            modelDisplayName = activeModel.displayName,
            modelProvider = activeModel.provider
        )
    }

    fun resolveContextWindow(model: ModelInfo): Int {
        val id = model.id.lowercase().trim()
        val provider = model.provider.lowercase().trim()
        return when {
            id.contains("gemini") || provider.contains("google") -> 1_000_000
            id.contains("claude") || provider.contains("anthropic") -> 200_000
            id.contains("o1") || id.contains("o3") -> 200_000
            id.contains("deepseek") -> 128_000
            id.contains("gpt-4") -> 128_000
            id.contains("hermes") -> 128_000
            id.contains("llama") -> 128_000
            else -> 128_000
        }
    }

    fun estimateTextTokens(text: String): Int {
        if (text.isBlank()) return 0
        val trimmed = text.trim()
        val words = trimmed.split(Regex("""\s+""")).filter { it.isNotEmpty() }.size
        val charTokens = ceil(trimmed.length / 3.9).toInt()
        return maxOf(words, charTokens).coerceAtLeast(1)
    }

    fun estimateJsonTokens(jsonStr: String): Int {
        if (jsonStr.isBlank() || jsonStr == "[]" || jsonStr == "{}") return 0
        return ceil(jsonStr.length / 4.4).toInt().coerceAtLeast(1)
    }

    fun formatTokensCompact(tokens: Int): String {
        return when {
            tokens >= 1_000_000 -> {
                val m = tokens.toDouble() / 1_000_000.0
                String.format(Locale.US, "%.1fM", m)
            }
            tokens >= 1_000 -> {
                val k = tokens.toDouble() / 1_000.0
                if (k >= 100.0 || (tokens % 1000 == 0)) {
                    String.format(Locale.US, "%dk", k.roundToInt())
                } else {
                    String.format(Locale.US, "%.1fk", k)
                }
            }
            else -> tokens.toString()
        }
    }

    fun toMarkdownReport(b: ContextBreakdown): String {
        val sb = StringBuilder()
        sb.append("📊 **Reporte de Contexto y Tokens en Tiempo Real**\n\n")
        sb.append("• **Modelo Activo:** ").append(b.modelDisplayName).append(" (").append(b.modelProvider).append(")\n")
        sb.append("• **Ventana Máxima:** ").append(formatTokensCompact(b.contextWindowLimit)).append(" tokens\n")
        sb.append("• **Total Consumido:** ").append(b.totalContextUsed).append(" tokens (")
            .append(String.format(Locale.US, "%.1f", b.percentUsed)).append("%)\n")
        sb.append("• **Tokens Restantes:** ").append(b.remainingTokens).append(" tokens\n\n")
        sb.append("--- **Desglose Dividido** ---\n")
        sb.append("1. 🤖 **System Prompt:** ").append(b.systemPromptTokens).append(" tokens\n")
        sb.append("2. 🔧 **Herramientas MCP:** ").append(b.totalToolsTokens).append(" tokens\n")
        sb.append("   - Esquemas de ").append(b.activeToolsCount).append(" Tools: ").append(b.toolSchemasTokens).append(" tokens\n")
        sb.append("   - Ejecuciones y Respuestas: ").append(b.toolResponsesTokens).append(" tokens\n")
        sb.append("3. 👤 **Mensajes de Usuario:** ").append(b.userMessagesTokens).append(" tokens (")
            .append(b.userMessagesCount).append(" mensajes)\n")
        sb.append("4. 💬 **Respuestas Asistente:** ").append(b.assistantMessagesTokens).append(" tokens (")
            .append(b.assistantMessagesCount).append(" respuestas)\n")
        return sb.toString()
    }
}
