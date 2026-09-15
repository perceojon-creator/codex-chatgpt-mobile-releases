package com.codex.chat.metrics

import com.codex.chat.core.metrics.StreamMetrics
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.*
import org.junit.Test

class ChatMessageMetricsTest {

    @Test
    fun test_chat_message_with_metrics() {
        val msg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Respuesta asistida con métricas de rendimiento.",
            durationMs = 1200L,
            completionTokens = 48,
            promptTokens = 20,
            totalTokens = 68,
            tokensPerSecond = 40.0
        )

        assertTrue(msg.hasPerformanceMetrics)
        val metrics = msg.toStreamMetrics()
        assertEquals(1200L, metrics.durationMs)
        assertEquals(48, metrics.completionTokens)
        assertEquals(40.0, metrics.tokensPerSecond, 0.001)

        val summary = metrics.formatSummary()
        assertTrue(summary.contains("40.0 t/s"))
        assertTrue(summary.contains("48 tokens"))
        assertTrue(summary.contains("1.20s"))
    }

    @Test
    fun test_chat_message_apply_metrics() {
        val msg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Mensaje sin métricas iniciales"
        )
        assertFalse(msg.hasPerformanceMetrics)

        msg.applyStreamMetrics(
            StreamMetrics(
                durationMs = 950L,
                promptTokens = 10,
                completionTokens = 35,
                totalTokens = 45,
                tokensPerSecond = 36.8
            )
        )

        assertTrue(msg.hasPerformanceMetrics)
        assertEquals(950L, msg.durationMs)
        assertEquals(35, msg.completionTokens)
        assertEquals(36.8, msg.tokensPerSecond, 0.01)
    }
    @Test
    fun test_chat_message_with_thinking_duration() {
        val msg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Respuesta con pensamiento.",
            durationMs = 15000L,
            thinkingDurationMs = 13500L,
            generationDurationMs = 1500L,
            completionTokens = 45,
            tokensPerSecond = 30.0
        )

        val metrics = msg.toStreamMetrics()
        assertEquals(13500L, metrics.thinkingDurationMs)
        assertEquals(1500L, metrics.generationDurationMs)
        val summary = metrics.formatSummary()
        assertTrue(summary.contains("💭 13.5s"))
        assertTrue(summary.contains("30.0 t/s"))
    }
}
