package com.codex.chat.goal

import com.codex.chat.core.goal.model.GoalActivation
import com.codex.chat.core.goal.model.GoalPhase
import com.codex.chat.core.goal.model.GoalSnapshot
import com.codex.chat.core.goal.ui.GoalUiState
import org.junit.Assert.*
import org.junit.Test

/**
 * Fase 4: Pruebas unitarias de la proyección visual reactiva GoalUiState para la barra de objetivos.
 */
class GoalUiStateTest {

    @Test
    fun snapshot_nulo_produce_estado_ui_oculto() {
        val ui = GoalUiState.fromSnapshot(null)
        assertFalse(ui.isVisible)
        assertEquals("", ui.objective)
        assertEquals("", ui.roundBadge)
        assertEquals(0, ui.progressPct)
    }

    @Test
    fun objetivo_activo_produce_estado_visible_con_progreso_y_badge() {
        val snapshot = GoalSnapshot(
            id = "g1",
            revision = 2,
            objective = "Refactorizar modulo de red",
            phase = GoalPhase.ACTIVE,
            activation = GoalActivation.ARMED,
            maxGoalRounds = 10,
            roundsStarted = 3
        )

        val ui = GoalUiState.fromSnapshot(snapshot)
        assertTrue(ui.isVisible)
        assertEquals("Refactorizar modulo de red", ui.objective)
        assertEquals("🎯 Ronda 3/10", ui.roundBadge)
        assertEquals(30, ui.progressPct) // 3 de 10 = 30%
        assertFalse(ui.isPaused)
        assertTrue(ui.isArmed)
    }

    @Test
    fun objetivo_pausado_refleja_isPaused_y_desarmado() {
        val snapshot = GoalSnapshot(
            id = "g2",
            revision = 3,
            objective = "Pausado",
            phase = GoalPhase.PAUSED,
            activation = GoalActivation.DISARMED,
            maxGoalRounds = 5,
            roundsStarted = 2
        )

        val ui = GoalUiState.fromSnapshot(snapshot)
        assertTrue(ui.isVisible)
        assertTrue(ui.isPaused)
        assertFalse(ui.isArmed)
        assertTrue(ui.roundBadge.contains("Pausado"))
    }

    @Test
    fun objetivo_completado_se_oculta_o_muestra_completado() {
        val snapshot = GoalSnapshot(
            id = "g3",
            revision = 4,
            objective = "Finalizado",
            phase = GoalPhase.COMPLETE,
            activation = GoalActivation.DISARMED,
            maxGoalRounds = 5,
            roundsStarted = 3
        )

        val ui = GoalUiState.fromSnapshot(snapshot)
        assertFalse("Un objetivo completado debe ocultar la barra de seguimiento activo", ui.isVisible)
    }
}