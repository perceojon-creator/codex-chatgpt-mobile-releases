package com.codex.chat.parser

import com.codex.chat.core.metrics.StreamMetrics
import com.codex.chat.core.parser.SseStreamParser
import org.junit.Assert.*
import org.junit.Test

class SseStreamMetricsTest {

    @Test
    fun testUsageExtractionAndMetricsDelivery() {
        var completedMetrics: StreamMetrics? = null

        val parser = SseStreamParser(
            listener = object : SseStreamParser.SseEventListener {
                override fun onReasoningDelta(delta: String) {}
                override fun onContentDelta(delta: String) {}
                override fun onComplete(fullContent: String, fullReasoning: String) {}
                override fun onCompleteWithMetrics(
                    fullContent: String,
                    fullReasoning: String,
                    metrics: StreamMetrics
                ) {
                    completedMetrics = metrics
                }
                override fun onError(error: Throwable) {
                    throw AssertionError(error)
                }
            },
            requestStartTime = System.currentTimeMillis() - 500 // simulated 500ms duration
        )

        val chunk1 = "data: {\"choices\": [{\"delta\": {\"content\": \"Hola desde el proxy.\"}}]}\n\n"
        val chunkUsage = "data: {\"choices\": [], \"usage\": {\"prompt_tokens\": 25, \"completion_tokens\": 50, \"total_tokens\": 75}}\n\n"
        val done = "data: [DONE]\n\n"

        parser.feedChunk(chunk1)
        parser.feedChunk(chunkUsage)
        parser.feedChunk(done)

        assertNotNull("Metrics must be reported on completion", completedMetrics)
        val m = completedMetrics!!
        assertEquals(25, m.promptTokens)
        assertEquals(50, m.completionTokens)
        assertEquals(75, m.totalTokens)
        assertTrue("Duration should be >= 500ms", m.durationMs >= 500L)
        assertTrue("Tokens per second should be > 0", m.tokensPerSecond > 0.0)
    }

    @Test
    fun testFallbackTokenEstimationWhenUsageOmitted() {
        var completedMetrics: StreamMetrics? = null

        val parser = SseStreamParser(
            listener = object : SseStreamParser.SseEventListener {
                override fun onReasoningDelta(delta: String) {}
                override fun onContentDelta(delta: String) {}
                override fun onComplete(fullContent: String, fullReasoning: String) {}
                override fun onCompleteWithMetrics(
                    fullContent: String,
                    fullReasoning: String,
                    metrics: StreamMetrics
                ) {
                    completedMetrics = metrics
                }
                override fun onError(error: Throwable) {
                    throw AssertionError(error)
                }
            },
            requestStartTime = System.currentTimeMillis() - 1000 // simulated 1000ms duration
        )

        val chunk1 = "data: {\"choices\": [{\"delta\": {\"content\": \"Esta es una respuesta completa sin objeto de uso retornado por el proveedor.\"}}]}\n\n"
        val done = "data: [DONE]\n\n"

        parser.feedChunk(chunk1)
        parser.feedChunk(done)

        assertNotNull("Metrics must be reported even without usage block", completedMetrics)
        val m = completedMetrics!!
        assertTrue("Estimated completion tokens should be > 0", m.completionTokens > 0)
        assertTrue("Estimated total tokens should be > 0", m.totalTokens > 0)
        assertTrue("Duration should be >= 1000ms", m.durationMs >= 1000L)
        assertTrue("Tokens per second should be calculated", m.tokensPerSecond > 0.0)
    }

    @Test
    fun testDecoupledThinkingAndGenerationSpeed() {
        var completedMetrics: StreamMetrics? = null
        val startTime = System.currentTimeMillis() - 2000L // Request started 2 seconds ago

        val parser = SseStreamParser(
            listener = object : SseStreamParser.SseEventListener {
                override fun onReasoningDelta(delta: String) {}
                override fun onContentDelta(delta: String) {}
                override fun onComplete(fullContent: String, fullReasoning: String) {}
                override fun onCompleteWithMetrics(
                    fullContent: String,
                    fullReasoning: String,
                    metrics: StreamMetrics
                ) {
                    completedMetrics = metrics
                }
                override fun onError(error: Throwable) {
                    throw AssertionError(error)
                }
            },
            requestStartTime = startTime
        )

        // Simulate reasoning chunk at 100ms
        val reasoningChunk = "data: {\"choices\": [{\"delta\": {\"reasoning_content\": \"Thinking deeply about the universe...\"}}]}\n\n"
        parser.feedChunk(reasoningChunk)

        // Simulate content chunk arrives now (after ~2000ms of thinking)
        val contentChunk = "data: {\"choices\": [{\"delta\": {\"content\": \"The universe is vast.\"}}], \"usage\": {\"prompt_tokens\": 10, \"completion_tokens\": 50, \"total_tokens\": 60}}\n\n"
        parser.feedChunk(contentChunk)
        parser.feedChunk("data: [DONE]\n\n")

        assertNotNull(completedMetrics)
        val m = completedMetrics!!
        assertTrue("Thinking duration should be >= 1800ms", m.thinkingDurationMs >= 1800L)
        assertTrue("Tokens per second should be calculated from generation time, not total time", m.tokensPerSecond > 25.0)
    }
}
