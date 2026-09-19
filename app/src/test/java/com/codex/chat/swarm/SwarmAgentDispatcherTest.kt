package com.codex.chat.swarm

import com.codex.chat.core.swarm.engine.SwarmAgentDispatcher
import com.codex.chat.core.swarm.engine.SwarmDepthSentinel
import com.codex.chat.core.swarm.engine.SwarmRecursionLimitException
import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.SwarmTask
import com.codex.chat.core.swarm.model.WorkerStatus
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 1: Pruebas unitarias y de rendimiento empírico del motor concurrente SwarmAgentDispatcher.
 */
class SwarmAgentDispatcherTest {

    private lateinit var dispatcher: SwarmAgentDispatcher

    @Before
    fun setUp() {
        dispatcher = SwarmAgentDispatcher(maxConcurrency = 4)
    }

    @After
    fun tearDown() {
        dispatcher.shutdown()
    }

    @Test
    fun despacho_concurrente_de_4_workers_ejecuta_en_paralelo_con_speedup() {
        val task1 = SwarmTask(taskId = "t1", role = SwarmRole.SENSOR_OS, prompt = "p1")
        val task2 = SwarmTask(taskId = "t2", role = SwarmRole.CODE_COMPUTE, prompt = "p2")
        val task3 = SwarmTask(taskId = "t3", role = SwarmRole.MULTIMEDIA, prompt = "p3")
        val task4 = SwarmTask(taskId = "t4", role = SwarmRole.WEB_RESEARCH, prompt = "p4")

        val sleepMs = 150L
        val start = System.currentTimeMillis()

        val ticket1 = dispatcher.dispatch(task1) { Thread.sleep(sleepMs); "res1" }
        val ticket2 = dispatcher.dispatch(task2) { Thread.sleep(sleepMs); "res2" }
        val ticket3 = dispatcher.dispatch(task3) { Thread.sleep(sleepMs); "res3" }
        val ticket4 = dispatcher.dispatch(task4) { Thread.sleep(sleepMs); "res4" }

        val results = dispatcher.awaitAll(listOf(ticket1, ticket2, ticket3, ticket4), timeoutMs = 2000L)
        val totalDuration = System.currentTimeMillis() - start

        assertEquals(4, results.size)
        assertTrue(results.all { it.status == WorkerStatus.COMPLETED })
        assertTrue("4 tareas de 150ms paralelas deben terminar en < 450ms (duracion real: ${totalDuration}ms)", totalDuration < 450L)
    }

    @Test
    fun timeout_individual_cancela_worker_lento_sin_bloquear_el_pool() {
        val slowTask = SwarmTask(taskId = "slow-1", role = SwarmRole.CODE_COMPUTE, prompt = "bucle lento", timeoutMs = 150L)
        val ticket = dispatcher.dispatch(slowTask) {
            Thread.sleep(1000L)
            "no debe llegar aqui"
        }

        val result = dispatcher.await(ticket, timeoutMs = 300L)
        assertEquals(WorkerStatus.TIMED_OUT, result.status)
        assertTrue("Debe reportar timeout en mensaje de error", result.errorMessage?.contains("timed out", ignoreCase = true) == true || result.errorMessage?.contains("Tiempo excedido", ignoreCase = true) == true)
    }

    @Test
    fun fallo_en_un_worker_no_afecta_a_los_demas_workers_en_ejecucion() {
        val failTask = SwarmTask(taskId = "fail-1", role = SwarmRole.CODE_COMPUTE, prompt = "error")
        val okTask = SwarmTask(taskId = "ok-1", role = SwarmRole.SENSOR_OS, prompt = "ok")

        val ticketFail = dispatcher.dispatch(failTask) {
            throw IllegalStateException("Fallo interno simulado en worker")
        }
        val ticketOk = dispatcher.dispatch(okTask) {
            "datos correctos"
        }

        val results = dispatcher.awaitAll(listOf(ticketFail, ticketOk), timeoutMs = 2000L)
        val failRes = results.first { it.taskId == "fail-1" }
        val okRes = results.first { it.taskId == "ok-1" }

        assertEquals(WorkerStatus.FAILED, failRes.status)
        assertTrue(failRes.errorMessage!!.contains("Fallo interno simulado"))

        assertEquals(WorkerStatus.COMPLETED, okRes.status)
        assertEquals("datos correctos", okRes.outputPayload)
    }

    @Test(expected = SwarmRecursionLimitException::class)
    fun depth_sentinel_bloquea_recursion_que_supere_nivel_3() {
        val deepTask = SwarmTask(taskId = "deep-4", role = SwarmRole.ORCHESTRATOR, prompt = "profundo", depth = 4)
        dispatcher.dispatch(deepTask) { "bloqueado" }
    }

    @Test
    fun depth_sentinel_permite_profundidad_hasta_nivel_3() {
        SwarmDepthSentinel.auditDepth(1)
        SwarmDepthSentinel.auditDepth(2)
        SwarmDepthSentinel.auditDepth(3)
        // Si llega aquí sin lanzar excepción, pasa
        assertTrue(true)
    }

    @Test
    fun get_status_reporta_estado_correcto_de_ticket() {
        val task = SwarmTask(taskId = "t-status", role = SwarmRole.SENSOR_OS, prompt = "telemetria")
        val ticket = dispatcher.dispatch(task) {
            Thread.sleep(80L)
            "listo"
        }

        val result = dispatcher.await(ticket, timeoutMs = 500L)
        assertEquals(WorkerStatus.COMPLETED, result.status)
        assertEquals(WorkerStatus.COMPLETED, dispatcher.getStatus(ticket.ticketId))
    }
}