package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.ToolHealthState
import com.codex.chat.core.mcp.ToolQuarantineRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidToolQuarantineTest {

    private lateinit var registry: ToolQuarantineRegistry
    private lateinit var mcpRegistry: McpRegistry

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        registry = ToolQuarantineRegistry(
            maxConsecutiveFailures = 3,
            maxConsecutiveTimeouts = 2,
            defaultCooldownMs = 200L // 200ms para pruebas de cooldown rápido
        )
        registry.resetAll()
        mcpRegistry = McpRegistry(context)
    }

    @Test
    fun testRecordSuccessTelemetry() {
        val toolName = "evaluate_math"
        registry.recordExecution(toolName, isSuccess = true, durationMs = 15L)
        registry.recordExecution(toolName, isSuccess = true, durationMs = 25L)

        val tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(2L, tel.totalCalls)
        assertEquals(2L, tel.successCount)
        assertEquals(0L, tel.failureCount)
        assertEquals(0, tel.consecutiveFailures)
        assertEquals(40L, tel.totalDurationMs)
        assertEquals(15L, tel.minDurationMs)
        assertEquals(25L, tel.maxDurationMs)
        assertEquals(20.0, tel.avgDurationMs, 0.01)
        assertEquals(ToolHealthState.HEALTHY, tel.state)
        assertNull(tel.lastError)
    }

    @Test
    fun testConsecutiveFailuresTriggersQuarantine() {
        val toolName = "flaky_device_sensor"

        // Fallo 1
        registry.recordExecution(toolName, isSuccess = false, durationMs = 10L, errorMessage = "Sensor offline")
        var tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(1, tel.consecutiveFailures)
        assertEquals(ToolHealthState.HEALTHY, tel.state)
        assertFalse(registry.isQuarantined(toolName))

        // Fallo 2
        registry.recordExecution(toolName, isSuccess = false, durationMs = 10L, errorMessage = "Sensor timeout")
        tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(2, tel.consecutiveFailures)
        assertEquals(ToolHealthState.HEALTHY, tel.state)
        assertFalse(registry.isQuarantined(toolName))

        // Fallo 3: Alcanza maxConsecutiveFailures = 3 -> Circuit breaker trip
        registry.recordExecution(toolName, isSuccess = false, durationMs = 10L, errorMessage = "Fatal hardware error")
        tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(3, tel.consecutiveFailures)
        assertEquals(ToolHealthState.QUARANTINED, tel.state)
        assertTrue("La herramienta debe estar en cuarentena", registry.isQuarantined(toolName))

        val (canRun, reason) = registry.canExecute(toolName)
        assertFalse("No debe permitirse ejecución bajo cuarentena", canRun)
        assertNotNull(reason)
        assertTrue(reason!!.contains("[AUTO_QUARANTINE]"))
    }

    @Test
    fun testCooldownExpirationToProbationAndRecovery() {
        val toolName = "recovering_service"

        // Forzar 3 fallos para entrar en cuarentena
        repeat(3) {
            registry.recordExecution(toolName, isSuccess = false, durationMs = 5L, errorMessage = "Fail $it")
        }
        assertTrue("Debe estar en cuarentena", registry.isQuarantined(toolName))

        // Esperar 250ms (cooldown configurado en 200ms)
        Thread.sleep(250)

        // isQuarantined debe evaluar expiración y transmutar a PROBATION
        assertFalse("Cuarentena debe haber expirado", registry.isQuarantined(toolName))
        var tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(ToolHealthState.PROBATION, tel.state)

        // Prueba en periodo de prueba con éxito -> Restauración a HEALTHY
        registry.recordExecution(toolName, isSuccess = true, durationMs = 8L)
        tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(ToolHealthState.HEALTHY, tel.state)
        assertEquals(0, tel.consecutiveFailures)
        assertNull(tel.quarantinedAt)
    }

    @Test
    fun testProbationFailureReQuarantinesWithBackoff() {
        val toolName = "unstable_network_call"

        // Entrar en cuarentena
        repeat(3) {
            registry.recordExecution(toolName, isSuccess = false, durationMs = 5L, errorMessage = "Net error")
        }
        assertTrue(registry.isQuarantined(toolName))

        Thread.sleep(250)
        assertFalse(registry.isQuarantined(toolName))
        var tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(ToolHealthState.PROBATION, tel.state)
        val initialCooldown = tel.cooldownDurationMs

        // Fallo durante PROBATION
        registry.recordExecution(toolName, isSuccess = false, durationMs = 12L, errorMessage = "Crash in probation")
        tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(ToolHealthState.QUARANTINED, tel.state)
        assertTrue("Cooldown debe duplicarse tras fallar en probation", tel.cooldownDurationMs >= initialCooldown * 2)
    }

    @Test
    fun testManualResetRestoresHealthy() {
        val toolName = "manual_recovery_tool"
        repeat(3) {
            registry.recordExecution(toolName, isSuccess = false, durationMs = 5L, errorMessage = "Fail")
        }
        assertTrue(registry.isQuarantined(toolName))

        val resetSuccess = registry.resetTool(toolName)
        assertTrue("resetTool debe retornar true", resetSuccess)

        val tel = registry.getOrCreateTelemetry(toolName)
        assertEquals(ToolHealthState.HEALTHY, tel.state)
        assertEquals(0, tel.consecutiveFailures)
        assertFalse(registry.isQuarantined(toolName))
    }

    @Test
    fun testMcpRegistryQuarantineIntegration() {
        val toolName = "evaluate_math"
        // Verificar que la herramienta existe en herramientas activas
        val toolsBefore = mcpRegistry.getAllActiveTools()
        assertTrue("evaluate_math debe estar activa inicialmente", toolsBefore.any { it.name == toolName })

        // Aislar la herramienta en la cuarentena de McpRegistry
        val qRegistry = mcpRegistry.quarantineRegistry
        repeat(qRegistry.maxConsecutiveFailures) {
            qRegistry.recordExecution(toolName, isSuccess = false, durationMs = 10L, errorMessage = "Simulated crash")
        }
        assertTrue("evaluate_math debe estar en cuarentena", qRegistry.isQuarantined(toolName))

        // 1. getAllActiveTools() debe filtrarla automáticamente
        val toolsAfter = mcpRegistry.getAllActiveTools()
        assertFalse("evaluate_math NO debe aparecer en getAllActiveTools() mientras esté en cuarentena", toolsAfter.any { it.name == toolName })

        // 2. executeToolWithCallId debe rechazarla inmediatamente con mensaje de auto-cuarentena
        val res = mcpRegistry.executeToolWithCallId("call-test", toolName, "{\"expression\":\"2+2\"}")
        assertTrue("Debe marcarse como error", res.isError)
        assertTrue("Debe contener mensaje [AUTO_QUARANTINE]", res.content.contains("[AUTO_QUARANTINE]"))

        // 3. Restaurar la herramienta
        qRegistry.resetTool(toolName)
        val toolsRestored = mcpRegistry.getAllActiveTools()
        assertTrue("evaluate_math debe reaparecer tras reset", toolsRestored.any { it.name == toolName })
    }
}
