package com.codex.chat.goal

import com.codex.chat.core.goal.engine.GoalEngine
import com.codex.chat.core.goal.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 1: Pruebas de la máquina de estados de Objetivos con CAS (Compare-And-Set).
 */
class GoalEngineCasStateTest {

    private lateinit var engine: GoalEngine

    @Before
    fun setUp() {
        engine = GoalEngine()
    }

    @Test
    fun creacion_de_objetivo_inicializa_revision_1_y_fase_active() {
        val snapshot = engine.createGoal("Implementar login con biometria", maxRounds = 8)

        assertNotNull(snapshot.id)
        assertEquals(1, snapshot.revision)
        assertEquals("Implementar login con biometria", snapshot.objective)
        assertEquals(GoalPhase.ACTIVE, snapshot.phase)
        assertEquals(GoalActivation.ARMED, snapshot.activation)
        assertEquals(8, snapshot.maxGoalRounds)
        assertEquals(0, snapshot.roundsStarted)
    }

    @Test
    fun update_goal_falla_con_conflicto_si_la_revision_no_coincide_cas() {
        val initial = engine.createGoal("Objetivo CAS")
        assertEquals(1, initial.revision)

        // Intento de actualizar con una revisión desfasada (ej. 99 en vez de 1)
        val result = engine.updateGoal(
            goalId = initial.id,
            revision = 99,
            action = GoalAction.COMPLETE
        )

        assertTrue("Debe retornar conflicto CAS", result is GoalMutationResult.Conflict)
        val conflict = result as GoalMutationResult.Conflict
        assertEquals(99, conflict.expectedRevision)
        assertEquals(1, conflict.actualRevision)
        assertEquals(GoalPhase.ACTIVE, engine.getGoal()?.phase)
    }

    @Test
    fun update_goal_completa_satisfactoriamente_con_revision_correcta() {
        val initial = engine.createGoal("Completar documentacion")
        val result = engine.updateGoal(
            goalId = initial.id,
            revision = initial.revision,
            action = GoalAction.COMPLETE
        )

        assertTrue(result is GoalMutationResult.Success)
        val updated = (result as GoalMutationResult.Success).snapshot
        assertEquals(2, updated.revision)
        assertEquals(GoalPhase.COMPLETE, updated.phase)
        assertEquals(GoalActivation.DISARMED, updated.activation)
    }

    @Test
    fun pausar_y_reanudar_actualiza_fase_y_revision_monotonamente() {
        val g = engine.createGoal("Descarga pesada")
        assertEquals(1, g.revision)

        // 1. Pausar
        val resPause = engine.updateGoal(g.id, 1, GoalAction.PAUSE)
        assertTrue(resPause is GoalMutationResult.Success)
        val paused = (resPause as GoalMutationResult.Success).snapshot
        assertEquals(2, paused.revision)
        assertEquals(GoalPhase.PAUSED, paused.phase)
        assertEquals(GoalActivation.DISARMED, paused.activation)

        // 2. Reanudar con revision 2
        val resResume = engine.updateGoal(g.id, 2, GoalAction.RESUME)
        assertTrue(resResume is GoalMutationResult.Success)
        val resumed = (resResume as GoalMutationResult.Success).snapshot
        assertEquals(3, resumed.revision)
        assertEquals(GoalPhase.ACTIVE, resumed.phase)
        assertEquals(GoalActivation.ARMED, resumed.activation)
    }

    @Test
    fun record_round_started_incrementa_contador_y_bloquea_al_llegar_al_limite() {
        val g = engine.createGoal("Proceso de 3 rondas", maxRounds = 3)

        assertEquals(1, engine.recordRoundStarted())
        assertEquals(GoalPhase.ACTIVE, engine.getGoal()?.phase)

        assertEquals(2, engine.recordRoundStarted())
        assertEquals(GoalPhase.ACTIVE, engine.getGoal()?.phase)

        assertEquals(3, engine.recordRoundStarted())
        assertEquals(GoalPhase.ACTIVE, engine.getGoal()?.phase)

        // La 4ta ronda excede el límite de 3 -> pasa a BLOCKED automáticamente
        assertNull(engine.recordRoundStarted())
        val current = engine.getGoal()
        assertEquals(GoalPhase.BLOCKED, current?.phase)
        assertEquals("round-limit", current?.blockedReason?.code)
        assertEquals(GoalActivation.DISARMED, current?.activation)
    }

    @Test
    fun disarm_y_arm_modifican_activacion_sin_alterar_revision_durable() {
        val g = engine.createGoal("Tarea background")
        assertEquals(1, g.revision)
        assertEquals(GoalActivation.ARMED, g.activation)

        engine.disarm()
        assertEquals(GoalActivation.DISARMED, engine.getGoal()?.activation)
        assertEquals(1, engine.getGoal()?.revision)

        engine.arm()
        assertEquals(GoalActivation.ARMED, engine.getGoal()?.activation)
        assertEquals(1, engine.getGoal()?.revision)
    }
}