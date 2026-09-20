package com.codex.chat.core.goal.ui

import com.codex.chat.core.goal.model.GoalActivation
import com.codex.chat.core.goal.model.GoalPhase
import com.codex.chat.core.goal.model.GoalSnapshot

/**
 * Proyección visual reactiva inmutable del objetivo para el GoalBar de la interfaz.
 */
data class GoalUiState(
    val isVisible: Boolean,
    val objective: String,
    val roundBadge: String,
    val progressPct: Int,
    val isPaused: Boolean,
    val isArmed: Boolean,
    val statusSummary: String
) {
    companion object {
        fun fromSnapshot(snapshot: GoalSnapshot?): GoalUiState {
            if (snapshot == null || snapshot.phase == GoalPhase.COMPLETE) {
                return GoalUiState(
                    isVisible = false,
                    objective = "",
                    roundBadge = "",
                    progressPct = 0,
                    isPaused = false,
                    isArmed = false,
                    statusSummary = ""
                )
            }

            val total = snapshot.maxGoalRounds.coerceAtLeast(1)
            val current = snapshot.roundsStarted.coerceIn(0, total)
            val pct = (current * 100) / total
            val isPaused = snapshot.phase == GoalPhase.PAUSED
            val isArmed = snapshot.activation == GoalActivation.ARMED

            val badge = when (snapshot.phase) {
                GoalPhase.ACTIVE -> "🎯 Ronda $current/$total"
                GoalPhase.PAUSED -> "⏸️ Pausado ($current/$total)"
                GoalPhase.BLOCKED -> "⚠️ Bloqueado: " + (snapshot.blockedReason?.code ?: "error")
                GoalPhase.COMPLETE -> "✅ Completado"
            }

            val summary = when {
                snapshot.phase == GoalPhase.BLOCKED -> snapshot.blockedReason?.message ?: "Objetivo bloqueado"
                isPaused -> "Objetivo en pausa. Pulsa Reanudar para continuar."
                isArmed -> "En ejecución autónoma..."
                else -> "Desarmado"
            }

            return GoalUiState(
                isVisible = true,
                objective = snapshot.objective,
                roundBadge = badge,
                progressPct = pct,
                isPaused = isPaused,
                isArmed = isArmed,
                statusSummary = summary
            )
        }
    }
}