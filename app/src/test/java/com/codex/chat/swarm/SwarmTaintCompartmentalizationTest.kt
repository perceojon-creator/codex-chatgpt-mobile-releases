package com.codex.chat.swarm

import com.codex.chat.core.mcp.taint.SessionTaintTracker
import com.codex.chat.core.mcp.taint.TaintOrigin
import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.security.WorkerTaintCompartment
import org.junit.Assert.*
import org.junit.Test

/**
 * Fase 3: Pruebas unitarias de la compartimentación de Taint CaMeL en el Enjambre.
 */
class SwarmTaintCompartmentalizationTest {

    @Test
    fun worker_web_contaminado_no_propaga_taint_a_worker_sms_paralelo() {
        val workerWeb = WorkerTaintCompartment(SwarmRole.WEB_RESEARCH)
        val workerSms = WorkerTaintCompartment(SwarmRole.SENSOR_OS)

        // Worker Web se contamina al buscar en internet
        workerWeb.markTainted(TaintOrigin.WEB_SEARCH)

        assertTrue("Worker Web debe estar contaminado", workerWeb.isTainted())
        assertFalse("Worker SMS NO debe estar contaminado por la actividad del worker web", workerSms.isTainted())
    }

    @Test
    fun worker_sms_limpio_puede_ejecutar_herramienta_sensible_mientras_worker_web_esta_tainted() {
        val workerWeb = WorkerTaintCompartment(SwarmRole.WEB_RESEARCH)
        val workerSms = WorkerTaintCompartment(SwarmRole.SENSOR_OS)

        workerWeb.markTainted(TaintOrigin.WEB_SEARCH)

        // Worker SMS no esta contaminado, debe permitir herramientas sensibles de su perfil
        assertTrue(workerSms.allowsTool("read_sms_messages"))
        assertTrue(workerSms.allowsTool("get_battery_status"))

        // Worker Web contaminado no debe permitir invocar herramientas destructivas
        assertFalse(workerWeb.allowsTool("write_file"))
        assertFalse(workerWeb.allowsTool("send_sms"))
    }

    @Test
    fun la_reina_no_hereda_taint_cuando_el_worker_produce_sintesis_limpia() {
        val queenTracker = SessionTaintTracker()
        val workerWeb = WorkerTaintCompartment(SwarmRole.WEB_RESEARCH)
        workerWeb.markTainted(TaintOrigin.WEB_SEARCH)

        // El worker sintetizo la respuesta (rawPayloadConsumed = false)
        workerWeb.propagateToOrchestrator(queenTracker, rawPayloadConsumed = false)

        assertFalse("La Reina debe permanecer limpia si solo recibio sintesis estructurada", queenTracker.isSessionTainted())
    }

    @Test
    fun la_reina_hereda_taint_si_consume_payload_crudo_sin_sintetizar() {
        val queenTracker = SessionTaintTracker()
        val workerWeb = WorkerTaintCompartment(SwarmRole.WEB_RESEARCH)
        workerWeb.markTainted(TaintOrigin.WEB_SEARCH)

        // La reina consumio directamente el HTML/payload crudo sin sanitizar
        workerWeb.propagateToOrchestrator(queenTracker, rawPayloadConsumed = true)

        assertTrue("La Reina debe quedar contaminada si ingirio el payload crudo no confiable", queenTracker.isSessionTainted())
        assertTrue(queenTracker.taintOrigins().contains(TaintOrigin.WEB_SEARCH))
    }
}