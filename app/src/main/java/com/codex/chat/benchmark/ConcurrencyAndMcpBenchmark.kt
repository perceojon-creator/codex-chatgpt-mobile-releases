package com.codex.chat.benchmark

import android.content.Context
import com.codex.chat.core.concurrency.ToolBatchExecutor
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.model.McpToolResult
import com.codex.chat.core.mcp.server.MemorySqliteStore
import com.codex.chat.core.parser.SseStreamParser.CompletedToolCall
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ConcurrencyAndMcpBenchmark(private val context: Context) {

    fun execute(): BenchmarkResult {
        val logs = mutableListOf<String>()
        val start = System.currentTimeMillis()

        // 1. Benchmark de ToolBatchExecutor sin HoL Blocking
        val registry = McpRegistry(context)
        val executor = ToolBatchExecutor(registry)

        val completedCount = AtomicInteger(0)
        val calls = (1..30).map { i ->
            CompletedToolCall(
                id = "bench-call-$i",
                name = "evaluate_math",
                argumentsJson = "{\"expression\": \"$i * 2\"}"
            )
        }

        val summary = executor.executeBatch(calls) { _, _ ->
            completedCount.incrementAndGet()
        }

        logs.add("ToolBatch: ${summary.results.size} herramientas ejecutadas en ${summary.totalDurationMs} ms")
        logs.add("Speedup ratio estimado: ${summary.speedupRatio}x")

        // 2. Benchmark de lecturas concurrentes multi-lector en SQLite WAL
        val store = MemorySqliteStore.getInstance(context)
        // Insertar registros de prueba
        for (i in 1..10) {
            store.save("bench_key_$i", "Contenido de memoria de prueba $i", "benchmark")
        }

        val readerThreads = 4
        val readsPerThread = 25
        val latch = CountDownLatch(readerThreads)
        val successfulReads = AtomicInteger(0)

        for (t in 1..readerThreads) {
            Thread {
                try {
                    for (k in 1..readsPerThread) {
                        val res = store.searchFts5("memoria", 5)
                        if (res.length() > 0) {
                            successfulReads.incrementAndGet()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        val readersFinished = latch.await(10, TimeUnit.SECONDS)
        val totalReads = successfulReads.get()
        logs.add("SQLite WAL Multi-Reader: $totalReads lecturas concurrentes exitosas en 4 hilos paralelos")

        val durationMs = System.currentTimeMillis() - start
        val passed = (summary.results.size == 30) && (completedCount.get() == 30) && readersFinished && (totalReads > 0)

        return BenchmarkResult(
            name = "CONCURRENCY_AND_MCP_STRESS",
            passed = passed,
            durationMs = durationMs,
            metrics = mapOf(
                "batch_tools_count" to summary.results.size.toDouble(),
                "batch_duration_ms" to summary.totalDurationMs.toDouble(),
                "speedup_ratio" to summary.speedupRatio,
                "concurrent_reads" to totalReads.toDouble()
            ),
            logLines = logs,
            errorMessage = if (!passed) "Fallo en benchmark de concurrencia (herramientas=${summary.results.size}/30, lecturas=$totalReads)" else null
        )
    }
}
