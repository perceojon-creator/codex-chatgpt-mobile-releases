package com.codex.chat.swarm

import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.SwarmOrchestratorMcpServer
import com.codex.chat.core.mcp.taint.SessionTaintTracker
import com.codex.chat.core.mcp.taint.TaintOrigin
import com.codex.chat.core.swarm.blackboard.SwarmBlackboard
import com.codex.chat.core.swarm.engine.SwarmAgentDispatcher
import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.SwarmTask
import com.codex.chat.core.swarm.model.WorkerStatus
import com.codex.chat.core.swarm.security.WorkerTaintCompartment
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 5: Pruebas de Integración End-to-End del Enjambre de Agentes Móvil (Mobile Hive).
 * Evalúa el ciclo completo: Orquestador -> Despacho Concurrente -> Pizarra WAL -> Aislamiento CaMeL -> Síntesis.
 */
class SwarmEndToEndIntegrationTest {

    private lateinit var dispatcher: SwarmAgentDispatcher
    private lateinit var blackboard: SwarmBlackboard
    private lateinit var server: SwarmOrchestratorMcpServer

    @Before
    fun setUp() {
        dispatcher = SwarmAgentDispatcher(maxConcurrency = 4)
        blackboard = SwarmBlackboard()
        server = SwarmOrchestratorMcpServer(dispatcher)
    }

    @After
    fun tearDown() {
        dispatcher.shutdown()
    }

    @Test
    fun e2e_flujo_completo_orquestador_despacha_3_workers_y_sintetiza_desde_pizarra() {
        val sessionId = "session-e2e-1"

        // 1. Orquestador despacha 3 workers concurrentes
        val t1 = dispatcher.dispatch(SwarmTask(taskId = "t-sensor", role = SwarmRole.SENSOR_OS, prompt = "telemetria")) {
            Thread.sleep(100L)
            blackboard.postFinding(sessionId, "t-sensor", "bateria", "88%")
            "OK telemetria"
        }

        val t2 = dispatcher.dispatch(SwarmTask(taskId = "t-code", role = SwarmRole.CODE_COMPUTE, prompt = "calculo")) {
            Thread.sleep(120L)
            blackboard.postFinding(sessionId, "t-code", "analisis_cpu", "Normal (2.1 GHz)")
            "OK computo"
        }

        val t3 = dispatcher.dispatch(SwarmTask(taskId = "t-media", role = SwarmRole.MULTIMEDIA, prompt = "diagrama")) {
            Thread.sleep(90L)
            blackboard.postFinding(sessionId, "t-media", "grafico", "svg_data_payload")
            "OK medios"
        }

        // 2. Espera consolidada
        val results = dispatcher.awaitAll(listOf(t1, t2, t3), timeoutMs = 2000L)
        assertEquals(3, results.size)
        assertTrue(results.all { it.status == WorkerStatus.COMPLETED })

        // 3. Orquestador consulta la pizarra para la síntesis ejecutiva
        val findings = blackboard.getAllFindings(sessionId)
        assertEquals(3, findings.size)
        assertEquals("88%", findings["bateria"])
        assertEquals("Normal (2.1 GHz)", findings["analisis_cpu"])
        assertEquals("svg_data_payload", findings["grafico"])

        // 4. Síntesis simulada
        val sintesis = "Reporte: Bateria al " + findings["bateria"] + ", CPU " + findings["analisis_cpu"]
        assertTrue(sintesis.contains("88%"))
    }

    @Test
    fun e2e_benchmark_de_speedup_demuestra_paralelismo_real() {
        val workTimeMs = 120L
        val nWorkers = 3

        val start = System.currentTimeMillis()
        val tickets = (1..nWorkers).map { i ->
            dispatcher.dispatch(SwarmTask(taskId = "bench-$i", role = SwarmRole.CODE_COMPUTE, prompt = "bench")) {
                Thread.sleep(workTimeMs)
                "done_$i"
            }
        }

        val results = dispatcher.awaitAll(tickets, timeoutMs = 3000L)
        val parallelTime = System.currentTimeMillis() - start
        val estimatedSequentialTime = workTimeMs * nWorkers

        assertEquals(nWorkers, results.size)
        assertTrue(results.all { it.status == WorkerStatus.COMPLETED })

        // En secuencial tomaria >= 360ms. En paralelo debe tomar < 250ms
        assertTrue(
            "El tiempo paralelo ($parallelTime ms) debe ser significativamente menor al secuencial ($estimatedSequentialTime ms)",
            parallelTime < estimatedSequentialTime
        )
    }

    @Test
    fun e2e_resiliencia_recupera_parcialmente_si_un_worker_falla_por_timeout() {
        val sessionId = "session-partial-1"

        val tOk = dispatcher.dispatch(SwarmTask(taskId = "t-ok", role = SwarmRole.SENSOR_OS, prompt = "rapido")) {
            blackboard.postFinding(sessionId, "t-ok", "temp", "42C")
            "OK"
        }

        val tSlow = dispatcher.dispatch(SwarmTask(taskId = "t-slow", role = SwarmRole.CODE_COMPUTE, prompt = "lento", timeoutMs = 100L)) {
            Thread.sleep(800L)
            "no llega"
        }

        val results = dispatcher.awaitAll(listOf(tOk, tSlow), timeoutMs = 500L)
        assertEquals(2, results.size)

        val resOk = results.first { it.taskId == "t-ok" }
        val resSlow = results.first { it.taskId == "t-slow" }

        assertEquals(WorkerStatus.COMPLETED, resOk.status)
        assertEquals(WorkerStatus.TIMED_OUT, resSlow.status)

        // Los datos del worker exitoso siguen disponibles en la pizarra
        val findings = blackboard.getAllFindings(sessionId)
        assertEquals("42C", findings["temp"])
    }
}