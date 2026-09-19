package com.codex.chat.core.swarm.engine

/**
 * Excepción de seguridad arrojada al intentar violar la profundidad máxima del enjambre.
 */
class SwarmRecursionLimitException(message: String) : SecurityException(message)

/**
 * Centinela de profundidad de recursión para subagentes del enjambre.
 * Previene bucles infinitos de delegación mutua (Fork Bombs / Infinite Subagent Chaining).
 */
object SwarmDepthSentinel {

    const val MAX_ALLOWED_DEPTH = 3

    /**
     * Audita la profundidad de la tarea antes de permitir su despacho.
     * @throws SwarmRecursionLimitException si currentDepth > MAX_ALLOWED_DEPTH
     */
    fun auditDepth(currentDepth: Int) {
        if (currentDepth > MAX_ALLOWED_DEPTH) {
            throw SwarmRecursionLimitException(
                "Violación de límite de recursión del enjambre: profundidad actual $currentDepth excede el máximo permitido de $MAX_ALLOWED_DEPTH"
            )
        }
    }
}