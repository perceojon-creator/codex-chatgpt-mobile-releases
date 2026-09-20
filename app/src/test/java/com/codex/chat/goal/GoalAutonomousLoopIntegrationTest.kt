package com.codex.chat.goal

import com.codex.chat.core.goal.engine.GoalEngine
import com.codex.chat.core.goal.engine.GoalRoundDriver
import com.codex.chat.core.goal.model.*
import com.codex.chat.core.goal.ui.GoalUiState
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.GoalMcpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 5: Prueba de Integración End-to-End del Bucle Autónomo de Objetivos (Multi-Round Goal Loop).
 * Simula la interacción completa entre GoalEngine, GoalRoundDriver, GoalMcpServer y GoalUiState.
 */
class GoalAutonomousLoopIntegrationTest {

    private lateinit var engine: GoalEngine
    private lateinit var driver: GoalRoundDriver
    private lateinit var mcpServer: GoalMcpServer

    @Before
    fun setUp() {
        engine = GoalEngine()
        driver = GoalRoundDriver()
        mcpServer = GoalMcpServer(engine)
    }

    @Test
    fun e2e_bucle_autonomo_multi_ronda_avanza_y_completa_con_cas_exitosamente() {
        // 1. Creación del objetivo (Ronda inicial)
        val createReq = McpToolCallRequest(
            id = "c1",
            toolName = "create_goal",
            argumentsJson = JSONObject().put("objective", "Auditar y endurecer ProGuard").put("max_goal_rounds", 5).toString()
        )
        val createRes = mcpServer.executeTool(createReq)
        assertFalse(createRes.isError)

        val goalId = JSONObject(createRes.content).getJSONObject("goal").getString("id")
        assertEquals(GoalPhase.ACTIVE, engine.getGoal()?.phase)
        assertEquals(GoalActivation.ARMED, engine.getGoal()?.activation)

        // UI State en Ronda 0
        val ui0 = GoalUiState.fromSnapshot(engine.getGoal())
        assertTrue(ui0.isVisible)
        assertTrue(ui0.roundBadge.contains("Ronda 0/5"))

        // 2. Simulación de fin de Turno 1 -> Driver evalúa y dispara Ronda 1
        assertTrue(driver.shouldDrive(engine.getGoal()))
        val promptR1 = driver.nextRoundPrompt(engine)
        assertNotNull(promptR1)
        assertTrue(promptR1!!.contains("Round: 1/5"))
        assertEquals(1, engine.getGoal()?.roundsStarted)

        // 3. Simulación de fin de Turno 2 -> Driver evalúa y dispara Ronda 2
        assertTrue(driver.shouldDrive(engine.getGoal()))
        val promptR2 = driver.nextRoundPrompt(engine)
        assertNotNull(promptR2)
        assertTrue(promptR2!!.contains("Round: 2/5"))
        assertEquals(2, engine.getGoal()?.roundsStarted)

        // 4. En Ronda 2, el modelo decide que el objetivo se ha logrado por completo
        // Consulta estado con get_goal para obtener la revisión actual
        val getReq = McpToolCallRequest(id = "c2", toolName = "get_goal", argumentsJson = "{}")
        val getRes = mcpServer.executeTool(getReq)
        val currentRev = JSONObject(getRes.content).getJSONObject("goal").getInt("revision")
        assertEquals(1, currentRev) // No ha habido mutación de campos duraderos hasta ahora

        // Llama a update_goal con action="complete"
        val updateReq = McpToolCallRequest(
            id = "c3",
            toolName = "update_goal",
            argumentsJson = JSONObject()
                .put("goal_id", goalId)
                .put("revision", currentRev)
                .put("action", "complete")
                .toString()
        )
        val updateRes = mcpServer.executeTool(updateReq)
        assertFalse(updateRes.isError)
        assertEquals(GoalPhase.COMPLETE, engine.getGoal()?.phase)
        assertEquals(GoalActivation.DISARMED, engine.getGoal()?.activation)
        assertEquals(2, engine.getGoal()?.revision)

        // 5. Simulación de fin de turno tras completar -> Driver NO debe continuar
        assertFalse("El conductor debe detenerse cuando el objetivo está completado", driver.shouldDrive(engine.getGoal()))
        assertNull(driver.nextRoundPrompt(engine))

        // UI State final se oculta automáticamente
        val uiFinal = GoalUiState.fromSnapshot(engine.getGoal())
        assertFalse("GoalBar debe ocultarse tras completar la meta", uiFinal.isVisible)
    }

    @Test
    fun e2e_interrupcion_del_usuario_frena_el_bucle_inmediatamente() {
        engine.createGoal("Generar reporte largo", maxRounds = 10)
        assertTrue(driver.shouldDrive(engine.getGoal()))

        // Driver avanza ronda 1
        driver.nextRoundPrompt(engine)
        assertTrue(driver.shouldDrive(engine.getGoal()))

        // El usuario escribe un mensaje manual en el chat mientras el objetivo corría
        driver.onHumanInterruption(engine)

        // El bucle queda congelado (disarmed)
        assertFalse(driver.shouldDrive(engine.getGoal()))
        assertEquals(GoalActivation.DISARMED, engine.getGoal()?.activation)
        assertEquals(GoalPhase.ACTIVE, engine.getGoal()?.phase) // La fase sigue ACTIVE pero desarmado
    }
}