package com.codex.chat.core.goal.model

import org.json.JSONObject
import java.util.UUID

/**
 * Fases del ciclo de vida duradero de un objetivo en sesión.
 * Paridad con @deepseek-ai/dsh-goal GoalPhase.
 */
enum class GoalPhase {
    ACTIVE,
    PAUSED,
    BLOCKED,
    COMPLETE
}

/**
 * Estado de activación del proceso local.
 * ARMED: el motor puede continuar automáticamente hacia la siguiente ronda.
 * DISARMED: el motor no continuará sin intervención explícita.
 */
enum class GoalActivation {
    ARMED,
    DISARMED
}

/**
 * Acciones de mutación admitidas sobre un objetivo.
 */
enum class GoalAction {
    EDIT,
    PAUSE,
    RESUME,
    COMPLETE,
    BLOCKED
}

/**
 * Referencia canónica para control optimista de concurrencia (CAS).
 */
data class GoalRef(
    val id: String,
    val revision: Int
)

/**
 * Razón de bloqueo estructurada para el objetivo.
 */
data class GoalBlockReason(
    val code: String,
    val message: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
    }
    companion object {
        fun fromJson(json: JSONObject): GoalBlockReason = GoalBlockReason(
            code = json.optString("code", "unknown"),
            message = json.optString("message", "")
        )
    }
}

/**
 * Estado duradero completo de un objetivo en sesión.
 */
data class GoalSnapshot(
    val id: String = UUID.randomUUID().toString(),
    val revision: Int = 1,
    val objective: String,
    val phase: GoalPhase = GoalPhase.ACTIVE,
    val activation: GoalActivation = GoalActivation.ARMED,
    val blockedReason: GoalBlockReason? = null,
    val maxGoalRounds: Int = 1_000_000,
    val roundsStarted: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis()
) {
    fun toRef(): GoalRef = GoalRef(id = id, revision = revision)

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("revision", revision)
        put("objective", objective)
        put("phase", phase.name.lowercase())
        put("activation", activation.name.lowercase())
        put("maxGoalRounds", maxGoalRounds)
        put("roundsStarted", roundsStarted)
        put("createdAt", createdAt)
        put("updatedAt", updatedAt)
        blockedReason?.let { put("blockedReason", it.toJson()) }
    }

    companion object {
        fun fromJson(json: JSONObject): GoalSnapshot {
            val phaseStr = json.optString("phase", "active").uppercase()
            val actStr = json.optString("activation", "armed").uppercase()
            val phase = try { GoalPhase.valueOf(phaseStr) } catch (_: Exception) { GoalPhase.ACTIVE }
            val act = try { GoalActivation.valueOf(actStr) } catch (_: Exception) { GoalActivation.ARMED }
            val blockedObj = json.optJSONObject("blockedReason")

            return GoalSnapshot(
                id = json.optString("id", UUID.randomUUID().toString()),
                revision = json.optInt("revision", 1),
                objective = json.optString("objective", ""),
                phase = phase,
                activation = act,
                blockedReason = blockedObj?.let { GoalBlockReason.fromJson(it) },
                maxGoalRounds = json.optInt("maxGoalRounds", 1_000_000),
                roundsStarted = json.optInt("roundsStarted", 0),
                createdAt = json.optLong("createdAt", System.currentTimeMillis()),
                updatedAt = json.optLong("updatedAt", System.currentTimeMillis())
            )
        }
    }
}

/**
 * Resultado de una mutación con compare-and-set (CAS).
 */
sealed class GoalMutationResult {
    data class Success(val snapshot: GoalSnapshot) : GoalMutationResult()
    data class Conflict(val expectedRevision: Int, val actualRevision: Int, val message: String) : GoalMutationResult()
    data class Error(val message: String) : GoalMutationResult()
}