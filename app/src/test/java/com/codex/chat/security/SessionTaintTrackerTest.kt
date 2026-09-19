package com.codex.chat.security

import com.codex.chat.core.mcp.taint.SessionTaintTracker
import com.codex.chat.core.mcp.taint.TaintOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Auditoria v1.0.79 SEC-3: isWebTainted era un boolean por turno. Un SMS adversarial
 * entraba en el turno N marcado, pero en el N+1 ya no lo estaba.
 * SessionTaintTracker persiste la contaminacion para toda la sesion.
 */
class SessionTaintTrackerTest {

    private lateinit var tracker: SessionTaintTracker

    @Before
    fun setUp() { tracker = SessionTaintTracker() }

    @Test
    fun una_sesion_nueva_no_esta_contaminada() {
        assertFalse(tracker.isSessionTainted())
    }

    @Test
    fun marcar_web_contamina_la_sesion_permanentemente() {
        tracker.markTainted(TaintOrigin.WEB_SEARCH)
        assertTrue(tracker.isSessionTainted())
    }

    @Test
    fun marcar_sms_contamina_la_sesion_permanentemente() {
        tracker.markTainted(TaintOrigin.SMS_READ)
        assertTrue(tracker.isSessionTainted())
    }

    @Test
    fun la_contaminacion_es_monotonica_no_se_puede_limpiar() {
        tracker.markTainted(TaintOrigin.WEB_SEARCH)
        tracker.clearTaint()
        assertTrue("La contaminacion debe persistir incluso tras un intento de limpieza",
            tracker.isSessionTainted())
    }

    @Test
    fun registra_el_origen_de_la_contaminacion() {
        tracker.markTainted(TaintOrigin.WEB_SEARCH)
        tracker.markTainted(TaintOrigin.SMS_READ)
        val origenes = tracker.taintOrigins()
        assertTrue(origenes.contains(TaintOrigin.WEB_SEARCH))
        assertTrue(origenes.contains(TaintOrigin.SMS_READ))
    }

    @Test
    fun multiples_origenes_no_duplican_la_contaminacion() {
        repeat(5) { tracker.markTainted(TaintOrigin.WEB_SEARCH) }
        assertTrue(tracker.taintOrigins().size == 1)
    }

    @Test
    fun una_sesion_no_contaminada_produce_isWebTainted_false() {
        assertFalse(tracker.isWebTainted())
    }

    @Test
    fun sms_contamina_isWebTainted_tambien() {
        tracker.markTainted(TaintOrigin.SMS_READ)
        assertTrue("SMS adversarial debe activar isWebTainted para el guardian",
            tracker.isWebTainted())
    }

    @Test
    fun notificacion_adversarial_contamina_isWebTainted() {
        tracker.markTainted(TaintOrigin.NOTIFICATION_READ)
        assertTrue(tracker.isWebTainted())
    }

    @Test
    fun registra_todos_los_origenes_sin_duplicar() {
        TaintOrigin.entries.forEach { tracker.markTainted(it) }
        assertTrue(tracker.taintOrigins().containsAll(TaintOrigin.entries.toSet()))
        assertEquals(TaintOrigin.entries.size, tracker.taintOrigins().size)
    }
}
