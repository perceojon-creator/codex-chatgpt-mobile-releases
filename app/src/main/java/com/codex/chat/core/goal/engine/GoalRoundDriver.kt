package com.codex.chat.core.goal.engine

import com.codex.chat.core.goal.model.GoalActivation
import com.codex.chat.core.goal.model.GoalPhase
import com.codex.chat.core.goal.model.GoalSnapshot

/**
 * Conductor autónomo de rondas de objetivos dentro de la misma sesión.
 * Paridad con @deepseek-ai/dsh-goal-round-driver.
 */
class GoalRoundDriver {

    /**
     * Genera el prompt canónico de continuación de ronda preservado en el historial.
     */
    fun renderGoalRoundPrompt(goal: GoalSnapshot, round: Int): String {
        val escapedObjective = goal.objective.replace("\"", "\\\"")
        return "<goal_round>\n" +
            "Objective: \"" + escapedObjective + "\"\n" +
            "Round: " + round + "/" + goal.maxGoalRounds + "\n\n" +
            "Continue working toward the objective in this same session. Treat the current workspace, " +
            "tool results, and durable session state as authoritative; inspect them instead of assuming " +
            "earlier narration is still current. Make concrete progress and verify the result. Before " +
            "claiming completion, gather evidence that the whole objective is achieved, read the current " +
            "goal, and mark it complete. If work remains, leave the goal active for the next round. Follow " +
            "the configured goal-tool policy before reporting a blocker.\n" +
            "</goal_round>"
    }

    /**
     * Evalúa si el objetivo actual debe continuar automáticamente hacia una nueva ronda.
     */
    fun shouldDrive(goal: GoalSnapshot?): Boolean {
        if (goal == null) return false
        return goal.phase == GoalPhase.ACTIVE &&
               goal.activation == GoalActivation.ARMED &&
               goal.roundsStarted < goal.maxGoalRounds
    }

    /**
     * Avanza la ronda de forma atómica en el motor y retorna el prompt listo para inyección.
     * Retorna null si el objetivo no debe o no puede continuar.
     */
    fun nextRoundPrompt(engine: GoalEngine): String? {
        val current = engine.getGoal() ?: return null
        if (!shouldDrive(current)) return null

        val nextRound = engine.recordRoundStarted() ?: return null
        return renderGoalRoundPrompt(current, nextRound)
    }

    /**
     * Desarma el conductor ante la llegada de una entrada manual del usuario.
     * Esto asegura que el humano siempre tiene prioridad absoluta sobre la cola autónoma.
     */
    fun onHumanInterruption(engine: GoalEngine) {
        engine.disarm()
    }
}