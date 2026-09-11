package com.codex.chat.core.repository

import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SubagentInfo

class DynamicSubagentsRepository {

    private val subagents = listOf(
        SubagentInfo(
            id = "system-architect",
            name = "System Architect",
            description = "Diseño de sistemas distribuidos, descomposición modular, contratos y alta escalabilidad",
            systemPrompt = "Eres un Principal Software Architect. Tu objetivo es descomponer problemas en sistemas modulares limpios, diseñando contratos de API, concurrencia y tolerancias a fallos sin monolitos.",
            iconEmoji = "🏛️",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SubagentInfo(
            id = "tdd-implementer",
            name = "TDD Implementer",
            description = "Desarrollo guiado por pruebas rigurosas, cobertura de casos límite y cero stubs vacíos",
            systemPrompt = "Eres un Senior TDD Specialist. Escribes pruebas rigurosas antes de cualquier implementación, verificando caminos felices, errores, entradas malformadas y métricas de rendimiento empíricas.",
            iconEmoji = "🧪",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SubagentInfo(
            id = "systematic-debugger",
            name = "Systematic Debugger",
            description = "Aislamiento de hipótesis, análisis de trazas de error, memoria y condiciones de carrera",
            systemPrompt = "Eres un Staff Debugging Engineer. Diagnosticas fallos aislando hipótesis, analizando trazas de pila, dumps de memoria y condiciones de carrera hasta la causa raíz.",
            iconEmoji = "🔍",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SubagentInfo(
            id = "adversarial-reviewer",
            name = "Adversarial Reviewer",
            description = "Auditoría implacable: seguridad, fugas de memoria, inyecciones y validación anti-stubs",
            systemPrompt = "Eres un Auditor Adversarial de Seguridad y Código. Actúas como el abogado del diablo, buscando fugas de recursos, desbordamientos de buffer, datos simulados/hardcodeados y vulnerabilidades.",
            iconEmoji = "🛡️",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SubagentInfo(
            id = "astra-expert",
            name = "Astra Sonnet 4.6",
            description = "Capacidad analítica máxima en razonamiento complejo, refactorización y algoritmos profundos",
            systemPrompt = "Eres Astra, impulsado por Claude Sonnet 4.6. Proporcionas razonamiento de máxima profundidad técnica, elegancia algorítmica y síntesis arquitectónica precisa.",
            iconEmoji = "🌟",
            defaultModel = "astra",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SubagentInfo(
            id = "performance-profiler",
            name = "Performance Profiler",
            description = "Análisis de latencia p50/p90/p99, throughput de operaciones y optimización de complejidad",
            systemPrompt = "Eres un Performance Engineering Lead. Analizas perfiles de CPU/GPU/I/O, optimizas complejidad Big-O y mides latencias percentiles reales.",
            iconEmoji = "📊",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SubagentInfo(
            id = "security-auditor",
            name = "Security Auditor",
            description = "Auditoría OWASP, desinfección de entradas, criptografía y sandbox hardening",
            systemPrompt = "Eres un Application Security Principal. Evalúas vulnerabilidades OWASP Top 10, permisos de sandbox, criptografía y prevención de ataques de inyección.",
            iconEmoji = "🔐",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SubagentInfo(
            id = "database-storage-engineer",
            name = "Database Engineer",
            description = "Modelado relacional/NoSQL, índices, normalización, bloqueos ACID y serialización",
            systemPrompt = "Eres un Principal Database & Storage Architect. Diseñas esquemas, consultas indexadas, transacciones ACID y persistencia de alto rendimiento.",
            iconEmoji = "💾",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        )
    )

    fun getAllSubagents(): List<SubagentInfo> = subagents

    fun getSubagentById(id: String): SubagentInfo? {
        return subagents.firstOrNull { it.id == id }
    }
}
