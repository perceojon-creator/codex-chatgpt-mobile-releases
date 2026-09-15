package com.codex.chat.network

import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.network.CodexPayloadBuilder
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ToolProtocolRoundTripTest {

    private fun model(): ModelInfo {
        return ModelInfo(id = "test-model", displayName = "Test", provider = "openai", supportsReasoning = true)
    }

    @Test
    fun rol_tool_se_serializa_con_tool_call_id_y_name() {
        val msgs = listOf(
            ChatMessage(role = MessageRole.USER, content = "Dame el resultado"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "", toolCallsJson = "[]"),
            ChatMessage(role = MessageRole.TOOL, content = "HTTP 200 OK", toolCallId = "call_abc123", toolName = "http_get")
        )
        val payload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model(), effort = ReasoningEffort.MEDIUM,
            messages = msgs, stream = true
        )
        val arr = payload.getJSONArray("messages")
        val toolMsg = (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.optString("role") == "tool" }
        assertEquals("call_abc123", toolMsg.optString("tool_call_id"))
        assertEquals("http_get", toolMsg.optString("name"))
        assertEquals("HTTP 200 OK", toolMsg.optString("content"))
    }

    @Test
    fun assistant_con_toolcalls_emite_array_nativo_y_contenido_limpio() {
        val toolCalls = JSONArray().put(
            JSONObject().put("id", "call_1").put("type", "function")
                .put("function", JSONObject().put("name", "write_file").put("arguments", "{}"))
        )
        val rawBubble = "Voy a generar el diagrama\n\n⚙️ **[MCP Tool Call: `write_file`]**\n```json\n{\"a\":1}\n```\n\n✅ **[Resultado MCP: `write_file`]**\n```json\n{\"ok\":true}\n```"
        val msgs = listOf(
            ChatMessage(role = MessageRole.USER, content = "hazlo"),
            ChatMessage(role = MessageRole.ASSISTANT, content = rawBubble, toolCallsJson = toolCalls.toString())
        )
        val payload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model(), effort = ReasoningEffort.MEDIUM,
            messages = msgs, stream = true
        )
        val arr = payload.getJSONArray("messages")
        val assistant = (0 until arr.length()).map { arr.getJSONObject(it) }.first { it.optString("role") == "assistant" }
        assertTrue(assistant.has("tool_calls"))
        assertEquals(1, assistant.getJSONArray("tool_calls").length())
        val content = assistant.optString("content")
        assertFalse(content.contains("MCP Tool Call"))
        assertFalse(content.contains("Resultado MCP"))
        assertFalse(content.contains("\"a\":1"))
        assertTrue(content.contains("Voy a generar el diagrama"))
    }

    @Test
    fun stripLocalToolMarkdown_elimina_bloques_y_cola_json() {
        val leaky = "Previo\n\n⚙️ **[MCP Tool Call: `write_file`]**\n```json\n{\"content\":\"x\"}\n```\n\n✅ **[Resultado MCP: `write_file`]**\n```json\n{\"ok\":true}\n```\n\nTexto final."
        val stripped = CodexPayloadBuilder.stripLocalToolMarkdown(leaky)
        assertFalse(stripped.contains("MCP Tool Call"))
        assertFalse(stripped.contains("Resultado MCP"))
        assertFalse(stripped.contains("\"content\":"))
        assertFalse(stripped.contains("```"))
        assertTrue(stripped.contains("Texto final."))
        assertTrue(stripped.contains("Previo"))
    }

    @Test
    fun stripLocalToolMarkdown_no_dana_texto_plano() {
        val plain = "El turbocompresor comprime gases de escape. Diagrama de flujo del carro."
        val stripped = CodexPayloadBuilder.stripLocalToolMarkdown(plain)
        assertEquals(plain, stripped)
    }
}