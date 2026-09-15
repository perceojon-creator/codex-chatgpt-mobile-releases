package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import com.codex.chat.core.root.RootShellExecutor
import com.codex.chat.core.security.EstopEngagedException
import com.codex.chat.core.security.EstopSentinel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidEstopSentinelTest {

    private lateinit var context: android.content.Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        EstopSentinel.init(context)
        EstopSentinel.disengage()
    }

    @After
    fun tearDown() {
        EstopSentinel.disengage()
    }

    @Test
    fun testRealDevice_EstopEngageAndDisengageCycle() {
        assertFalse("ESTOP must initially be disengaged", EstopSentinel.isEngaged())
        val sentinelFile = File(context.filesDir, "ESTOP")
        assertFalse("ESTOP file must not exist initially", sentinelFile.exists())

        // Activar ESTOP
        val reason = "Parada manual durante auditoría de seguridad"
        EstopSentinel.engage(reason)

        assertTrue("ESTOP must report engaged", EstopSentinel.isEngaged())
        assertTrue("ESTOP sentinel file must be created on disk", sentinelFile.exists())

        val status = EstopSentinel.getStatus()
        assertTrue(status.isEngaged)
        assertEquals(reason, status.reason)
        assertTrue(status.engagedAt > 0L)

        // Verificar excepción de seguridad fail-safe
        try {
            EstopSentinel.checkOrThrow()
            fail("checkOrThrow must throw EstopEngagedException when engaged")
        } catch (e: EstopEngagedException) {
            assertTrue(e.message?.contains(reason) == true)
        }

        // Reanudar / Disengage
        EstopSentinel.disengage()
        assertFalse("ESTOP must be disengaged after resume", EstopSentinel.isEngaged())
        assertFalse("ESTOP sentinel file must be deleted", sentinelFile.exists())
    }

    @Test
    fun testRealDevice_EstopBlocksToolApprovalGateImmediately() {
        val gate = ToolApprovalGate(
            enHiloUi = { it() },
            actividadViva = { true },
            dialogRenderer = { _, callback -> callback(ApprovalDecision.APPROVED) }
        )

        val req = ApprovalRequest(
            toolName = "read_file",
            serverName = "filesystem",
            argumentsJson = """{"path": "notes.txt"}""",
            risk = ToolRiskLevel.SAFE
        )

        // 1. Con ESTOP desactivado y política FULL_ACCESS, debe ser aprobado
        EstopSentinel.disengage()
        val approved = gate.decide(req, ApprovalPolicy.FULL_ACCESS)
        assertEquals(ApprovalDecision.APPROVED, approved)

        // 2. Con ESTOP activado, DEBE ser DENEGADO de inmediato sin importar la política
        EstopSentinel.engage("Bloqueo de emergencia activado por el operador")
        val denied = gate.decide(req, ApprovalPolicy.FULL_ACCESS)
        assertEquals(ApprovalDecision.DENIED, denied)
    }

    @Test
    fun testRealDevice_EstopHaltsRootShellExecutor() {
        // En dispositivo sin root o con root, ESTOP debe interceptar antes de cualquier proceso
        EstopSentinel.engage("Prueba de corte total en root/shell")

        val rootCheck = RootShellExecutor.checkRootAccess()
        assertFalse("Must not be rooted or permitted under ESTOP", rootCheck.success)
        assertEquals(-999, rootCheck.exitCode)
        assertTrue(rootCheck.stderr.contains("[ESTOP ACTIVADO]"))

        val execResult = RootShellExecutor.executeSu("whoami")
        assertFalse("Execution must be aborted under ESTOP", execResult.success)
        assertEquals(-999, execResult.exitCode)
        assertTrue(execResult.stderr.contains("[ESTOP ACTIVADO]"))

        // Al desactivar ESTOP, el comportamiento normal del dispositivo se restaura
        EstopSentinel.disengage()
        val normalCheck = RootShellExecutor.checkRootAccess()
        assertNotEquals(-999, normalCheck.exitCode)
    }

    @Test
    fun testRealDevice_FailSafeFileDetection() {
        // Simular que un proceso externo creó el archivo ESTOP directamente en filesDir
        val sentinelFile = File(context.filesDir, "ESTOP")
        sentinelFile.writeText("""{"reason":"Intervención externa de monitor","engaged_at":12345678}""")

        // Re-sincronizar
        EstopSentinel.init(context)
        assertTrue("ESTOP must detect sentinel file on disk", EstopSentinel.isEngaged())
        assertEquals("Intervención externa de monitor", EstopSentinel.getStatus().reason)

        sentinelFile.delete()
        EstopSentinel.disengage()
    }
}
