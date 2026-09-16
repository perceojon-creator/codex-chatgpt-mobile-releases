package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KillAndCorruptionChaosBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_chaos_benchmark_completes_with_zero_corrupted_files() {
        val benchDir = tempFolder.newFolder("chaos_bench")
        val benchmark = KillAndCorruptionChaosBenchmark(benchDir)
        val result = benchmark.execute(50)

        assertTrue("La prueba de caos debe pasar con 0 archivos de 0 bytes", result.passed)
        assertEquals("CHAOS_KILL_AND_CORRUPTION", result.name)
        assertEquals(0.0, result.metrics["zero_byte_files"] ?: -1.0, 0.0)
    }
}
