package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class MigrationCutoverBenchmarkTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_migration_cutover_benchmark_executes_and_passes() {
        val testDir = tempFolder.newFolder("bench_cutover_test")
        val benchmark = MigrationCutoverBenchmark(testDir)
        val result = benchmark.execute(legacySessionCount = 50)

        assertTrue("MigrationCutoverBenchmark debe pasar: " + result.errorMessage, result.passed)
        assertEquals(49.0, result.metrics["migrated_count"] ?: -1.0, 0.0)
        assertEquals(1.0, result.metrics["skipped_count"] ?: -1.0, 0.0)
        assertEquals(1.0, result.metrics["cutover_done"] ?: -1.0, 0.0)

        val ansi = result.toAnsiOutput()
        assertTrue(ansi.contains("[PASS]"))
        assertTrue(ansi.contains("STORAGE_CUTOVER_MIGRATION"))
    }
}
