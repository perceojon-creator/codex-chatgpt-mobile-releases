package com.codex.chat.benchmark

import org.junit.Assert.*
import org.junit.Test

class MasterBenchmarkRunnerTest {
    @Test
    fun test_master_runner_aggregates_all_benchmarks() {
        val engine = TuiBenchmarkEngine()
        engine.registerBenchmark("B1") { BenchmarkResult("B1", true, 10L) }
        engine.registerBenchmark("B2") { BenchmarkResult("B2", true, 20L) }

        val runner = MasterBenchmarkRunner(engine)
        val report = runner.runAll()
        assertEquals(2, report.totalCount)
        assertEquals(2, report.passedCount)
        assertEquals(0, report.failedCount)
        assertTrue(report.toJson().contains("\"passedCount\": 2") || report.toJson().contains("\"passedCount\":2"))
    }
}
