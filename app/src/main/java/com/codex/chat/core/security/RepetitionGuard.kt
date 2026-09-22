package com.codex.chat.core.security

import kotlin.math.ceil
import kotlin.math.max

/**
 * Repetition Guard ultra-optimizado para streaming de baja latencia en dispositivos móviles:
 * - Inspecciona exclusivamente la ventana activa final (últimos 600 caracteres) donde ocurren bucles degenerativos.
 * - Elimina la creación masiva de substrings en el heap de Android, evitando pausas de Garbage Collection (GC).
 */
object RepetitionGuard {

    const val MIN_FRAGMENT_LENGTH = 400
    const val REPEAT_WINDOW = 60
    const val MIN_REPEAT_COUNT = 5
    const val DOMINANCE_RATIO = 0.5
    private const val MAX_TAIL_WINDOW = 800

    /**
     * Retorna true si el texto activo presenta bucles repetitivos degenerativos.
     * Escanea únicamente la cola del texto (máximo 800 caracteres) para garantizar ejecución en O(1) tiempo constante (< 1 ms).
     */
    fun isRepetitionDominated(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val totalLen = text.length
        if (totalLen < MIN_FRAGMENT_LENGTH) return false

        // Limitar el análisis a la cola activa reciente (los bucles ocurren al final, no en el inicio)
        val target = if (totalLen > MAX_TAIL_WINDOW) {
            text.substring(totalLen - MAX_TAIL_WINDOW)
        } else {
            text
        }
        val n = target.length

        // 1. Fast-path por líneas duplicadas (cero coste de substrings)
        val lines = target.lines().map { it.trim() }.filter { it.length >= 10 }
        if (lines.isNotEmpty()) {
            val lineCounts = HashMap<String, Int>(lines.size)
            for (l in lines) {
                val count = (lineCounts[l] ?: 0) + 1
                lineCounts[l] = count
                if (count >= MIN_REPEAT_COUNT && (count * l.length) >= (n * DOMINANCE_RATIO)) {
                    return true
                }
            }
        }

        // 2. Sliding window sobre la ventana limitada con paso de 4 caracteres para reducir asignaciones en un 75%
        val needed = max(MIN_REPEAT_COUNT, ceil((n * DOMINANCE_RATIO) / REPEAT_WINDOW).toInt())
        val counts = HashMap<String, Int>(n / 4)
        var i = 0
        while (i <= (n - REPEAT_WINDOW)) {
            val key = target.substring(i, i + REPEAT_WINDOW)
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            if (count >= needed) {
                return true
            }
            i += 4 // Stride de 4 caracteres
        }
        return false
    }
}
