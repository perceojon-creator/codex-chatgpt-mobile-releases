package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.concurrency.ToolBatchExecutor
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.parser.SseStreamParser.CompletedToolCall
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidToolBatchExecutorTest {

    private lateinit var context: android.content.Context
    private lateinit var mcpRegistry: McpRegistry
    private lateinit var executor: ToolBatchExecutor

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        mcpRegistry = McpRegistry(context)
        executor = ToolBatchExecutor(
            mcpRegistry = mcpRegistry,
            approvalGate = null,
            getApprovalPolicy = { ApprovalPolicy.FULL_ACCESS },
            maxConcurrency = 6
        )
    }

    @Test
    fun testCanRunConcurrentlyClassification() {
        // Consultas seguras y de lectura deben ser concurrentes
        assertTrue("evaluate_math debe ser concurrente", executor.canRunConcurrently("evaluate_math"))
        assertTrue("get_battery_status debe ser concurrente", executor.canRunConcurrently("get_battery_status"))
        assertTrue("get_device_telemetry debe ser concurrente", executor.canRunConcurrently("get_device_telemetry"))
        assertTrue("read_file debe ser concurrente", executor.canRunConcurrently("read_file"))
        assertTrue("list_files debe ser concurrente", executor.canRunConcurrently("list_files"))
        assertTrue("get_memory debe ser concurrente", executor.canRunConcurrently("get_memory"))

        // Mutaciones de disco, estado o comandos destructivos deben ser secuenciales
        assertFalse("write_file NO debe ser concurrente", executor.canRunConcurrently("write_file"))
        assertFalse("delete_file NO debe ser concurrente", executor.canRunConcurrently("delete_file"))
        assertFalse("create_directory NO debe ser concurrente", executor.canRunConcurrently("create_directory"))
        assertFalse("execute_python NO debe ser concurrente", executor.canRunConcurrently("execute_python"))
        assertFalse("save_memory NO debe ser concurrente", executor.canRunConcurrently("save_memory"))
        assertFalse("execute_root_command NO debe ser concurrente", executor.canRunConcurrently("execute_root_command"))
        assertFalse("root_read_file NO debe ser concurrente", executor.canRunConcurrently("root_read_file"))
    }

    @Test
    fun testPlanExecutionPartitioning() {
        val calls = listOf(
            CompletedToolCall("c1", "evaluate_math", "{\"expression\":\"2+2\"}"),
            CompletedToolCall("c2", "get_battery_status", "{}"),
            CompletedToolCall("c3", "write_file", "{\"file_path\":\"/sdcard/test.txt\"}"),
            CompletedToolCall("c4", "get_storage_info", "{}"),
            CompletedToolCall("c5", "read_file", "{\"file_path\":\"/sdcard/test.txt\"}")
        )

        val plan = executor.planExecution(calls)
        assertEquals("Debe generar 3 pasos de ejecución (Paralelo -> Secuencial -> Paralelo)", 3, plan.size)

        // Paso 1: Paralelo [c1, c2]
        assertTrue(plan[0] is ToolBatchExecutor.ExecutionPlanStep.ParallelStep)
        val step1 = plan[0] as ToolBatchExecutor.ExecutionPlanStep.ParallelStep
        assertEquals(2, step1.items.size)
        assertEquals("c1", step1.items[0].toolCall.id)
        assertEquals("c2", step1.items[1].toolCall.id)

        // Paso 2: Secuencial [c3]
        assertTrue(plan[1] is ToolBatchExecutor.ExecutionPlanStep.SequentialStep)
        val step2 = plan[1] as ToolBatchExecutor.ExecutionPlanStep.SequentialStep
        assertEquals("c3", step2.item.toolCall.id)
        assertEquals("write_file", step2.item.toolCall.name)

        // Paso 3: Paralelo [c4, c5]
        assertTrue(plan[2] is ToolBatchExecutor.ExecutionPlanStep.ParallelStep)
        val step3 = plan[2] as ToolBatchExecutor.ExecutionPlanStep.ParallelStep
        assertEquals(2, step3.items.size)
        assertEquals("c4", step3.items[0].toolCall.id)
        assertEquals("c5", step3.items[1].toolCall.id)
    }

    @Test
    fun testPreserveExactPositionalOrder() {
        val calls = listOf(
            CompletedToolCall("id-math", "evaluate_math", "{\"expression\":\"10*5\"}"),
            CompletedToolCall("id-battery", "get_battery_status", "{}"),
            CompletedToolCall("id-storage", "get_storage_info", "{}"),
            CompletedToolCall("id-wifi", "get_wifi_status", "{}")
        )

        val summary = executor.executeBatch(calls)

        assertEquals("Debe retornar exactamente 4 resultados", 4, summary.results.size)
        assertEquals("id-math", summary.results[0].callId)
        assertEquals("evaluate_math", summary.results[0].toolName)
        assertTrue("Resultado 0 debe contener 50", summary.results[0].content.contains("50"))

        assertEquals("id-battery", summary.results[1].callId)
        assertEquals("get_battery_status", summary.results[1].toolName)

        assertEquals("id-storage", summary.results[2].callId)
        assertEquals("get_storage_info", summary.results[2].toolName)

        assertEquals("id-wifi", summary.results[3].callId)
        assertEquals("get_wifi_status", summary.results[3].toolName)

        assertEquals(4, summary.parallelCallsCount)
        assertEquals(0, summary.sequentialCallsCount)
    }

    @Test
    fun testHyperConcurrentBatchExecutionSpeedupMetrics() {
        val calls = listOf(
            CompletedToolCall("c1", "evaluate_math", "{\"expression\":\"2^8\"}"),
            CompletedToolCall("c2", "evaluate_math", "{\"expression\":\"100/4\"}"),
            CompletedToolCall("c3", "get_battery_status", "{}"),
            CompletedToolCall("c4", "get_storage_info", "{}")
        )

        val summary = executor.executeBatch(calls)
        assertTrue("Duración total debe ser mayor a 0 ms", summary.totalDurationMs >= 1L)
        assertTrue("Speedup ratio debe ser >= 1.0", summary.speedupRatio >= 1.0)
        assertEquals(4, summary.results.size)
        assertFalse(summary.results[0].isError)
        assertFalse(summary.results[1].isError)
    }

    @Test
    fun testApprovalGateIntegrationWithBatch() {
        // Creamos un gate de aprobación mock que rechaza llamadas
        val mockGate = ToolApprovalGate(
            enHiloUi = { block -> block() },
            actividadViva = { true },
            dialogRenderer = { _, callback -> callback(ApprovalDecision.DENIED) }
        )

        val gatedExecutor = ToolBatchExecutor(
            mcpRegistry = mcpRegistry,
            approvalGate = mockGate,
            getApprovalPolicy = { ApprovalPolicy.ALWAYS_ASK },
            maxConcurrency = 4
        )

        val calls = listOf(
            CompletedToolCall("c-gated", "evaluate_math", "{\"expression\":\"1+1\"}")
        )

        val summary = gatedExecutor.executeBatch(calls)
        assertEquals(1, summary.results.size)
        val res = summary.results[0]
        assertEquals("c-gated", res.callId)
        assertTrue("Debe marcarse como error tras rechazo del usuario", res.isError)
        assertTrue("Mensaje debe reflejar rechazo", res.content.contains("Rechazado por el usuario"))
    }
}
