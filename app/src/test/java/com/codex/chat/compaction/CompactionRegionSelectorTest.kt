package com.codex.chat.compaction

import com.codex.chat.core.compaction.CompactionRegionSelector
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionRegionSelectorTest {

    @Test
    fun testFailsWhenMessageCountIsTooSmall() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hola"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Hola, ¿en qué te ayudo?")
        )
        val region = CompactionRegionSelector.selectRegion(messages, contextWindow = 128_000, force = true)
        assertNull(region)
    }

    @Test
    fun testSelectsRegionAndRetainsTail() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Turno 1 user"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Turno 1 assistant"),
            ChatMessage(role = MessageRole.USER, content = "Turno 2 user"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Turno 2 assistant"),
            ChatMessage(role = MessageRole.USER, content = "Turno 3 user reciente"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Turno 3 assistant reciente")
        )
        val region = CompactionRegionSelector.selectRegion(
            messages = messages,
            contextWindow = 128_000,
            minRetainMessages = 2,
            force = true
        )
        assertNotNull(region)
        assertTrue(region!!.spanToCompact.isNotEmpty())
        assertTrue(region.retainedTail.isNotEmpty())
        assertEquals(messages.size, region.spanToCompact.size + region.retainedTail.size)
        // Retained tail must start with user message when available
        assertEquals(MessageRole.USER, region.retainedTail.first().role)
    }

    @Test
    fun testNeverSplitsToolCallsAndToolResponses() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Abre terminal"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Ejecutando tool", toolCallsJson = "[{\"name\":\"mobile_click\"}]"),
            ChatMessage(role = MessageRole.TOOL, content = "{\"status\":\"success\"}", toolCallId = "call-1", toolName = "mobile_click"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Herramienta terminada"),
            ChatMessage(role = MessageRole.USER, content = "Siguiente tarea"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Respuesta final")
        )
        val region = CompactionRegionSelector.selectRegion(
            messages = messages,
            contextWindow = 128_000,
            minRetainMessages = 2,
            force = true
        )
        assertNotNull(region)
        // Verify tool message is never the first item of the retained tail
        assertTrue(region!!.retainedTail.first().role != MessageRole.TOOL)
    }

    @Test
    fun testProtectFirstNPreservesHead() {
        val messages = (1..15).flatMap { turn ->
            listOf(
                ChatMessage(role = MessageRole.USER, content = "User turn $turn"),
                ChatMessage(role = MessageRole.ASSISTANT, content = "Assistant turn $turn")
            )
        }
        val region = CompactionRegionSelector.selectRegion(
            messages = messages,
            contextWindow = 128_000,
            minRetainMessages = 5,
            protectFirstN = 3,
            force = true
        )
        assertNotNull(region)
        assertEquals(3, region!!.retainedHead.size)
        assertEquals("User turn 1", region.retainedHead[0].content)
        assertTrue(region.spanToCompact.isNotEmpty())
        assertTrue(region.retainedTail.isNotEmpty())
    }
}