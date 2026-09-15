package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Auditoría de Concurrencia, Ciclo de Vida y Seguridad en Hilos de ToolApprovalGate.
 *
 * Evalúa 16 métodos dedicados para:
 * 1. Prevención estricta de deadlocks en hilo UI (fail-fast con IllegalStateException).
 * 2. Comportamiento ante destrucción de Activity durante el bloqueo de CountDownLatch.
 * 3. Expira de timeout y retorno fail-closed hacia DENIED.
 * 4. Resistencia ante ráfagas concurrentes de herramientas (Multi-Worker Stress Test).
 * 5. Idempotencia y protección ante callbacks múltiples o excepciones en el renderizado de UI.
 */
class ToolApprovalGateConcurrencyAuditTest {

    @Test(expected = IllegalStateException::class)
    fun test_gate_throws_illegal_state_if_called_on_main_thread() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { true }, // Simula que está en el hilo UI
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED) }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
    }

    @Test
    fun test_gate_denies_immediately_if_activity_is_dead_before_dialog() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { false }, // Actividad muerta
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED) }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        val decision = gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
        assertEquals(ApprovalDecision.DENIED, decision)
    }

    @Test
    fun test_gate_denies_if_activity_dies_while_dispatching_to_ui() {
        var actividadViva = true
        val gate = ToolApprovalGate(
            enHiloUi = { r ->
                actividadViva = false // Muere justo antes de renderizar
                r()
            },
            actividadViva = { actividadViva },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED) }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        val decision = gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
        assertEquals(ApprovalDecision.DENIED, decision)
    }

    @Test
    fun test_gate_timeout_triggers_denial() {
        val gate = ToolApprovalGate(
            enHiloUi = { r -> Thread(r).start() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            timeoutSegundos = 1, // Timeout de 1 segundo
            dialogRenderer = { _, _ ->
                // El usuario nunca responde (ignora el diálogo)
            }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        val start = System.currentTimeMillis()
        val decision = gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
        val duration = System.currentTimeMillis() - start

        assertEquals(ApprovalDecision.TIMEOUT, decision)
        assertTrue("Debe respetar el timeout configurado", duration >= 900)
    }

    @Test
    fun test_gate_user_approves_once_returns_approved() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED) }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        assertEquals(ApprovalDecision.APPROVED, gate.decide(req, ApprovalPolicy.ALWAYS_ASK))
        // No debe haberlo guardado en la allowlist de sesión
        assertFalse(gate.isAllowedInSession("test_tool"))
    }

    @Test
    fun test_gate_user_rejects_returns_denied() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.DENIED) }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        assertEquals(ApprovalDecision.DENIED, gate.decide(req, ApprovalPolicy.ALWAYS_ASK))
    }

    @Test
    fun test_gate_user_approves_session_returns_approved_session() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED_SESSION) }
        )
        val req = ApprovalRequest("write_file", """{"path":"/sdcard/test.txt"}""", ToolRiskLevel.DESTRUCTIVE, "FS")
        assertEquals(ApprovalDecision.APPROVED_SESSION, gate.decide(req, ApprovalPolicy.ALWAYS_ASK))
        assertTrue(gate.isAllowedInSession("write_file"))
    }

    @Test
    fun test_gate_session_allowlist_cleared_on_request() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED_SESSION) }
        )
        val req = ApprovalRequest("write_file", "{}", ToolRiskLevel.DESTRUCTIVE, "FS")
        gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
        assertTrue(gate.isAllowedInSession("write_file"))

        gate.clearSessionAllowlist()
        assertFalse(gate.isAllowedInSession("write_file"))
    }

    @Test
    fun test_gate_session_allowlist_case_insensitive_and_trimmed() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED_SESSION) }
        )
        val req = ApprovalRequest("  Write_File  ", "{}", ToolRiskLevel.DESTRUCTIVE, "FS")
        gate.decide(req, ApprovalPolicy.ALWAYS_ASK)

        assertTrue(gate.isAllowedInSession("write_file"))
        assertTrue(gate.isAllowedInSession("WRITE_FILE"))
        assertTrue(gate.isAllowedInSession("  write_file  "))
    }

    @Test
    fun test_gate_dialog_exception_fails_closed_to_denied() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, _ ->
                throw RuntimeException("Fallo inflador de vistas de Android")
            }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        val dec = gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
        assertEquals("Ante excepción inesperada en el diálogo debe retornar DENIED", ApprovalDecision.DENIED, dec)
    }

    @Test
    fun test_gate_untainted_safe_request_does_not_call_ui_thread() {
        val uiCalled = AtomicBoolean(false)
        val gate = ToolApprovalGate(
            enHiloUi = {
                uiCalled.set(true)
                it()
            },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED) }
        )
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = false)
        val dec = gate.decide(req, ApprovalPolicy.FULL_ACCESS)
        assertEquals(ApprovalDecision.APPROVED, dec)
        assertFalse("Llamada SAFE en FULL_ACCESS no debe tocar el hilo de UI", uiCalled.get())
    }

    @Test
    fun test_gate_dialog_callback_idempotency() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb ->
                // Llamar al callback 3 veces con decisiones opuestas
                cb(ApprovalDecision.APPROVED)
                cb(ApprovalDecision.DENIED)
                cb(ApprovalDecision.APPROVED_SESSION)
            }
        )
        val req = ApprovalRequest("test_tool", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
        val dec = gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
        assertEquals("La primera respuesta registrada debe ser la definitiva", ApprovalDecision.APPROVED, dec)
    }

    @Test
    fun test_gate_concurrent_decisions_from_multiple_worker_threads() {
        val threads = 10
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val approvedCount = AtomicInteger(0)

        val gate = ToolApprovalGate(
            enHiloUi = { r -> Thread(r).start() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            timeoutSegundos = 3,
            dialogRenderer = { _, cb ->
                // Simula respuesta humana tras 20ms
                Thread {
                    Thread.sleep(20)
                    cb(ApprovalDecision.APPROVED)
                }.start()
            }
        )

        for (i in 0 until threads) {
            executor.submit {
                try {
                    val req = ApprovalRequest("tool_$i", "{}", ToolRiskLevel.DESTRUCTIVE, "Test")
                    val dec = gate.decide(req, ApprovalPolicy.ALWAYS_ASK)
                    if (dec == ApprovalDecision.APPROVED) {
                        approvedCount.incrementAndGet()
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertEquals(threads, approvedCount.get())
    }

    @Test
    fun test_gate_session_allowlist_thread_safe_concurrent_reads_writes() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            dialogRenderer = { _, cb -> cb(ApprovalDecision.APPROVED_SESSION) }
        )

        val executor = Executors.newFixedThreadPool(8)
        val latch = CountDownLatch(8)

        for (t in 0 until 8) {
            executor.submit {
                try {
                    for (i in 0 until 50) {
                        gate.isAllowedInSession("tool_$i")
                        if (t % 2 == 0) {
                            gate.clearSessionAllowlist()
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
    }
}
