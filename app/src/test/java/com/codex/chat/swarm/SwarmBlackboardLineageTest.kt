package com.codex.chat.swarm

import com.codex.chat.core.swarm.blackboard.SwarmBlackboard
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Fase 3: Pruebas unitarias de la Pizarra Compartida (Swarm Blackboard).
 * Verifica operaciones concurrentes, aislamiento entre sesiones y persistencia de hallazgos.
 */
class SwarmBlackboardLineageTest {

    private lateinit var blackboard: SwarmBlackboard

    @Before
    fun setUp() {
        blackboard = SwarmBlackboard()
    }

    @Test
    fun escritura_concurrente_en_pizarra_no_produce_bloqueo_ni_perdida_de_datos() {
        val nWorkers = 8
        val executor = Executors.newFixedThreadPool(nWorkers)
        val latch = CountDownLatch(nWorkers)
        val sessionId = "session-concurrent-1"

        for (i in 1..nWorkers) {
            executor.submit {
                try {
                    blackboard.postFinding(sessionId, "task-$i", "dato_$i", "valor_producido_$i")
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Todas las escrituras concurrentes deben completar en < 2s", latch.await(2, TimeUnit.SECONDS))
        executor.shutdown()

        val findings = blackboard.getAllFindings(sessionId)
        assertEquals(nWorkers, findings.size)
        for (i in 1..nWorkers) {
            assertEquals("valor_producido_$i", findings["dato_$i"])
        }
    }

    @Test
    fun consulta_de_hallazgos_esta_aislada_entre_sesiones_distintas() {
        blackboard.postFinding("session-A", "t1", "clave_comun", "valor_A")
        blackboard.postFinding("session-B", "t2", "clave_comun", "valor_B")

        assertEquals("valor_A", blackboard.getFinding("session-A", "clave_comun"))
        assertEquals("valor_B", blackboard.getFinding("session-B", "clave_comun"))
    }

    @Test
    fun limpieza_de_sesion_purga_solo_los_hallazgos_de_dicha_sesion() {
        blackboard.postFinding("sesion-1", "t1", "temp", "100")
        blackboard.postFinding("sesion-2", "t2", "temp", "200")

        blackboard.clearSession("sesion-1")

        assertTrue(blackboard.getAllFindings("sesion-1").isEmpty())
        assertEquals("200", blackboard.getFinding("sesion-2", "temp"))
    }
}