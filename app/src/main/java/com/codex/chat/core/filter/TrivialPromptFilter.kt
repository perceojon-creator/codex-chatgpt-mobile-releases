package com.codex.chat.core.filter

import java.util.Locale

enum class PromptNoiseType {
    VALID,
    RAPID_DUPLICATE,
    EMPTY_OR_WHITESPACE,
    PUNCTUATION_ONLY,
    REPETITIVE_CHARACTERS,
    TRIVIAL_ACKNOWLEDGMENT
}

data class FilterDecision(
    val noiseType: PromptNoiseType,
    val isBlocked: Boolean,
    val reason: String,
    val sanitizedPrompt: String,
    val instantResponse: String? = null
)

/**
 * Filtro de Ruido de Prompts Triviales (Codex / DeepSeek Harness).
 * Evita llamadas costosas al LLM causadas por pulsaciones accidentales,
 * signos de puntuación aislados, caracteres repetitivos sin sentido o envíos duplicados rápidos.
 */
object TrivialPromptFilter {

    private const val RAPID_SUBMISSION_WINDOW_MS = 1500L

    private val TRIVIAL_ACKS = mapOf(
        "ok" to "¡Entendido! ¿En qué más puedo ayudarte?",
        "vale" to "¡Perfecto! Dime qué paso sigue.",
        "k" to "Entendido. ¿Continuamos?",
        "gracias" to "¡De nada! Aquí estoy para lo que necesites.",
        "thanks" to "You're welcome! Let me know what we build next.",
        "thx" to "De nada. ¿Qué sigue en la lista?"
    )

    private val NOISE_PUNCTUATION_REGEX = Regex("^[\\s\\p{Punct}\\p{Sm}\\p{Sc}\\p{Sk}]+$")

    fun evaluate(
        prompt: String,
        lastPrompt: String? = null,
        lastTimestampMs: Long = 0L,
        currentTimestampMs: Long = System.currentTimeMillis()
    ): FilterDecision {
        val sanitized = sanitize(prompt)

        // 1. Vacío o solo espacios
        if (sanitized.isEmpty()) {
            return FilterDecision(
                noiseType = PromptNoiseType.EMPTY_OR_WHITESPACE,
                isBlocked = true,
                reason = "El mensaje está vacío o contiene solo espacios en blanco.",
                sanitizedPrompt = ""
            )
        }

        // 2. Envío duplicado rápido (rebote de botón)
        if (lastPrompt != null &&
            sanitized.equals(sanitize(lastPrompt), ignoreCase = true) &&
            (currentTimestampMs - lastTimestampMs) in 1 until RAPID_SUBMISSION_WINDOW_MS
        ) {
            return FilterDecision(
                noiseType = PromptNoiseType.RAPID_DUPLICATE,
                isBlocked = true,
                reason = "Envío duplicado descartado por ventana de seguridad de ${RAPID_SUBMISSION_WINDOW_MS}ms.",
                sanitizedPrompt = sanitized
            )
        }

        // 3. Solo signos de puntuación o símbolos (ej. ".", "?", "!!!", "...")
        if (NOISE_PUNCTUATION_REGEX.matches(sanitized)) {
            return FilterDecision(
                noiseType = PromptNoiseType.PUNCTUATION_ONLY,
                isBlocked = true,
                reason = "El mensaje contiene exclusivamente signos de puntuación o símbolos.",
                sanitizedPrompt = sanitized
            )
        }

        // 4. Caracteres idénticos repetitivos sin sentido (ej. "aaaaaaa", "zzzzz", "?????")
        if (sanitized.length >= 4 && sanitized.all { it == sanitized[0] }) {
            return FilterDecision(
                noiseType = PromptNoiseType.REPETITIVE_CHARACTERS,
                isBlocked = true,
                reason = "Secuencia de caracteres repetitivos sin valor semántico.",
                sanitizedPrompt = sanitized
            )
        }

        // 5. Agradecimiento o acuse de recibo trivial de una palabra
        val lower = sanitized.lowercase(Locale.ROOT)
        val instant = TRIVIAL_ACKS[lower]
        if (instant != null) {
            return FilterDecision(
                noiseType = PromptNoiseType.TRIVIAL_ACKNOWLEDGMENT,
                isBlocked = false, // No se bloquea, pero proporciona respuesta instantánea opcional
                reason = "Acuse de recibo trivial reconocido.",
                sanitizedPrompt = sanitized,
                instantResponse = instant
            )
        }

        return FilterDecision(
            noiseType = PromptNoiseType.VALID,
            isBlocked = false,
            reason = "Prompt válido para inferencia.",
            sanitizedPrompt = sanitized
        )
    }

    /**
     * Sanitiza el prompt eliminando caracteres de control no imprimibles y normalizando espacios.
     */
    fun sanitize(text: String): String {
        return text
            .replace(Regex("[\\u200B-\\u200D\\uFEFF]"), "") // zero-width spaces
            .replace(Regex("[\\r\\t]+"), " ")
            .trim()
    }
}
