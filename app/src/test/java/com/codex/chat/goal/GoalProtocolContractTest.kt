package com.codex.chat.goal

import com.codex.chat.core.goal.model.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Fase 0: Pruebas unitarias de los contratos de protocolo de Objetivos Autónomos (Goal Domain).
 */
class GoalProtocolContractTest {

    @Test
    fun snapshot_de_objetivo_retiene_invariantes_inmutables() {
        val now = System.currentTimeMillis()
        val snapshot = GoalSnapshot(
            id = "goal-abc-123",
            revision = 1,
            objective = "Migrar base de datos y correr tests",
            phase = GoalPhase.ACTIVE,
            activation = GoalActivation.ARMED,
            maxGoalRounds = 10,
            roundsStarted = 0,
            createdAt = now,
            updatedAt = now
        )

        assertEquals("goal-abc-123", snapshot.id)
        assertEquals(1, snapshot.revision)
        assertEquals("Migrar base de datos y correr tests", snapshot.objective)
        assertEquals(GoalPhase.ACTIVE, snapshot.phase)
        assertEquals(GoalActivation.ARMED, snapshot.activation)
        assertEquals(10, snapshot.maxGoalRounds)
        assertEquals(0, snapshot.roundsStarted)
        assertNull(snapshot.blockedReason)
    }

    @Test
    fun objetivo_bloqueado_requiere_codigo_y_mensaje_explicativo() {
        val reason = GoalBlockReason(code = "round-limit", message = "Se alcanzo el limite de 10 rondas")
        val snapshot = GoalSnapshot(
            id = "goal-blocked",
            revision = 5,
            objective = "Objetivo bloqueado",
            phase = GoalPhase.BLOCKED,
            activation = GoalActivation.DISARMED,
            blockedReason = reason,
            maxGoalRounds = 10,
            roundsStarted = 10
        )

        assertEquals(GoalPhase.BLOCKED, snapshot.phase)
        assertEquals(GoalActivation.DISARMED, snapshot.activation)
        assertNotNull(snapshot.blockedReason)
        assertEquals("round-limit", snapshot.blockedReason?.code)
        assertEquals("Se alcanzo el limite de 10 rondas", snapshot.blockedReason?.message)
    }

    @Test
    fun goal_ref_contiene_id_y_revision_para_cas() {
        val ref = GoalRef(id = "goal-cas", revision = 3)
        assertEquals("goal-cas", ref.id)
        assertEquals(3, ref.revision)
    }

    @Test
    fun goal_phases_incluye_los_cuatro_estados_canonicos() {
        val phases = GoalPhase.values().map { it.name }.toSet()
        val expected = setOf("ACTIVE", "PAUSED", "BLOCKED", "COMPLETE")
        assertEquals(expected, phases)
    }

    @Test
    fun goal_actions_incluye_los_verbos_de_mutacion_permitidos() {
        val actions = GoalAction.values().map { it.name }.toSet()
        val expected = setOf("EDIT", "PAUSE", "RESUME", "COMPLETE", "BLOCKED")
        assertEquals(expected, actions)
    }

    @Test
    fun mutation_result_distingue_exito_de_conflicto_cas() {
        val success = GoalMutationResult.Success(
            snapshot = GoalSnapshot(id = "g1", revision = 2, objective = "Obj", phase = GoalPhase.ACTIVE)
        )
        assertTrue(success is GoalMutationResult.Success)
        assertEquals(2, (success as GoalMutationResult.Success).snapshot.revision)

        val conflict = GoalMutationResult.Conflict(
            expectedRevision = 1,
            actualRevision = 2,
            message = "Revision mismatch: expected 1 but current is 2"
        )
        assertTrue(conflict is GoalMutationResult.Conflict)
        assertEquals(1, (conflict as GoalMutationResult.Conflict).expectedRevision)
        assertEquals(2, conflict.actualRevision)
    }
}