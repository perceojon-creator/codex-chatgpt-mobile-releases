package com.codex.chat.core.metrics

import java.util.Locale

/**
 * Model for performance and token consumption metrics of an LLM generation.
 * Decouples initial thinking/TTFT duration from active token generation duration
 * so models with heavy reasoning (e.g. Gemini 3.8 / o1) reflect accurate tokens/sec.
 */
data class StreamMetrics(
    val durationMs: Long = 0L,
    val thinkingDurationMs: Long = 0L,
    val generationDurationMs: Long = 0L,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val totalTokens: Int = 0,
    val tokensPerSecond: Double = 0.0
) {
    val hasMetrics: Boolean
        get() = durationMs > 0L || completionTokens > 0 || tokensPerSecond > 0.0

    fun formatTps(): String = String.format(Locale.US, "%.1f t/s", tokensPerSecond)

    fun formatTokens(): String {
        return if (completionTokens > 0) {
            "$completionTokens tokens"
        } else if (totalTokens > 0) {
            "$totalTokens tokens"
        } else {
            ""
        }
    }

    fun formatDuration(): String {
        return if (durationMs >= 1000L) {
            String.format(Locale.US, "%.2fs", durationMs / 1000.0)
        } else if (durationMs > 0L) {
            "${durationMs}ms"
        } else {
            ""
        }
    }

    fun formatThinkingDuration(): String {
        return if (thinkingDurationMs >= 1000L) {
            String.format(Locale.US, "%.1fs", thinkingDurationMs / 1000.0)
        } else if (thinkingDurationMs > 0L) {
            "${thinkingDurationMs}ms"
        } else {
            ""
        }
    }

    fun formatSummary(): String {
        val parts = mutableListOf<String>()
        if (tokensPerSecond > 0.0) parts.add("⚡ " + formatTps())
        if (thinkingDurationMs >= 1000L) parts.add("💭 " + formatThinkingDuration())
        if (completionTokens > 0 || totalTokens > 0) parts.add(formatTokens())
        if (durationMs > 0L) parts.add(formatDuration())
        return parts.joinToString(" · ")
    }

    fun formatDetailedTooltip(): String {
        val sb = StringBuilder()
        if (tokensPerSecond > 0.0) sb.append("⚡ Velocidad generación: ").append(formatTps()).append("\n")
        if (thinkingDurationMs > 0L) sb.append("💭 Tiempo razonamiento: ").append(formatThinkingDuration()).append("\n")
        if (generationDurationMs > 0L) sb.append("✍️ Tiempo emisión: ").append(String.format(Locale.US, "%.2fs", generationDurationMs / 1000.0)).append("\n")
        if (completionTokens > 0) sb.append("📊 Tokens salida: ").append(completionTokens).append("\n")
        if (promptTokens > 0) sb.append("📥 Tokens entrada: ").append(promptTokens).append("\n")
        if (totalTokens > 0) sb.append("🔢 Tokens totales: ").append(totalTokens).append("\n")
        if (durationMs > 0L) sb.append("⏱️ Tiempo total: ").append(formatDuration())
        return sb.toString().trim()
    }
}
