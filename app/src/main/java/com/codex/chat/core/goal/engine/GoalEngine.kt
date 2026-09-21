package com.codex.chat.core.goal.engine

import com.codex.chat.core.goal.model.*
import java.util.UUID

/**
 * Motor de estado y control de concurrencia optimista (CAS) para Objetivos Autónomos.
 * Paridad con el subsistema @deepseek-ai/dsh-goal.
 */
class GoalEngine(
    private val onGoalChanged: ((GoalSnapshot?) -> Unit)? = null
) {
    private val lock = Any()
    @Volatile
    private var currentGoal: GoalSnapshot? = null

    fun createGoal(objective: String, maxRounds: Int = 1_000_000): GoalSnapshot {
        val now = System.currentTimeMillis()
        val snapshot = GoalSnapshot(
            id = "goal_" + UUID.randomUUID().toString().take(12),
            revision = 1,
            objective = objective.trim(),
            phase = GoalPhase.ACTIVE,
            activation = GoalActivation.ARMED,
            blockedReason = null,
            maxGoalRounds = maxRounds.coerceIn(1, 1_000_000),
            roundsStarted = 0,
            createdAt = now,
            updatedAt = now
        )
        synchronized(lock) {
            currentGoal = snapshot
        }
        onGoalChanged?.invoke(snapshot)
        return snapshot
    }

    fun getGoal(): GoalSnapshot? {
        return currentGoal
    }

    fun updateGoal(
        goalId: String,
        revision: Int,
        action: GoalAction,
        newObjective: String? = null,
        newMaxRounds: Int? = null,
        blockedReason: GoalBlockReason? = null
    ): GoalMutationResult {
        synchronized(lock) {
            val existing = currentGoal
                ?: return GoalMutationResult.Error("No hay ningun objetivo activo en sesion.")

            if (existing.id != goalId) {
                return GoalMutationResult.Error("Identificador de objetivo no coincide: esperado '" + existing.id + "', recibido '" + goalId + "'.")
            }

            // Control optimista de concurrencia (CAS)
            if (existing.revision != revision) {
                return GoalMutationResult.Conflict(
                    expectedRevision = revision,
                    actualRevision = existing.revision,
                    message = "Conflicto CAS: revision esperada " + revision + " pero la revision actual es " + existing.revision
                )
            }

            val now = System.currentTimeMillis()
            val nextRevision = existing.revision + 1

            val updated = when (action) {
                GoalAction.EDIT -> existing.copy(
                    revision = nextRevision,
                    objective = newObjective?.trim()?.ifBlank { null } ?: existing.objective,
                    maxGoalRounds = newMaxRounds?.coerceIn(1, 1_000_000) ?: existing.maxGoalRounds,
                    updatedAt = now
                )
                GoalAction.PAUSE -> existing.copy(
                    revision = nextRevision,
                    phase = GoalPhase.PAUSED,
                    activation = GoalActivation.DISARMED,
                    updatedAt = now
                )
                GoalAction.RESUME -> existing.copy(
                    revision = nextRevision,
                    phase = GoalPhase.ACTIVE,
                    activation = GoalActivation.ARMED,
                    updatedAt = now
                )
                GoalAction.COMPLETE -> existing.copy(
                    revision = nextRevision,
                    phase = GoalPhase.COMPLETE,
                    activation = GoalActivation.DISARMED,
                    updatedAt = now
                )
                GoalAction.BLOCKED -> existing.copy(
                    revision = nextRevision,
                    phase = GoalPhase.BLOCKED,
                    activation = GoalActivation.DISARMED,
                    blockedReason = blockedReason ?: GoalBlockReason("manual-block", "Bloqueado manualmente o por error"),
                    updatedAt = now
                )
            }

            currentGoal = updated
            onGoalChanged?.invoke(updated)
            return GoalMutationResult.Success(updated)
        }
    }

    /**
     * Incrementa el contador de rondas admitidas.
     * Si excede maxGoalRounds, transiciona automáticamente a BLOCKED (código 'round-limit').
     * Retorna el número de ronda iniciado, o null si el objetivo no puede avanzar.
     */
    fun recordRoundStarted(): Int? {
        synchronized(lock) {
            val existing = currentGoal ?: return null
            if (existing.phase != GoalPhase.ACTIVE || existing.activation != GoalActivation.ARMED) {
                return null
            }

            val nextRound = existing.roundsStarted + 1
            if (nextRound > existing.maxGoalRounds) {
                val blocked = existing.copy(
                    revision = existing.revision + 1,
                    phase = GoalPhase.BLOCKED,
                    activation = GoalActivation.DISARMED,
                    blockedReason = GoalBlockReason("round-limit", "El objetivo alcanzo el limite de " + existing.maxGoalRounds + " rondas."),
                    updatedAt = System.currentTimeMillis()
                )
                currentGoal = blocked
                onGoalChanged?.invoke(blocked)
                return null
            }

            val updated = existing.copy(
                roundsStarted = nextRound,
                updatedAt = System.currentTimeMillis()
            )
            currentGoal = updated
            onGoalChanged?.invoke(updated)
            return nextRound
        }
    }

    fun disarm() {
        synchronized(lock) {
            val g = currentGoal ?: return
            if (g.activation != GoalActivation.DISARMED) {
                currentGoal = g.copy(activation = GoalActivation.DISARMED)
                onGoalChanged?.invoke(currentGoal)
            }
        }
    }

    fun arm() {
        synchronized(lock) {
            val g = currentGoal ?: return
            if (g.activation != GoalActivation.ARMED) {
                currentGoal = g.copy(activation = GoalActivation.ARMED)
                onGoalChanged?.invoke(currentGoal)
            }
        }
    }

    fun clearGoal() {
        synchronized(lock) {
            currentGoal = null
        }
        onGoalChanged?.invoke(null)
    }

    fun restoreGoal(snapshot: GoalSnapshot?) {
        synchronized(lock) {
            currentGoal = snapshot
        }
        onGoalChanged?.invoke(snapshot)
    }
}