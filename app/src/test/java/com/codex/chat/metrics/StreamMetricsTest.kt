package com.codex.chat.metrics

import com.codex.chat.core.metrics.StreamMetrics
import com.codex.chat.core.metrics.TokenEstimator
import org.junit.Assert.*
import org.junit.Test

class StreamMetricsTest {

    @Test
    fun test_token_estimator_basic_and_boundaries() {
        assertEquals(0, TokenEstimator.estimateTokens(""))
        assertEquals(0, TokenEstimator.estimateTokens("   "))

        val singleWord = TokenEstimator.estimateTokens("Hola")
        assertTrue("Single word should yield at least 1 token", singleWord >= 1)

        val sentence = "Hola, ¿cómo estás hoy en este hermoso día?"
        val estimated = TokenEstimator.estimateTokens(sentence)
        assertTrue("Sentence should produce proportional tokens", estimated in 8..20)
    }

    @Test
    fun test_calculate_tps() {
        val tps = TokenEstimator.calculateTps(tokens = 100, durationMs = 2000L)
        assertEquals(50.0, tps, 0.001)

        val tpsZeroDuration = TokenEstimator.calculateTps(tokens = 100, durationMs = 0L)
        assertEquals(0.0, tpsZeroDuration, 0.001)
    }

    @Test
    fun test_stream_metrics_formatting() {
        val metrics = StreamMetrics(
            durationMs = 2500L,
            promptTokens = 120,
            completionTokens = 100,
            totalTokens = 220,
            tokensPerSecond = 40.0
        )

        assertTrue(metrics.hasMetrics)
        assertEquals("40.0 t/s", metrics.formatTps())
        assertEquals("100 tokens", metrics.formatTokens())
        assertEquals("2.50s", metrics.formatDuration())

        val summary = metrics.formatSummary()
        assertTrue(summary.contains("40.0 t/s"))
        assertTrue(summary.contains("100 tokens"))
        assertTrue(summary.contains("2.50s"))

        val details = metrics.formatDetailedTooltip()
        assertTrue(details.contains("Velocidad: 40.0 t/s") || details.contains("Velocidad generación: 40.0 t/s"))
        assertTrue(details.contains("Tokens salida: 100"))
        assertTrue(details.contains("Tokens entrada: 120"))
    }

    @Test
    fun test_stream_metrics_with_thinking_duration_decoupling() {
        val metrics = StreamMetrics(
            durationMs = 20000L,
            thinkingDurationMs = 18500L,
            generationDurationMs = 1500L,
            promptTokens = 20,
            completionTokens = 60,
            totalTokens = 80,
            tokensPerSecond = 40.0 // 60 tokens in 1.5s = 40 t/s, NOT 60 in 20s = 3 t/s!
        )

        val summary = metrics.formatSummary()
        assertTrue("Summary should contain high generation tps", summary.contains("40.0 t/s"))
        assertTrue("Summary should reflect thinking time separately", summary.contains("💭 18.5s"))
        assertTrue("Summary should reflect tokens count", summary.contains("60 tokens"))

        val details = metrics.formatDetailedTooltip()
        assertTrue(details.contains("Tiempo razonamiento: 18.5s"))
        assertTrue(details.contains("Tiempo emisión: 1.50s"))
    }
}
