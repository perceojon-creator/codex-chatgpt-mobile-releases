package com.codex.chat.metrics

import com.codex.chat.core.metrics.ContextMetricsCalculator
import org.junit.Assert.*
import org.junit.Test

class ContextMetricsPrecompiledRegexTest {
    @Test
    fun test_token_meter_calculation_throughput() {
        val text = "Texto representativo con varias palabras para medir velocidad de conteo"
        val start = System.nanoTime()
        for (i in 1..1000) {
            ContextMetricsCalculator.fastWordCount(text)
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
        assertTrue("1000 conteos deben tardar menos de 20 ms", elapsedMs < 20.0)
    }

    @Test
    fun test_fast_word_count_accuracy() {
        assertEquals(0, ContextMetricsCalculator.fastWordCount(""))
        assertEquals(0, ContextMetricsCalculator.fastWordCount("   \n\t  "))
        assertEquals(1, ContextMetricsCalculator.fastWordCount("Palabra"))
        assertEquals(4, ContextMetricsCalculator.fastWordCount("  Hola   mundo desde   Kotlin!  "))
    }
}
