package com.codex.chat.concurrency

import com.codex.chat.core.concurrency.ToolBatchExecutor
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.model.McpToolResult
import com.codex.chat.core.parser.SseStreamParser.CompletedToolCall
import com.codex.chat.core.security.EstopSentinel
import org.junit.Assert.*
import org.junit.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ToolExecutionConcurrencyValidationTest {

    @Test
    fun testTwentyParallelReadOnlyToolsRunSimultaneouslyWithSpeedup() {
        val maxConcurrentObserved = AtomicInteger(0)
        val currentActive = AtomicInteger(0)
        val threadNames = ConcurrentHashMap.newKeySet<String>()

        val registry = McpRegistry()
        val executor = object : ToolBatchExecutor(registry, maxConcurrency = 20) {
            override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                threadNames.add(Thread.currentThread().name)
                val active = currentActive.incrementAndGet()
                maxConcurrentObserved.updateAndGet { prev -> maxOf(prev, active) }
                Thread.sleep(50) // Simular latencia de consulta I/O o telemetría
                currentActive.decrementAndGet()
                return McpToolResult(callId = tc.id, toolName = tc.name, content = "{\"index\": ${tc.id}}")
            }
        }

        // Generar 20 llamadas de lectura simultáneas
        val calls = (1..20).map { i ->
            CompletedToolCall(id = "call-$i", name = "get_battery_status", argumentsJson = "{}")
        }

        val plan = executor.planExecution(calls)
        assertEquals("20 lecturas deben agruparse en 1 solo paso paralelo", 1, plan.size)
        assertTrue(plan[0] is ToolBatchExecutor.ExecutionPlanStep.ParallelStep)
        val parallelStep = plan[0] as ToolBatchExecutor.ExecutionPlanStep.ParallelStep
        assertEquals(20, parallelStep.items.size)

        val summary = executor.executeBatch(calls)
        assertEquals(20, summary.results.size)
        assertEquals(20, summary.parallelCallsCount)
        assertEquals(0, summary.sequentialCallsCount)

        // Validar concurrencia real comprobada empíricamente
        assertTrue("Debe haberse ejecutado en múltiples hilos en paralelo (hilos: ${threadNames.size})", threadNames.size >= 10)
        assertTrue("Máximo de llamadas simultáneas activas al mismo tiempo debe ser >= 10 (fue ${maxConcurrentObserved.get()})", maxConcurrentObserved.get() >= 10)
        assertTrue("El speedup medido debe ser > 3.0x (fue ${summary.speedupRatio}x)", summary.speedupRatio >= 3.0)

        // Validar preservación estricta del orden posicional
        for (i in 1..20) {
            assertEquals("call-$i", summary.results[i - 1].callId)
        }
    }

    @Test
    fun testMutatingToolsExecuteStrictlySequentialOneByOne() {
        val maxConcurrentObserved = AtomicInteger(0)
        val currentActive = AtomicInteger(0)
        val executionSequence = mutableListOf<String>()

        val registry = McpRegistry()
        val executor = object : ToolBatchExecutor(registry, maxConcurrency = 20) {
            override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                val active = currentActive.incrementAndGet()
                maxConcurrentObserved.updateAndGet { prev -> maxOf(prev, active) }
                synchronized(executionSequence) {
                    executionSequence.add("start-" + tc.id)
                }
                Thread.sleep(15)
                synchronized(executionSequence) {
                    executionSequence.add("end-" + tc.id)
                }
                currentActive.decrementAndGet()
                return McpToolResult(callId = tc.id, toolName = tc.name, content = "done")
            }
        }

        // 5 operaciones mutantes / táctiles
        val calls = listOf(
            CompletedToolCall("c1", "write_file", "{}"),
            CompletedToolCall("c2", "mobile_click", "{}"),
            CompletedToolCall("c3", "mobile_type", "{}"),
            CompletedToolCall("c4", "delete_file", "{}"),
            CompletedToolCall("c5", "send_sms", "{}")
        )

        val plan = executor.planExecution(calls)
        assertEquals("5 mutaciones deben generar 5 pasos secuenciales aislados", 5, plan.size)
        for (step in plan) {
            assertTrue(step is ToolBatchExecutor.ExecutionPlanStep.SequentialStep)
        }

        val summary = executor.executeBatch(calls)
        assertEquals(5, summary.results.size)
        assertEquals(5, summary.sequentialCallsCount)
        assertEquals(0, summary.parallelCallsCount)

        // En secuencial estricto, la concurrencia máxima debe ser EXACTAMENTE 1 (nunca se solapan)
        assertEquals("La concurrencia máxima en llamadas secuenciales debe ser exactamente 1", 1, maxConcurrentObserved.get())

        // Verificar que start-c1 termina antes de que start-c2 comience
        assertEquals(10, executionSequence.size)
        assertEquals("start-c1", executionSequence[0])
        assertEquals("end-c1", executionSequence[1])
        assertEquals("start-c2", executionSequence[2])
        assertEquals("end-c2", executionSequence[3])
        assertEquals("start-c3", executionSequence[4])
        assertEquals("end-c3", executionSequence[5])
    }

    @Test
    fun testHybridBatchPartitionsIntoParallelChunksAndSequentialVerifications() {
        val registry = McpRegistry()
        val executor = ToolBatchExecutor(registry, maxConcurrency = 20)

        // Lote mixto de 10 herramientas: [Lectura x3] -> [Escritura x1] -> [Lectura x4] -> [Táctil x1] -> [Lectura x1]
        val calls = listOf(
            CompletedToolCall("c1", "get_battery_status", "{}"),
            CompletedToolCall("c2", "get_wifi_status", "{}"),
            CompletedToolCall("c3", "read_file", "{}"),
            CompletedToolCall("c4", "write_file", "{}"), // Mutante
            CompletedToolCall("c5", "get_device_telemetry", "{}"),
            CompletedToolCall("c6", "list_files", "{}"),
            CompletedToolCall("c7", "search_memory", "{}"),
            CompletedToolCall("c8", "mobile_get_screen", "{}"),
            CompletedToolCall("c9", "mobile_click", "{}"), // Táctil / Mutante
            CompletedToolCall("c10", "evaluate_math", "{}")
        )

        val plan = executor.planExecution(calls)
        assertEquals("Debe particionarse en 5 pasos alternados", 5, plan.size)

        // Paso 0: Paralelo [c1, c2, c3]
        assertTrue(plan[0] is ToolBatchExecutor.ExecutionPlanStep.ParallelStep)
        assertEquals(3, (plan[0] as ToolBatchExecutor.ExecutionPlanStep.ParallelStep).items.size)

        // Paso 1: Secuencial [c4: write_file]
        assertTrue(plan[1] is ToolBatchExecutor.ExecutionPlanStep.SequentialStep)
        assertEquals("write_file", (plan[1] as ToolBatchExecutor.ExecutionPlanStep.SequentialStep).item.toolCall.name)

        // Paso 2: Paralelo [c5, c6, c7, c8]
        assertTrue(plan[2] is ToolBatchExecutor.ExecutionPlanStep.ParallelStep)
        assertEquals(4, (plan[2] as ToolBatchExecutor.ExecutionPlanStep.ParallelStep).items.size)

        // Paso 3: Secuencial [c9: mobile_click]
        assertTrue(plan[3] is ToolBatchExecutor.ExecutionPlanStep.SequentialStep)
        assertEquals("mobile_click", (plan[3] as ToolBatchExecutor.ExecutionPlanStep.SequentialStep).item.toolCall.name)

        // Paso 4: Paralelo [c10: evaluate_math]
        assertTrue(plan[4] is ToolBatchExecutor.ExecutionPlanStep.ParallelStep)
        assertEquals(1, (plan[4] as ToolBatchExecutor.ExecutionPlanStep.ParallelStep).items.size)
    }

    @Test
    fun testEmpiricalLatencyPercentilesAndThroughputStressTest() {
        val latenciesMs = Collections.synchronizedList(mutableListOf<Long>())
        val engagedThreads = ConcurrentHashMap.newKeySet<String>()
        val registry = McpRegistry()

        val executor = object : ToolBatchExecutor(registry, maxConcurrency = 20) {
            override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                engagedThreads.add(Thread.currentThread().name)
                val start = System.nanoTime()
                // Simulación determinista de I/O móvil (10ms a 35ms)
                val simDuration = 10L + (tc.id.hashCode() and 0x7FFFFFFF) % 25L
                Thread.sleep(simDuration)
                val durationMs = (System.nanoTime() - start) / 1_000_000L
                latenciesMs.add(durationMs)
                return McpToolResult(callId = tc.id, toolName = tc.name, content = "{\"status\":\"ok\"}")
            }
        }

        // Ejecutar 100 herramientas paralelas en 5 lotes de 20
        val totalCallsCount = 100
        val allCalls = (1..totalCallsCount).map { i ->
            CompletedToolCall(id = "perf-call-$i", name = "get_device_telemetry", argumentsJson = "{}")
        }

        val startTotal = System.currentTimeMillis()
        var totalSequentialEstimate = 0L
        var totalExecutedResults = 0

        for (chunk in allCalls.chunked(20)) {
            val summary = executor.executeBatch(chunk)
            totalSequentialEstimate += summary.sequentialEstimateMs
            totalExecutedResults += summary.results.size
            assertEquals(chunk.size, summary.results.size)
        }

        val totalDurationMs = maxOf(1L, System.currentTimeMillis() - startTotal)
        val speedupRatio = totalSequentialEstimate.toDouble() / totalDurationMs.toDouble()
        val opsPerSec = (totalExecutedResults.toDouble() / (totalDurationMs.toDouble() / 1000.0))

        val sortedLatencies = synchronized(latenciesMs) { latenciesMs.sorted() }
        val minLat = sortedLatencies.first()
        val maxLat = sortedLatencies.last()
        val avgLat = sortedLatencies.average()
        val p50 = sortedLatencies[(sortedLatencies.size * 0.50).toInt()]
        val p90 = sortedLatencies[(sortedLatencies.size * 0.90).toInt()]
        val p99 = sortedLatencies[(sortedLatencies.size * 0.99).toInt()]

        println("=== EMPIRICAL PERFORMANCE & CONCURRENCY BENCHMARK ===")
        println("Total Calls Processed: $totalExecutedResults")
        println("Total Wall-Clock Time: ${totalDurationMs} ms")
        println("Sequential Equivalent: ${totalSequentialEstimate} ms")
        println("Empirical Speedup: ${String.format(java.util.Locale.US, "%.2f", speedupRatio)}x")
        println("Throughput: ${String.format(java.util.Locale.US, "%.2f", opsPerSec)} ops/sec")
        println("Threads Engaged: ${engagedThreads.size}")
        println("Latency Percentiles:")
        println("  Min: ${minLat} ms")
        println("  Avg: ${String.format(java.util.Locale.US, "%.2f", avgLat)} ms")
        println("  p50: ${p50} ms")
        println("  p90: ${p90} ms")
        println("  p99: ${p99} ms")
        println("  Max: ${maxLat} ms")
        println("=====================================================")

        assertEquals(100, totalExecutedResults)
        assertTrue("Speedup debe ser >= 3.0x", speedupRatio >= 3.0)
        assertTrue("Debe utilizar al menos 10 hilos concurrentes", engagedThreads.size >= 10)
        assertTrue("Throughput debe ser mayor a 50 ops/sec", opsPerSec >= 50.0)
    }

    @Test
    fun testMidFlightEstopInterceptionInBatch() {
        try {
            EstopSentinel.disengage()
            val registry = McpRegistry()
            val executedCalls = mutableListOf<String>()

            val executor = object : ToolBatchExecutor(registry, maxConcurrency = 20) {
                override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                    // Si se alcanza el 3er elemento, se activa ESTOP
                    if (tc.id == "c3") {
                        EstopSentinel.engage("Activación ESTOP en test")
                    }
                    val res = super.executeSingleTool(tc, isWebTainted)
                    synchronized(executedCalls) {
                        executedCalls.add(tc.id)
                    }
                    return res
                }
            }

            val calls = listOf(
                CompletedToolCall("c1", "set_clipboard_text", """{"text":"val-1"}"""),
                CompletedToolCall("c2", "set_clipboard_text", """{"text":"val-2"}"""),
                CompletedToolCall("c3", "set_clipboard_text", """{"text":"val-3"}"""),
                CompletedToolCall("c4", "set_clipboard_text", """{"text":"val-4"}"""),
                CompletedToolCall("c5", "set_clipboard_text", """{"text":"val-5"}""")
            )

            val summary = executor.executeBatch(calls)
            assertEquals(5, summary.results.size)

            // c1 y c2 se ejecutaron antes de ESTOP
            assertFalse(summary.results[0].isError)
            assertFalse(summary.results[1].isError)

            // c4 y c5 deben haberse cancelado inmediatamente por ESTOP
            assertTrue("c4 debe ser cancelada por ESTOP", summary.results[3].isError)
            assertTrue(summary.results[3].content.contains("Parada de Emergencia"))
            assertTrue("c5 debe ser cancelada por ESTOP", summary.results[4].isError)
            assertTrue(summary.results[4].content.contains("Parada de Emergencia"))
        } finally {
            EstopSentinel.disengage()
        }
    }

    @Test
    fun testResilienceAgainstExceptionsAndLatchSafety() {
        val registry = McpRegistry()
        val callbackReceived = ConcurrentLinkedQueue<String>()

        val executor = object : ToolBatchExecutor(registry, maxConcurrency = 20) {
            override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                if (tc.id.endsWith("-fail")) {
                    throw RuntimeException("Simulated worker exception for ${tc.id}")
                }
                return McpToolResult(callId = tc.id, toolName = tc.name, content = "success-${tc.id}")
            }
        }

        // 20 herramientas: 15 exitosas, 5 con excepción
        val calls = (1..20).map { i ->
            val id = if (i % 4 == 0) "call-$i-fail" else "call-$i"
            CompletedToolCall(id = id, name = "get_battery_status", argumentsJson = "{}")
        }

        val summary = executor.executeBatch(calls) { tc, res ->
            callbackReceived.add(res.callId)
        }

        // El CountDownLatch debe haber completado sin colgarse
        assertEquals(20, summary.results.size)
        assertEquals(20, callbackReceived.size)

        // Verificar resultados individuales preservados en orden exacto
        for (i in 1..20) {
            val res = summary.results[i - 1]
            if (i % 4 == 0) {
                assertTrue("Llamada que falló debe marcar isError=true", res.isError)
                assertTrue(res.content.contains("Error de ejecución concurrente"))
            } else {
                assertFalse("Llamada exitosa no debe tener error", res.isError)
                assertEquals("success-call-$i", res.content)
            }
        }
    }

    @Test
    fun testPositionalOrderPreservedUnderChaoticLatencies() {
        val registry = McpRegistry()
        val completedCallbackOrder = ConcurrentLinkedQueue<String>()

        val executor = object : ToolBatchExecutor(registry, maxConcurrency = 20) {
            override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                val index = tc.id.removePrefix("call-").toInt()
                // Latencia invertida: call-1 tarda 60ms, call-20 tarda 3ms
                val sleepTime = maxOf(3L, (21 - index) * 3L)
                Thread.sleep(sleepTime)
                return McpToolResult(callId = tc.id, toolName = tc.name, content = "content-$index")
            }
        }

        val calls = (1..20).map { i ->
            CompletedToolCall(id = "call-$i", name = "get_wifi_status", argumentsJson = "{}")
        }

        val summary = executor.executeBatch(calls) { tc, res ->
            completedCallbackOrder.add(tc.id)
        }

        assertEquals(20, summary.results.size)
        val callbackList = completedCallbackOrder.toList()
        assertEquals(20, callbackList.size)

        // Validar eliminación de Head-of-Line blocking: call-20 debe haber completado antes que call-1
        assertNotEquals("call-1 no debe ser la primera en reportar callback", "call-1", callbackList[0])
        assertEquals("call-1 debe haber sido de las últimas en completar callback", "call-1", callbackList.last())

        // Validar fidelidad de orden posicional estricto en el resultado final consolidado
        for (i in 1..20) {
            assertEquals("call-$i", summary.results[i - 1].callId)
            assertEquals("content-$i", summary.results[i - 1].content)
        }
    }

    @Test
    fun testBoundaryConditionsEmptyAndSingleCallBatches() {
        val registry = McpRegistry()
        val executor = ToolBatchExecutor(registry, maxConcurrency = 20)

        // Caso 1: Lote vacío
        val emptySummary = executor.executeBatch(emptyList())
        assertEquals(0, emptySummary.results.size)
        assertEquals(0, emptySummary.parallelCallsCount)
        assertEquals(0, emptySummary.sequentialCallsCount)
        assertEquals(1.0, emptySummary.speedupRatio, 0.01)

        // Caso 2: Un solo elemento paralelo (evalúa fast-path sin sobrecarga de pool)
        var callbackFired = false
        val singleCall = listOf(CompletedToolCall("single-1", "get_battery_status", "{}"))
        val singleSummary = executor.executeBatch(singleCall) { _, _ ->
            callbackFired = true
        }
        assertEquals(1, singleSummary.results.size)
        assertEquals(1, singleSummary.parallelCallsCount)
        assertTrue(callbackFired)
    }
}
