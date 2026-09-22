package com.codex.chat.core.security

import kotlin.math.ceil
import kotlin.math.max

/**
 * Repetition Guard (Hermes & Apex parity - agent/repetition_guard.py):
 * Detects whether a model response has entered a degenerative repetition loop during streaming.
 */
object RepetitionGuard {

    const val MIN_FRAGMENT_LENGTH = 400
    const val REPEAT_WINDOW = 60
    const val MIN_REPEAT_COUNT = 5
    const val DOMINANCE_RATIO = 0.5

    /**
     * Returns true if the text is dominated by repetitive loops (> 50% dominance of repeated 60-char window
     * or repeated line fast-path).
     */
    fun isRepetitionDominated(text: String?): Boolean {
        if (text.isNullOrEmpty()) return false
        val n = text.length
        if (n < MIN_FRAGMENT_LENGTH) return false

        // Fast path: normalized line duplicated enough to cover >= 50% of the fragment
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val lineCounts = mutableMapOf<String, Int>()
        for (l in lines) {
            val count = (lineCounts[l] ?: 0) + 1
            lineCounts[l] = count
            if (count >= MIN_REPEAT_COUNT && (count * l.length) >= (n * DOMINANCE_RATIO)) {
                return true
            }
        }

        // General sliding window path: 60-char window
        val needed = max(MIN_REPEAT_COUNT, ceil((n * DOMINANCE_RATIO) / REPEAT_WINDOW).toInt())
        val counts = mutableMapOf<String, Int>()
        for (i in 0..(n - REPEAT_WINDOW)) {
            val key = text.substring(i, i + REPEAT_WINDOW)
            val count = (counts[key] ?: 0) + 1
            counts[key] = count
            if (count >= needed) {
                return true
            }
        }
        return false
    }
}
