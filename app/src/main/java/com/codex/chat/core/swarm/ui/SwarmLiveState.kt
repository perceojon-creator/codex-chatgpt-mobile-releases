package com.codex.chat.core.swarm.ui

import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.WorkerStatus

/**
 * Progreso y estado reactivo en vivo de un obrero individual dentro de la interfaz.
 */
data class WorkerLiveProgress(
    val ticketId: String,
    val role: SwarmRole,
    val prompt: String,
    val status: WorkerStatus,
    val durationMs: Long = 0L,
    val error: String? = null
)

/**
 * Estado observable en vivo del panal de agentes para renderizado reactivo en el chat.
 */
data class SwarmLiveState(
    val sessionId: String,
    val workers: List<WorkerLiveProgress> = emptyList()
) {
    val totalCount: Int get() = workers.size
    val completedCount: Int get() = workers.count { it.status == WorkerStatus.COMPLETED }
    val failedCount: Int get() = workers.count { it.status == WorkerStatus.FAILED || it.status == WorkerStatus.TIMED_OUT }
    val isAllDone: Boolean get() = workers.isNotEmpty() && workers.all { it.status != WorkerStatus.QUEUED && it.status != WorkerStatus.RUNNING }
    val progressPercent: Int get() = if (workers.isEmpty()) 0 else ((completedCount + failedCount) * 100) / workers.size

    fun formatSummary(): String {
        return if (isAllDone) {
            "🐝 Enjambre: " + completedCount + "/" + totalCount + " completados (100%)"
        } else {
            "🐝 Enjambre Activo: " + completedCount + "/" + totalCount + " completados (" + progressPercent + "%)"
        }
    }

    fun withWorkerUpdate(
        ticketId: String,
        newStatus: WorkerStatus,
        durationMs: Long = 0L,
        error: String? = null
    ): SwarmLiveState {
        val updated = workers.map { w ->
            if (w.ticketId == ticketId) {
                w.copy(status = newStatus, durationMs = durationMs, error = error)
            } else {
                w
            }
        }
        return copy(workers = updated)
    }
}