package com.codex.chat.concurrency

import com.codex.chat.core.concurrency.ToolBatchExecutor
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.model.McpToolResult
import com.codex.chat.core.parser.SseStreamParser.CompletedToolCall
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
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
}