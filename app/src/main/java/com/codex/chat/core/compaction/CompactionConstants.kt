package com.codex.chat.core.compaction

/**
 * Constantes y directivas canónicas para el motor de compactación contextual
 * con paridad matemática y estructural idéntica a DeepSeek Harness (Apex).
 */
object CompactionConstants {

    /** Framing que convierte el mensaje sintetizado en contexto establecido. */
    const val CHECKPOINT_PREAMBLE =
        "This is an automatically generated checkpoint condensing an earlier span of the conversation to free up context. Treat the captured context as established background and build on it without restating it. Continue the task directly from the messages that follow, without acknowledging this checkpoint."

    /** Etiquetas XML que delimitan el sumario estructurado dentro del nodo de checkpoint. */
    const val SUMMARY_OPEN_TAG = "<compacted-summary>"
    const val SUMMARY_CLOSE_TAG = "</compacted-summary>"

    /**
     * Umbral por defecto para disparo de compactación automática: 90% de la ventana
     * de contexto propia del modelo activo.
     */
    const val DEFAULT_THRESHOLD_RATIO = 0.90

    /**
     * Proporción del contexto retenido verbatim al final de la conversación (cola reciente).
     */
    const val DEFAULT_RETAIN_RATIO = 0.15

    /** Mínimo de mensajes recientes que NUNCA deben ser compactados (el último turno). */
    const val MIN_RETAIN_MESSAGES = 2

    /** Mínimo de mensajes necesarios en el historial para justificar una compactación. */
    const val MIN_MESSAGES_TO_COMPACT = 3

    /** Modelo de sumarización ultrarrápido y de bajo coste por defecto. */
    const val DEFAULT_SUMMARIZER_MODEL = "gemini-3.5-flash-lite"

    /**
     * Directiva de compactación idéntica a DSH Apex.
     * Diseñada para ejecutarse como el ÚLTIMO mensaje de usuario tras reproducir
     * el prefijo conversacional para máxima reutilización de KV-cache.
     */
    val COMPACTION_INSTRUCTION = listOf(
        "You are now acting as a compaction engine for this AI coding assistant. Condense the conversation ABOVE into a structured checkpoint that lets another model resume the work with no loss of essential context.",
        "",
        "Output EXACTLY the Markdown structure below: keep every section, in order. Use terse bullets, not prose paragraphs. Write \"(none)\" for an empty section — never drop a section.",
        "",
        "## Primary Request and Intent",
        "- [the user's original and evolving goals; quote verbatim where the exact wording matters]",
        "",
        "## Key Technical Concepts",
        "- [technologies, frameworks, patterns, and conventions in play]",
        "",
        "## Files and Code",
        "- [exact path: why it matters, key changes or snippets]",
        "",
        "## Errors and Fixes",
        "- [error: how it was resolved, plus any related user feedback]",
        "",
        "## Pending Jobs",
        "- [explicitly requested work not yet completed]",
        "",
        "## Current Work",
        "- [precisely what was in progress at this checkpoint]",
        "",
        "## Next Step",
        "- [the single next action, directly in line with the most recent request, or \"(none)\"]",
        "",
        "## Critical Context",
        "- [decisions and their rationale, constraints, user preferences, open questions, data needed to continue]",
        "",
        "Rules:",
        "- Write concise English engineering prose. Preserve exact file paths, commands, error strings, identifiers, numeric values, function signatures, and syntax fragments.",
        "- Capture user feedback and explicit instructions faithfully, especially corrections.",
        "- Do NOT mention this summarization request or that the context was compacted.",
        "- Output only the checkpoint text: do not call any tool or take any other action.",
        "- If the conversation already contains a $SUMMARY_OPEN_TAG block, it is a PRIOR checkpoint. Do not copy it forward verbatim: preserve still-true facts, drop stale ones, and merge newer information into a single consolidated summary under the same structure."
    ).joinToString("\n")
}
