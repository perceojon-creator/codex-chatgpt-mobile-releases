package com.codex.chat.security

import com.codex.chat.core.mcp.taint.SessionTaintTracker
import com.codex.chat.core.mcp.taint.TaintOrigin
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifica que la contaminacion persiste entre turnos simulados.
 */
class SessionTaintIntegrationTest {

    @Test
    fun la_contaminacion_del_turno_N_persiste_en_el_turno_N_mas_1() {
        val tracker = SessionTaintTracker()

        // Turno N: el modelo usa busqueda web (webGrounding no vacio)
        val turnoN_webGrounding = "resultados de busqueda sobre como desbloquear un telefono"
        if (turnoN_webGrounding.isNotBlank()) tracker.markTainted(TaintOrigin.WEB_SEARCH)

        // Turno N+1: sin busqueda web, webGrounding esta vacio
        val turnoN1_webGrounding = ""
        // Antes del fix: isWebTainted = false (falso negativo)
        // Despues del fix: el tracker recuerda la contaminacion
        val isWebTaintedAntesDeFix = turnoN1_webGrounding.isNotBlank()
        val isWebTaintedDespuesDeFix = tracker.isWebTainted()

        assertFalse("El calculo por turno da falso negativo", isWebTaintedAntesDeFix)
        assertTrue("El tracker da verdadero positivo correcto", isWebTaintedDespuesDeFix)
    }

    @Test
    fun una_sesion_nueva_limpia_la_contaminacion_de_la_anterior() {
        val sesionAnterior = SessionTaintTracker()
        sesionAnterior.markTainted(TaintOrigin.WEB_SEARCH)

        // Iniciar sesion nueva = crear nueva instancia
        val sesionNueva = SessionTaintTracker()
        assertFalse("Una sesion nueva debe empezar limpia", sesionNueva.isSessionTainted())
        assertTrue("La sesion anterior sigue contaminada", sesionAnterior.isSessionTainted())
    }
}
