package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataIntegrityBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_data_integrity_benchmark_runs_and_passes() {
        val testDir = tempFolder.newFolder("bench_data_integrity")
        val benchmark = DataIntegrityBenchmark(testDir)
        val result = benchmark.execute(writeIterations = 200)

        assertTrue("DataIntegrityBenchmark debe pasar exitosamente: " + result.errorMessage, result.passed)
        assertEquals(0.0, result.metrics["error_count"] ?: -1.0, 0.0)
        assertEquals(0.0, result.metrics["zero_byte_files"] ?: -1.0, 0.0)
        assertEquals(200.0, result.metrics["write_count"] ?: -1.0, 0.0)

        val output = result.toAnsiOutput()
        assertTrue(output.contains("[PASS]"))
        assertTrue(output.contains("DATA_INTEGRITY_STRESS"))
    }
}
