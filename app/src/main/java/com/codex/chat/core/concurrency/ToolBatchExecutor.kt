package com.codex.chat.core.concurrency

import android.content.Context
import android.util.Log
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.approval.ToolRiskClassifier
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import com.codex.chat.core.mcp.model.McpToolResult
import com.codex.chat.core.parser.SseStreamParser.CompletedToolCall
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * Representa el resultado consolidado de la ejecución por lotes hiperconcurrente.
 */
data class BatchExecutionSummary(
    val results: List<McpToolResult>,
    val totalDurationMs: Long,
    val sequentialEstimateMs: Long,
    val speedupRatio: Double,
    val parallelCallsCount: Int,
    val sequentialCallsCount: Int
)

/**
 * Ejecutor hiperconcurrente de herramientas y llamadas en lote (PTC / Tool Batching).
 * Implementa el protocolo Codex Apex / DeepSeek Harness para paralelizar consultas
 * seguras y de solo lectura mientras garantiza ejecución secuencial estricta y
 * control de riesgos para operaciones con efectos secundarios o mutaciones.
 */
class ToolBatchExecutor(
    private val mcpRegistry: McpRegistry,
    private val approvalGate: ToolApprovalGate? = null,
    private val getApprovalPolicy: () -> ApprovalPolicy = { ApprovalPolicy.ASK_ON_RISK },
    private val maxConcurrency: Int = 6
) {

    companion object {
        private const val TAG = "ToolBatchExecutor"

        // Herramientas puramente de lectura o computación sin mutación de estado
        private val READ_ONLY_CONCURRENT_TOOLS = setOf(
            // Matemáticas y cómputo
            "evaluate_math", "compute_hash",
            // Diagnóstico y telemetría de dispositivo
            "get_battery_status", "get_device_telemetry", "get_storage_info",
            "get_wifi_status", "get_storage_root", "dns_resolve", "ping_host",
            "check_root_status", "get_device_settings", "get_app_usage_stats",
            // Almacenamiento y memoria (lecturas)
            "list_memories", "get_memory", "search_memory", "wal_status",
            // Sistema de archivos (lecturas)
            "read_file", "list_files", "get_file_info", "search_files",
            // Datos personales (solo lectura)
            "get_clipboard_text", "get_call_log", "read_sms_messages",
            "list_contacts", "list_calendar_events", "get_captured_notifications"
        )

        // Herramientas con mutación de estado o efectos secundarios en disco/red
        private val MUTATING_SEQUENTIAL_TOOLS = setOf(
            "set_clipboard_text", "write_file", "delete_file", "create_directory",
            "send_sms", "save_memory", "delete_memory", "create_calendar_event",
            "set_audio_volume", "set_screen_brightness", "http_get",
            "execute_python", "execute_sandbox_command", "vibrate_device",
            "execute_root_command", "root_read_file", "root_write_file",
            "root_grant_permissions", "root_reboot_device"
        )
    }

    sealed class ExecutionPlanStep {
        data class ParallelStep(val items: List<IndexedToolCall>) : ExecutionPlanStep()
        data class SequentialStep(val item: IndexedToolCall) : ExecutionPlanStep()
    }

    data class IndexedToolCall(
        val originalIndex: Int,
        val toolCall: CompletedToolCall
    )

    /**
     * Determina si una herramienta puede ejecutarse de manera concurrente en paralelo.
     */
    fun canRunConcurrently(toolName: String): Boolean {
        val clean = toolName.lowercase().trim()
        if (clean.startsWith("root_") || clean.contains("sudo")) return false
        if (clean in MUTATING_SEQUENTIAL_TOOLS) return false
        return clean in READ_ONLY_CONCURRENT_TOOLS
    }

    /**
     * Particiona la lista de llamadas en pasos paralelos contiguos y pasos secuenciales aislados.
     */
    fun planExecution(calls: List<CompletedToolCall>): List<ExecutionPlanStep> {
        if (calls.isEmpty()) return emptyList()

        val steps = mutableListOf<ExecutionPlanStep>()
        var currentParallelChunk = mutableListOf<IndexedToolCall>()

        for ((index, tc) in calls.withIndex()) {
            val indexed = IndexedToolCall(index, tc)
            val isConcurrent = canRunConcurrently(tc.name)

            if (isConcurrent) {
                currentParallelChunk.add(indexed)
            } else {
                if (currentParallelChunk.isNotEmpty()) {
                    steps.add(ExecutionPlanStep.ParallelStep(currentParallelChunk))
                    currentParallelChunk = mutableListOf()
                }
                steps.add(ExecutionPlanStep.SequentialStep(indexed))
            }
        }

        if (currentParallelChunk.isNotEmpty()) {
            steps.add(ExecutionPlanStep.ParallelStep(currentParallelChunk))
        }

        return steps
    }

    /**
     * Ejecuta el lote completo preservando el orden posicional estricto y la fidelidad del protocolo.
     */
    fun executeBatch(
        calls: List<CompletedToolCall>,
        isWebTainted: Boolean = false,
        onToolCompleted: ((CompletedToolCall, McpToolResult) -> Unit)? = null
    ): BatchExecutionSummary {
        if (calls.isEmpty()) {
            return BatchExecutionSummary(
                results = emptyList(),
                totalDurationMs = 0L,
                sequentialEstimateMs = 0L,
                speedupRatio = 1.0,
                parallelCallsCount = 0,
                sequentialCallsCount = 0
            )
        }

        val startTotal = System.currentTimeMillis()
        val resultArray = arrayOfNulls<McpToolResult>(calls.size)
        var sequentialEstimateMs = 0L
        var parallelCount = 0
        var sequentialCount = 0

        val plan = planExecution(calls)
        val threadPool = Executors.newFixedThreadPool(maxConcurrency.coerceAtMost(calls.size))

        try {
            for (step in plan) {
                when (step) {
                    is ExecutionPlanStep.ParallelStep -> {
                        parallelCount += step.items.size
                        if (step.items.size == 1) {
                            // Optimización: un solo elemento no requiere sobrecarga de pool
                            val item = step.items[0]
                            val startItem = System.currentTimeMillis()
                            val res = executeSingleTool(item.toolCall, isWebTainted)
                            val itemDur = System.currentTimeMillis() - startItem
                            sequentialEstimateMs += itemDur
                            resultArray[item.originalIndex] = res
                            onToolCompleted?.invoke(item.toolCall, res)
                        } else {
                            // Despacho hiperconcurrente en paralelo
                            val futures = mutableListOf<Pair<IndexedToolCall, Future<Pair<McpToolResult, Long>>>>()
                            for (item in step.items) {
                                val future = threadPool.submit(Callable {
                                    val startItem = System.currentTimeMillis()
                                    val res = executeSingleTool(item.toolCall, isWebTainted)
                                    val itemDur = System.currentTimeMillis() - startItem
                                    Pair(res, itemDur)
                                })
                                futures.add(Pair(item, future))
                            }

                            for ((item, future) in futures) {
                                try {
                                    val (res, itemDur) = future.get(120, TimeUnit.SECONDS)
                                    sequentialEstimateMs += itemDur
                                    resultArray[item.originalIndex] = res
                                    onToolCompleted?.invoke(item.toolCall, res)
                                } catch (e: Exception) {
                                    Log.e(TAG, "Error esperando herramienta paralela ${item.toolCall.name}: ${e.message}", e)
                                    val errRes = McpToolResult(
                                        callId = item.toolCall.id.ifBlank { "call-" + UUID.randomUUID().toString().take(8) },
                                        toolName = item.toolCall.name,
                                        content = "Error de ejecución concurrente: ${e.message}",
                                        isError = true
                                    )
                                    resultArray[item.originalIndex] = errRes
                                    onToolCompleted?.invoke(item.toolCall, errRes)
                                }
                            }
                        }
                    }
                    is ExecutionPlanStep.SequentialStep -> {
                        sequentialCount++
                        val item = step.item
                        val startItem = System.currentTimeMillis()
                        val res = executeSingleTool(item.toolCall, isWebTainted)
                        val itemDur = System.currentTimeMillis() - startItem
                        sequentialEstimateMs += itemDur
                        resultArray[item.originalIndex] = res
                        onToolCompleted?.invoke(item.toolCall, res)
                    }
                }
            }
        } finally {
            threadPool.shutdown()
        }

        val totalDurationMs = max(1L, System.currentTimeMillis() - startTotal)
        val speedupRatio = if (totalDurationMs > 0) {
            String.format(java.util.Locale.US, "%.2f", sequentialEstimateMs.toDouble() / totalDurationMs.toDouble()).toDouble()
        } else {
            1.0
        }

        val finalList = resultArray.filterNotNull().toList()

        return BatchExecutionSummary(
            results = finalList,
            totalDurationMs = totalDurationMs,
            sequentialEstimateMs = sequentialEstimateMs,
            speedupRatio = speedupRatio.coerceAtLeast(1.0),
            parallelCallsCount = parallelCount,
            sequentialCallsCount = sequentialCount
        )
    }

    private fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
        val callId = tc.id.ifBlank { "call-" + UUID.randomUUID().toString().take(8) }
        val toolName = tc.name
        val argumentsJson = tc.argumentsJson.ifBlank { "{}" }

        val gate = approvalGate
        if (gate != null) {
            val risk = ToolRiskClassifier.classify(toolName)
            val serverName = mcpRegistry.servidorDe(toolName) ?: "Servidor MCP"
            val req = ApprovalRequest(
                toolName = toolName,
                argumentsJson = argumentsJson,
                risk = risk,
                serverName = serverName,
                isWebTainted = isWebTainted
            )
            val policy = getApprovalPolicy()
            val decision = gate.decide(req, policy)
            if (decision != ApprovalDecision.APPROVED && decision != ApprovalDecision.APPROVED_SESSION) {
                val reason = if (decision == ApprovalDecision.TIMEOUT) "Cancelado por tiempo de espera (120 s)" else "Rechazado por el usuario"
                return McpToolResult(
                    callId = callId,
                    toolName = toolName,
                    content = "Ejecución cancelada: $reason.",
                    isError = true
                )
            }
        }

        return mcpRegistry.executeToolWithCallId(callId, toolName, argumentsJson)
    }
}
