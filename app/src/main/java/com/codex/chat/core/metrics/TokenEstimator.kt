package com.codex.chat.core.metrics

import kotlin.math.ceil
import kotlin.math.max

/**
 * Estimator for token counts and generation velocity (tokens per second)
 * when upstream providers do not return exact usage statistics.
 */
object TokenEstimator {

    /**
     * Estimates the token count of a given text.
     * Uses a composite heuristic combining word counts and average character ratios
     * (~3.6 chars/token) suitable for English, Spanish, code, and punctuation.
     */
    fun estimateTokens(text: String): Int {
        if (text.isBlank()) return 0
        val trimmed = text.trim()
        val words = trimmed.split(Regex("\\s+")).filter { it.isNotEmpty() }.size
        val charTokens = ceil(trimmed.length / 3.6).toInt()
        return max(words, charTokens).coerceAtLeast(1)
    }

    /**
     * Calculates tokens per second (t/s).
     */
    fun calculateTps(tokens: Int, durationMs: Long): Double {
        if (durationMs <= 0L || tokens <= 0) return 0.0
        val durationSec = durationMs / 1000.0
        return tokens / durationSec
    }
}
