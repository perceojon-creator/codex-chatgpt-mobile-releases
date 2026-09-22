package com.codex.chat.mcp

import com.codex.chat.core.mcp.server.MemoryMcpServer
import com.codex.chat.core.network.CodexPayloadBuilder
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMcpServerTest {

    @Test
    fun testIsTrivialPromptDetectsGreetingsAndShortConfirmations() {
        assertTrue(MemoryMcpServer.isTrivialPrompt("hola"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("¡Hola!"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("buenas tardes"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("gracias"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("ok"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("si"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("sí"))
        assertTrue(MemoryMcpServer.isTrivialPrompt("  "))
        assertTrue(MemoryMcpServer.isTrivialPrompt(null))
    }

    @Test
    fun testIsTrivialPromptAllowsSubstantiveQueries() {
        assertFalse(MemoryMcpServer.isTrivialPrompt("¿Cómo configuro el proxy en el puerto 8317?"))
        assertFalse(MemoryMcpServer.isTrivialPrompt("Revisa el código de MainActivity y corrige el bug"))
        assertFalse(MemoryMcpServer.isTrivialPrompt("Explica la diferencia entre FTS5 y FTS4"))
    }

    @Test
    fun testActivePersonaInjectedIntoSystemPrompt() {
        val prompt = CodexPayloadBuilder.buildSystemPrompt(
            activePersona = Pair("technical", "Sé riguroso y conciso con código tipado.")
        )
        assertTrue(prompt.contains("### 8. PERSONALIDAD ACTIVA (TECHNICAL):"))
        assertTrue(prompt.contains("Sé riguroso y conciso con código tipado."))
    }

    @Test
    fun testBatteryLowModulatesReasoningEffort() {
        val model = ModelInfo(
            id = "o3-mini",
            displayName = "o3 Mini",
            provider = "openai",
            supportsReasoning = true
        )
        // With battery low, effort HIGH downgrades to LOW
        val payloadLow = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model,
            effort = ReasoningEffort.HIGH,
            messages = emptyList(),
            isBatteryLow = true
        )
        assertEquals("low", payloadLow.optString("reasoning_effort"))

        // With normal battery, effort remains HIGH
        val payloadNormal = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model,
            effort = ReasoningEffort.HIGH,
            messages = emptyList(),
            isBatteryLow = false
        )
        assertEquals("high", payloadNormal.optString("reasoning_effort"))
    }
}
