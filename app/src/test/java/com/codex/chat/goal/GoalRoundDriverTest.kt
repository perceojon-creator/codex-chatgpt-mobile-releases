package com.codex.chat.goal

import com.codex.chat.core.goal.engine.GoalEngine
import com.codex.chat.core.goal.engine.GoalRoundDriver
import com.codex.chat.core.goal.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 2: Pruebas unitarias del conductor autónomo de rondas de objetivos.
 */
class GoalRoundDriverTest {

    private lateinit var engine: GoalEngine
    private lateinit var driver: GoalRoundDriver

    @Before
    fun setUp() {
        engine = GoalEngine()
        driver = GoalRoundDriver()
    }

    @Test
    fun render_goal_round_prompt_genera_estructura_xml_canonica() {
        val snapshot = GoalSnapshot(
            id = "g-test",
            revision = 1,
            objective = "Verificar seguridad CaMeL",
            maxGoalRounds = 12
        )

        val prompt = driver.renderGoalRoundPrompt(snapshot, round = 3)
        assertTrue(prompt.startsWith("<goal_round>"))
        assertTrue(prompt.endsWith("</goal_round>"))
        assertTrue(prompt.contains("Objective: \"Verificar seguridad CaMeL\""))
        assertTrue(prompt.contains("Round: 3/12"))
        assertTrue(prompt.contains("Continue working toward the objective in this same session."))
    }

    @Test
    fun should_drive_es_verdadero_solo_si_activo_armado_y_dentro_del_limite() {
        val active = GoalSnapshot(objective = "A", phase = GoalPhase.ACTIVE, activation = GoalActivation.ARMED, maxGoalRounds = 5, roundsStarted = 2)
        assertTrue(driver.shouldDrive(active))

        val disarmed = active.copy(activation = GoalActivation.DISARMED)
        assertFalse(driver.shouldDrive(disarmed))

        val paused = active.copy(phase = GoalPhase.PAUSED)
        assertFalse(driver.shouldDrive(paused))

        val completed = active.copy(phase = GoalPhase.COMPLETE)
        assertFalse(driver.shouldDrive(completed))

        val exhausted = active.copy(roundsStarted = 5)
        assertFalse(driver.shouldDrive(exhausted))

        assertFalse(driver.shouldDrive(null))
    }

    @Test
    fun next_round_prompt_avanza_ronda_y_genera_prompt_autonomo() {
        engine.createGoal("Optimizar SQLite WAL", maxRounds = 5)

        val promptR1 = driver.nextRoundPrompt(engine)
        assertNotNull(promptR1)
        assertTrue(promptR1!!.contains("Round: 1/5"))
        assertEquals(1, engine.getGoal()?.roundsStarted)

        val promptR2 = driver.nextRoundPrompt(engine)
        assertNotNull(promptR2)
        assertTrue(promptR2!!.contains("Round: 2/5"))
        assertEquals(2, engine.getGoal()?.roundsStarted)
    }

    @Test
    fun on_human_interruption_desarma_el_motor_para_dar_prioridad_al_usuario() {
        engine.createGoal("Tarea larga")
        assertTrue(driver.shouldDrive(engine.getGoal()))

        // El usuario escribe un mensaje manual -> interrupción humana
        driver.onHumanInterruption(engine)
        assertFalse("El conductor debe desarmarse ante interrupción humana", driver.shouldDrive(engine.getGoal()))
        assertEquals(GoalActivation.DISARMED, engine.getGoal()?.activation)
    }
}