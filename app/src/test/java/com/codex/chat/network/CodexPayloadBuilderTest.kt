package com.codex.chat.network

import com.codex.chat.core.model.*
import com.codex.chat.core.network.CodexPayloadBuilder
import org.junit.Assert.*
import org.junit.Test

class CodexPayloadBuilderTest {

    @Test
    fun testModelAndStreamFlagsAreNeverHardcoded() {
        val customModel = ModelInfo(
            id = "custom-llm-v99",
            displayName = "Custom LLM",
            provider = "Local",
            supportsReasoning = false
        )

        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "¿Cuál es el status del cluster?")
        )

        val payload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = customModel,
            effort = ReasoningEffort.LOW,
            messages = messages,
            activeSubagent = null,
            stream = true
        )

        assertEquals("custom-llm-v99", payload.getString("model"))
        assertTrue("Stream debe ser true", payload.getBoolean("stream"))
        assertFalse("No debe tener reasoning_effort si el modelo no lo soporta", payload.has("reasoning_effort"))

        val msgsArray = payload.getJSONArray("messages")
        assertEquals(1, msgsArray.length())
        val firstMsg = msgsArray.getJSONObject(0)
        assertEquals("user", firstMsg.getString("role"))
        assertEquals("¿Cuál es el status del cluster?", firstMsg.getString("content"))
    }

    @Test
    fun testReasoningEffortSerializedWhenSupported() {
        val reasoningModel = ModelInfo(
            id = "gpt-5.6-sol",
            displayName = "Sol",
            provider = "Antigravity",
            supportsReasoning = true
        )

        for (effort in ReasoningEffort.entries) {
            val payload = CodexPayloadBuilder.buildChatCompletionPayload(
                model = reasoningModel,
                effort = effort,
                messages = listOf(ChatMessage(role = MessageRole.USER, content = "Hola")),
                activeSubagent = null
            )

            assertTrue("Debe incluir el campo reasoning_effort", payload.has("reasoning_effort"))
            assertEquals(effort.value, payload.getString("reasoning_effort"))
        }
    }

    @Test
    fun testSubagentSystemPromptInjectionAtHead() {
        val model = ModelInfo(id = "astra", displayName = "Astra", provider = "Antigravity", supportsReasoning = true)
        val subagent = SubagentInfo(
            id = "system-architect",
            name = "Architect",
            description = "Desc",
            systemPrompt = "Actúa como un arquitecto de software nivel L7.",
            iconEmoji = "🏛️"
        )

        val history = listOf(
            ChatMessage(role = MessageRole.USER, content = "Diseña un broker de eventos.")
        )

        val payload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model,
            effort = ReasoningEffort.XHIGH,
            messages = history,
            activeSubagent = subagent
        )

        val msgs = payload.getJSONArray("messages")
        assertEquals("Debe tener 2 mensajes (system + user)", 2, msgs.length())

        val systemMsg = msgs.getJSONObject(0)
        assertEquals("system", systemMsg.getString("role"))
        assertEquals("Actúa como un arquitecto de software nivel L7.", systemMsg.getString("content"))

        val userMsg = msgs.getJSONObject(1)
        assertEquals("user", userMsg.getString("role"))
        assertEquals("Diseña un broker de eventos.", userMsg.getString("content"))
    }

    @Test
    fun testMultimodalImageAttachmentSerialization() {
        val model = ModelInfo(id = "gpt-5.6-sol", displayName = "Sol", provider = "Antigravity", supportsReasoning = true)

        val dummyBase64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
        val imageAttachment = Attachment(
            id = "att-1",
            fileName = "screenshot.png",
            mimeType = "image/png",
            sizeBytes = 128,
            base64Data = dummyBase64
        )

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = "Explica esta captura",
            attachments = listOf(imageAttachment)
        )

        val payload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model,
            effort = ReasoningEffort.HIGH,
            messages = listOf(userMessage)
        )

        val msgs = payload.getJSONArray("messages")
        assertEquals(1, msgs.length())

        val msgObj = msgs.getJSONObject(0)
        assertTrue("El contenido debe ser un JSONArray multimodal", msgObj.get("content") is org.json.JSONArray)

        val contentArray = msgObj.getJSONArray("content")
        assertEquals(2, contentArray.length())

        val textPart = contentArray.getJSONObject(0)
        assertEquals("text", textPart.getString("type"))
        assertEquals("Explica esta captura", textPart.getString("text"))

        val imgPart = contentArray.getJSONObject(1)
        assertEquals("image_url", imgPart.getString("type"))
        val imgUrlObj = imgPart.getJSONObject("image_url")
        assertEquals("data:image/png;base64,$dummyBase64", imgUrlObj.getString("url"))
    }
}
