package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.approval.ToolApprovalPolicy
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Matriz Completa de Information Flow Control (IFC) y Arquitectura CaMeL (Google DeepMind 2025/2026).
 *
 * Evalúa los 25 escenarios formales del modelo Biba de integridad:
 * 1. Separación de flujo de control (usuario) vs flujo de datos (web).
 * 2. Matriz ortogonal de 4 niveles de riesgo x 3 políticas x estado de Taint.
 * 3. Invariantes constitucionales fail-safe en Nivel 3 (FULL_ACCESS).
 * 4. Aislamiento estricto de la allowlist de sesión frente a contaminación web.
 */
class CamelInformationFlowControlMatrixTest {

    // --- 1. Matriz de Nivel 1: ALWAYS_ASK (Preguntar Siempre) ---

    @Test
    fun test_matrix_alwaysAsk_safe_clean_requiresApproval() {
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ALWAYS_ASK))
    }

    @Test
    fun test_matrix_alwaysAsk_safe_tainted_requiresApproval() {
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = true)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ALWAYS_ASK))
    }

    @Test
    fun test_matrix_alwaysAsk_sensitive_clean_requiresApproval() {
        val req = ApprovalRequest("get_contacts", "{}", ToolRiskLevel.SENSITIVE, "Device", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ALWAYS_ASK))
    }

    @Test
    fun test_matrix_alwaysAsk_destructive_clean_requiresApproval() {
        val req = ApprovalRequest("write_file", "{}", ToolRiskLevel.DESTRUCTIVE, "FS", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ALWAYS_ASK))
    }

    @Test
    fun test_matrix_alwaysAsk_root_clean_requiresApproval() {
        val req = ApprovalRequest("execute_root_command", "{}", ToolRiskLevel.ROOT, "Root", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ALWAYS_ASK))
    }

    // --- 2. Matriz de Nivel 2: ASK_ON_RISK (Preguntar en Riesgo) ---

    @Test
    fun test_matrix_askOnRisk_safe_clean_runsAutonomously() {
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = false)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    @Test
    fun test_matrix_askOnRisk_safe_tainted_runsAutonomously() {
        // En ASK_ON_RISK, lecturas inocuas aunque provengan de web no destruyen el sistema
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = true)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    @Test
    fun test_matrix_askOnRisk_sensitive_clean_requiresApproval() {
        val req = ApprovalRequest("read_sms", "{}", ToolRiskLevel.SENSITIVE, "Telephony", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    @Test
    fun test_matrix_askOnRisk_sensitive_tainted_requiresApproval() {
        val req = ApprovalRequest("read_sms", "{}", ToolRiskLevel.SENSITIVE, "Telephony", isWebTainted = true)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    @Test
    fun test_matrix_askOnRisk_destructive_clean_requiresApproval() {
        val req = ApprovalRequest("write_file", "{}", ToolRiskLevel.DESTRUCTIVE, "FS", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    @Test
    fun test_matrix_askOnRisk_destructive_tainted_requiresApproval() {
        val req = ApprovalRequest("delete_file", "{}", ToolRiskLevel.DESTRUCTIVE, "FS", isWebTainted = true)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    @Test
    fun test_matrix_askOnRisk_root_clean_requiresApproval() {
        val req = ApprovalRequest("execute_root_command", "{}", ToolRiskLevel.ROOT, "Root", isWebTainted = false)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.ASK_ON_RISK))
    }

    // --- 3. Matriz de Nivel 3: FULL_ACCESS (Autonomía del Usuario vs Freno Web) ---

    @Test
    fun test_matrix_fullAccess_safe_clean_runsAutonomously() {
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = false)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_safe_tainted_runsAutonomously() {
        val req = ApprovalRequest("get_battery", "{}", ToolRiskLevel.SAFE, "Device", isWebTainted = true)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_sensitive_clean_runsAutonomously() {
        // Pedido directo del usuario: lee contactos sin interrumpir
        val req = ApprovalRequest("get_contacts", "{}", ToolRiskLevel.SENSITIVE, "Device", isWebTainted = false)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_sensitive_tainted_runsAutonomously_ifNoExfil() {
        // Lectura de sensor inducida por web sin exfiltración
        val req = ApprovalRequest("get_wifi_status", "{}", ToolRiskLevel.SENSITIVE, "Device", isWebTainted = true)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_destructive_clean_runsAutonomously() {
        // El usuario pide crear un archivo de código: se crea sin diálogo
        val req = ApprovalRequest("write_file", """{"path":"/sdcard/app.kt"}""", ToolRiskLevel.DESTRUCTIVE, "FS", isWebTainted = false)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_destructive_tainted_BLOCKS_andRequiresApproval() {
        // CaMeL Invariant: Si la llamada destructiva proviene de búsqueda web externa, SE FRENA EN SECO
        val req = ApprovalRequest("write_file", """{"path":"/sdcard/malicious.sh"}""", ToolRiskLevel.DESTRUCTIVE, "FS", isWebTainted = true)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_root_clean_runsAutonomously_forSafeCommands() {
        // Comando root de sólo lectura pedido por el usuario
        val req = ApprovalRequest("execute_root_command", """{"command":"ls /data"}""", ToolRiskLevel.ROOT, "Root", isWebTainted = false)
        assertFalse(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_fullAccess_root_tainted_BLOCKS_andRequiresApproval() {
        // CaMeL Invariant: Comando root inducido por web SIEMPRE pide confirmación
        val req = ApprovalRequest("execute_root_command", """{"command":"ls /data"}""", ToolRiskLevel.ROOT, "Root", isWebTainted = true)
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    // --- 4. Invariantes de Destrucción Irreversible & Peligro Crítico ---

    @Test
    fun test_matrix_criticalDanger_alwaysRequiresApproval_evenInFullAccess_untainted() {
        val req = ApprovalRequest(
            toolName = "execute_root_command",
            argumentsJson = """{"command":"rm -rf /"}""",
            risk = ToolRiskLevel.ROOT,
            serverName = "Root",
            isWebTainted = false,
            dangerReason = "Destrucción crítica detectada"
        )
        assertTrue(ToolApprovalPolicy.requiresApproval(req, ApprovalPolicy.FULL_ACCESS))
    }

    @Test
    fun test_matrix_irreversible_deleteFile_isCorrectlyClassified() {
        assertTrue(ToolApprovalPolicy.isIrreversible("delete_file"))
        assertTrue(ToolApprovalPolicy.isIrreversible("DELETE_FILE"))
        assertTrue(ToolApprovalPolicy.isIrreversible("delete_memory"))
        assertFalse(ToolApprovalPolicy.isIrreversible("write_file"))
        assertFalse(ToolApprovalPolicy.isIrreversible("read_file"))
    }

    // --- 5. Aislamiento de Sesión frente a Contaminación Web ---

    @Test
    fun test_matrix_gate_tainted_request_invalidates_session_allowlist() {
        val dialogInvocations = AtomicInteger(0)
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            timeoutSegundos = 2,
            dialogRenderer = { _, cb ->
                dialogInvocations.incrementAndGet()
                cb(ApprovalDecision.APPROVED_SESSION)
            }
        )

        val cleanReq = ApprovalRequest("write_file", """{"path":"/sdcard/note.txt"}""", ToolRiskLevel.DESTRUCTIVE, "FS", isWebTainted = false)
        // 1. Aprobado y guardado en sesión
        gate.decide(cleanReq, ApprovalPolicy.ASK_ON_RISK)
        assertEquals(1, dialogInvocations.get())

        // 2. Segunda petición limpia: usa la allowlist, no llama al diálogo
        gate.decide(cleanReq, ApprovalPolicy.ASK_ON_RISK)
        assertEquals(1, dialogInvocations.get())

        // 3. Misma herramienta pero con isWebTainted = true: NO usa la allowlist, fuerza diálogo
        val taintedReq = cleanReq.copy(isWebTainted = true)
        gate.decide(taintedReq, ApprovalPolicy.ASK_ON_RISK)
        assertEquals(2, dialogInvocations.get())
    }

    @Test
    fun test_matrix_gate_criticalDanger_refuses_to_save_in_session_allowlist() {
        val dialogInvocations = AtomicInteger(0)
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            enHiloPrincipal = { false },
            timeoutSegundos = 2,
            dialogRenderer = { _, cb ->
                dialogInvocations.incrementAndGet()
                cb(ApprovalDecision.APPROVED_SESSION)
            }
        )

        val criticalReq = ApprovalRequest("execute_root_command", """{"command":"rm -rf /sdcard"}""", ToolRiskLevel.ROOT, "Root", isWebTainted = false)
        gate.decide(criticalReq, ApprovalPolicy.FULL_ACCESS)
        assertEquals(1, dialogInvocations.get())

        // Aunque el usuario marcó APPROVED_SESSION, por seguridad crítica NO debe haberse guardado en allowlist
        assertFalse(gate.isAllowedInSession("execute_root_command"))

        // La siguiente llamada crítica debe exigir confirmación OTRA VEZ
        gate.decide(criticalReq, ApprovalPolicy.FULL_ACCESS)
        assertEquals(2, dialogInvocations.get())
    }

    @Test
    fun test_matrix_policy_fromNivel_failsClosed() {
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(1))
        assertEquals(ApprovalPolicy.ASK_ON_RISK, ApprovalPolicy.fromNivel(2))
        assertEquals(ApprovalPolicy.FULL_ACCESS, ApprovalPolicy.fromNivel(3))
        // Niveles inválidos o corrompidos deben caer por defecto en ALWAYS_ASK (Fail-Closed)
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(0))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(99))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(-1))
    }
}
