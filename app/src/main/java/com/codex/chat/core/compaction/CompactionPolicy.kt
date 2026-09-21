package com.codex.chat.core.compaction

data class CompactionConfig(
    val thresholdRatio: Double = CompactionConstants.DEFAULT_THRESHOLD_RATIO,
    val retainRatio: Double = CompactionConstants.DEFAULT_RETAIN_RATIO,
    val minRetainMessages: Int = CompactionConstants.MIN_RETAIN_MESSAGES,
    val minMessagesToCompact: Int = CompactionConstants.MIN_MESSAGES_TO_COMPACT,
    val autoCompactEnabled: Boolean = true
) {
    init {
        require(thresholdRatio in 0.1..1.0) { "thresholdRatio debe estar entre 0.1 y 1.0" }
        require(retainRatio in 0.01..thresholdRatio) { "retainRatio debe ser menor que thresholdRatio" }
    }
}

data class CompactionDecision(
    val shouldCompact: Boolean,
    val reason: String,
    val currentTokens: Int,
    val thresholdTokens: Int,
    val contextWindow: Int,
    val percentUsed: Double
)

object CompactionPolicy {

    fun resolveThresholdTokens(contextWindow: Int, thresholdRatio: Double = CompactionConstants.DEFAULT_THRESHOLD_RATIO): Int {
        if (contextWindow <= 0) return 0
        return (contextWindow * thresholdRatio).toInt().coerceAtLeast(1)
    }

    fun resolveRetainTokens(contextWindow: Int, retainRatio: Double = CompactionConstants.DEFAULT_RETAIN_RATIO): Int {
        if (contextWindow <= 0) return 0
        return (contextWindow * retainRatio).toInt().coerceAtLeast(1)
    }

    fun evaluate(
        currentTokens: Int,
        contextWindow: Int,
        messageCount: Int,
        config: CompactionConfig = CompactionConfig()
    ): CompactionDecision {
        if (!config.autoCompactEnabled) {
            return CompactionDecision(
                shouldCompact = false,
                reason = "Compactación automática deshabilitada por configuración",
                currentTokens = currentTokens,
                thresholdTokens = resolveThresholdTokens(contextWindow, config.thresholdRatio),
                contextWindow = contextWindow,
                percentUsed = if (contextWindow > 0) (currentTokens.toDouble() / contextWindow) * 100.0 else 0.0
            )
        }

        if (contextWindow <= 0) {
            return CompactionDecision(
                shouldCompact = false,
                reason = "Ventana de contexto no válida ($contextWindow)",
                currentTokens = currentTokens,
                thresholdTokens = 0,
                contextWindow = contextWindow,
                percentUsed = 0.0
            )
        }

        if (messageCount < config.minMessagesToCompact) {
            return CompactionDecision(
                shouldCompact = false,
                reason = "Conversación demasiado corta ($messageCount mensajes, mínimo " + config.minMessagesToCompact + ")",
                currentTokens = currentTokens,
                thresholdTokens = resolveThresholdTokens(contextWindow, config.thresholdRatio),
                contextWindow = contextWindow,
                percentUsed = (currentTokens.toDouble() / contextWindow) * 100.0
            )
        }

        val thresholdTokens = resolveThresholdTokens(contextWindow, config.thresholdRatio)
        val percentUsed = (currentTokens.toDouble() / contextWindow.toDouble()) * 100.0
        val shouldCompact = currentTokens >= thresholdTokens

        val reason = if (shouldCompact) {
            "Consumo de tokens (" + currentTokens + ") superó el " + (config.thresholdRatio * 100).toInt() + "% de la ventana (" + thresholdTokens + " / " + contextWindow + " tokens)"
        } else {
            "Consumo de tokens (" + currentTokens + ") por debajo del umbral de compactación (" + thresholdTokens + " tokens)"
        }

        return CompactionDecision(
            shouldCompact = shouldCompact,
            reason = reason,
            currentTokens = currentTokens,
            thresholdTokens = thresholdTokens,
            contextWindow = contextWindow,
            percentUsed = percentUsed
        )
    }
}