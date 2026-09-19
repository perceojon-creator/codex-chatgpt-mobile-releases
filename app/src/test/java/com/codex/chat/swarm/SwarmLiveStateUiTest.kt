package com.codex.chat.swarm

import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.WorkerStatus
import com.codex.chat.core.swarm.ui.SwarmLiveState
import com.codex.chat.core.swarm.ui.WorkerLiveProgress
import org.junit.Assert.*
import org.junit.Test

/**
 * Fase 4: Pruebas unitarias del estado reactivo de UI para el Enjambre (SwarmLiveState).
 */
class SwarmLiveStateUiTest {

    @Test
    fun calculo_de_porcentajes_y_estados_de_progreso_es_exacto() {
        val w1 = WorkerLiveProgress("t1", SwarmRole.SENSOR_OS, "p1", WorkerStatus.COMPLETED)
        val w2 = WorkerLiveProgress("t2", SwarmRole.CODE_COMPUTE, "p2", WorkerStatus.RUNNING)
        val w3 = WorkerLiveProgress("t3", SwarmRole.MULTIMEDIA, "p3", WorkerStatus.QUEUED)
        val w4 = WorkerLiveProgress("t4", SwarmRole.WEB_RESEARCH, "p4", WorkerStatus.FAILED)

        val state = SwarmLiveState(sessionId = "s1", workers = listOf(w1, w2, w3, w4))

        assertEquals(4, state.totalCount)
        assertEquals(1, state.completedCount)
        assertEquals(1, state.failedCount)
        assertFalse("No todos estan listos porque w2 corre y w3 esta en cola", state.isAllDone)
        assertEquals(50, state.progressPercent) // 2 de 4 procesados (completado + fallido) = 50%
    }

    @Test
    fun withWorkerUpdate_actualiza_inmutablemente_un_solo_worker() {
        val w1 = WorkerLiveProgress("t1", SwarmRole.SENSOR_OS, "p1", WorkerStatus.RUNNING)
        val w2 = WorkerLiveProgress("t2", SwarmRole.CODE_COMPUTE, "p2", WorkerStatus.RUNNING)

        val initial = SwarmLiveState("s1", listOf(w1, w2))
        val updated = initial.withWorkerUpdate("t1", WorkerStatus.COMPLETED, durationMs = 210L)

        // Inmutabilidad: el estado inicial no cambio
        assertEquals(WorkerStatus.RUNNING, initial.workers[0].status)
        // Estado nuevo refleja la actualizacion
        assertEquals(WorkerStatus.COMPLETED, updated.workers[0].status)
        assertEquals(210L, updated.workers[0].durationMs)
        assertEquals(WorkerStatus.RUNNING, updated.workers[1].status)
    }

    @Test
    fun formatSummary_muestra_resumen_apropiado_segun_estado_activo_o_final() {
        val w1 = WorkerLiveProgress("t1", SwarmRole.SENSOR_OS, "p1", WorkerStatus.COMPLETED)
        val w2 = WorkerLiveProgress("t2", SwarmRole.CODE_COMPUTE, "p2", WorkerStatus.COMPLETED)

        val finished = SwarmLiveState("s1", listOf(w1, w2))
        assertTrue(finished.isAllDone)
        val summary = finished.formatSummary()
        assertTrue(summary.contains("2/2"))
        assertTrue(summary.contains("100%"))
    }

    @Test
    fun estado_vacio_no_lanza_division_por_cero() {
        val empty = SwarmLiveState("s0", emptyList())
        assertEquals(0, empty.totalCount)
        assertEquals(0, empty.progressPercent)
        assertFalse(empty.isAllDone)
    }
}