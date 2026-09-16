package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Test

class UiRenderingAndStreamingBenchmarkTest {
    @Test
    fun test_benchmark_executes_and_passes() {
        val bench = UiRenderingAndStreamingBenchmark()
        val result = bench.execute()
        assertTrue("El benchmark debe pasar sin errores de tearing y con extracción rápida", result.passed)
        assertEquals("UI_RENDERING_AND_STREAMING", result.name)
        assertTrue(result.durationMs > 0)
    }
}
