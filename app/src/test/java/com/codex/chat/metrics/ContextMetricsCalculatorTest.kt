package com.codex.chat.metrics

import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.metrics.ContextMetricsCalculator
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.SkillInfo
import org.junit.Assert.*
import org.junit.Test

class ContextMetricsCalculatorTest {

    @Test
    fun testEmptyConversationInitializesWithSystemAndTools() {
        val model = ModelInfo("gpt-4o", "GPT-4o", "openai", supportsReasoning = false)
        val registry = McpRegistry(null)
        val skill = SkillInfo("test-skill", "Skill Creator", "Desc", "Anthropic", "System skill prompt")

        val breakdown = ContextMetricsCalculator.calculate(
            messages = emptyList(),
            activeModel = model,
            activeSkill = skill,
            activeSubagent = null,
            mcpRegistry = registry
        )

        assertTrue("System prompt tokens debe ser mayor a 3000", breakdown.systemPromptTokens > 3000)
        assertTrue("Tool schemas tokens debe ser mayor a 3000", breakdown.toolSchemasTokens > 3000)
        assertEquals("User tokens debe ser 0 en conversacion vacia", 0, breakdown.userMessagesTokens)
        assertEquals("Assistant tokens debe ser 0 en conversacion vacia", 0, breakdown.assistantMessagesTokens)
        assertEquals("Tool response tokens debe ser 0 en conversacion vacia", 0, breakdown.toolResponsesTokens)
        assertTrue("Total tokens debe ser la suma de system y tools", breakdown.totalContextUsed >= 6500)
        assertEquals("GPT-4o debe tener context window de 128000", 128000, breakdown.contextWindowLimit)
        assertTrue("Porcentaje de uso debe ser > 0 y < 15%", breakdown.percentUsed in 4.0..15.0)
    }

    @Test
    fun testUserAndAssistantMessagesIncreaseBreakdownCorrectly() {
        val model = ModelInfo("gpt-4o", "GPT-4o", "openai")
        val registry = McpRegistry(null)

        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hola, escribe un script en python de 100 lineas para procesar datos"),
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "Aqui tienes el script...",
                reasoningContent = "El usuario pide procesar datos, usaremos pandas..."
            ),
            ChatMessage(
                role = MessageRole.TOOL,
                content = "{\"success\": true, \"stdout\": \"Datos procesados correctamente\"}",
                toolName = "execute_python"
            )
        )

        val breakdown = ContextMetricsCalculator.calculate(
            messages = messages,
            activeModel = model,
            activeSkill = null,
            activeSubagent = null,
            mcpRegistry = registry
        )

        assertTrue("User tokens debe ser > 0", breakdown.userMessagesTokens > 10)
        assertTrue("Assistant tokens debe ser > 0", breakdown.assistantMessagesTokens > 10)
        assertTrue("Tool response tokens debe ser > 0", breakdown.toolResponsesTokens > 5)
        assertEquals("Debe registrar 1 mensaje de usuario", 1, breakdown.userMessagesCount)
        assertEquals("Debe registrar 1 mensaje de asistente", 1, breakdown.assistantMessagesCount)
        assertEquals("Debe registrar 1 ejecucion de herramienta", 1, breakdown.toolCallsCount)
    }

    @Test
    fun testFormatTokensCompact() {
        assertEquals("850", ContextMetricsCalculator.formatTokensCompact(850))
        assertEquals("7.8k", ContextMetricsCalculator.formatTokensCompact(7829))
        assertEquals("128k", ContextMetricsCalculator.formatTokensCompact(128000))
        assertEquals("1.0M", ContextMetricsCalculator.formatTokensCompact(1000000))
    }
}
