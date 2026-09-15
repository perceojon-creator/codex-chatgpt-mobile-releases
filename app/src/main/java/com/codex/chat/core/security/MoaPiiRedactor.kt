package com.codex.chat.core.security

import java.util.regex.Pattern

data class AdvisoryPerspective(
    val role: String,
    val focus: String,
    val prompt: String
)

data class AdvisorySynthesis(
    val advisorCount: Int,
    val risksDetected: List<String>,
    val recommendations: List<String>,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Motor MoA (Mixture-of-Agents) de Redacción de PII y Secretos.
 * Portado desde DeepSeek Harness (~/.dsh/bin/moa.cjs) y arquitectura Hermes Agent.
 * Sanitiza automáticamente claves API, tokens, emails, contraseñas, URIs de bases de datos
 * y números de teléfono antes de que abandonen el dispositivo móvil hacia proveedores LLM externos.
 */
object MoaPiiRedactor {

    private val PRIVATE_KEY_PATTERN = Pattern.compile("-----BEGIN [A-Z ]+PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]+PRIVATE KEY-----")
    private val OPENAI_KEY_PATTERN = Pattern.compile("\\bsk-[A-Za-z0-9]{32,}\\b")
    private val GITHUB_TOKEN_PATTERN = Pattern.compile("\\bgh[pousr]_[A-Za-z0-9]{36,}\\b")
    private val BEARER_TOKEN_PATTERN = Pattern.compile("Bearer\\s+['\"]?[A-Za-z0-9_\\-\\./+=]{20,}['\"]?", Pattern.CASE_INSENSITIVE)
    private val JWT_PATTERN = Pattern.compile("\\beyJ[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_=]+\\.[A-Za-z0-9-_.+/=]*\\b")
    private val DB_URI_PATTERN = Pattern.compile("(?:postgres|postgresql|mysql|mongodb|redis|sqlite)://[^\\s'\"]+", Pattern.CASE_INSENSITIVE)
    private val EMAIL_PATTERN = Pattern.compile("\\b[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}\\b")
    private val PHONE_PATTERN = Pattern.compile("(?<![\\w.+-])(?:\\+?1[ .-])?(?:\\(\\d{3}\\)[ .-]?|\\d{3}[.-])\\d{3}[.-]\\d{4}(?![\\w-])")
    private val GENERIC_KEY_PATTERN = Pattern.compile("(?:api[_-]?key|secret|token|password|auth)\\s*[:=]\\s*['\"]?([A-Za-z0-9_\\-\\.]{16,})['\"]?", Pattern.CASE_INSENSITIVE)

    /**
     * Sanitiza el texto reemplazando información confidencial por etiquetas seguras.
     */
    fun redact(text: String?): String {
        if (text.isNullOrBlank()) return text ?: ""

        var result = text
        result = PRIVATE_KEY_PATTERN.matcher(result).replaceAll("[REDACTED PRIVATE KEY]")
        result = OPENAI_KEY_PATTERN.matcher(result).replaceAll("[REDACTED OPENAI KEY]")
        result = GITHUB_TOKEN_PATTERN.matcher(result).replaceAll("[REDACTED GITHUB TOKEN]")
        result = JWT_PATTERN.matcher(result).replaceAll("[REDACTED JWT]")
        result = DB_URI_PATTERN.matcher(result).replaceAll("[REDACTED DB URI]")
        result = BEARER_TOKEN_PATTERN.matcher(result).replaceAll("Bearer [REDACTED TOKEN]")

        // Claves genéricas por asignación
        val keyMatcher = GENERIC_KEY_PATTERN.matcher(result)
        val sb = StringBuffer()
        while (keyMatcher.find()) {
            val fullMatch = keyMatcher.group(0) ?: ""
            val secret = keyMatcher.group(1)
            if (secret != null && fullMatch.isNotEmpty()) {
                val replaced = fullMatch.replace(secret, "[REDACTED SECRET]")
                keyMatcher.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(replaced))
            }
        }
        keyMatcher.appendTail(sb)
        result = sb.toString()

        result = EMAIL_PATTERN.matcher(result).replaceAll("[redacted email]")
        result = PHONE_PATTERN.matcher(result).replaceAll("[redacted phone]")

        return result
    }

    /**
     * Genera perspectivas de revisión MoA para subagentes con PII rediseñada y segura.
     */
    fun generatePerspectives(taskDescription: String): List<AdvisoryPerspective> {
        val sanitized = redact(taskDescription)
        return listOf(
            AdvisoryPerspective(
                role = "systems_architect",
                focus = "Separación de responsabilidades, modularidad y contratos limpios.",
                prompt = "Actúa como Principal Systems Architect. Analiza la siguiente tarea sanitizada:\n\"$sanitized\"\nIdentifica riesgos arquitectónicos y contratos de interfaz."
            ),
            AdvisoryPerspective(
                role = "security_auditor",
                focus = "Vulnerabilidades, fugas de credenciales, inyecciones y principio de mínimo privilegio.",
                prompt = "Actúa como Principal Security Auditor. Audita la siguiente tarea:\n\"$sanitized\"\nIdentifica posibles vectores de inyección o fuga de secretos."
            ),
            AdvisoryPerspective(
                role = "performance_engineer",
                focus = "Latencias percentiles (p50/p90/p99), cuellos de botella y uso de memoria.",
                prompt = "Actúa como Principal Performance Engineer. Evalúa la siguiente tarea:\n\"$sanitized\"\nDiagnostica posibles problemas de escalabilidad y bloqueos."
            )
        )
    }

    /**
     * Sintetiza reportes MoA en un consenso consolidado libre de secretos.
     */
    fun synthesizeReports(reports: List<Pair<String, String>>): AdvisorySynthesis {
        val risks = mutableListOf<String>()
        val recommendations = mutableListOf<String>()

        for ((role, rawContent) in reports) {
            val content = redact(rawContent)
            val lower = content.lowercase()
            if (lower.contains("riesgo") || lower.contains("vulnerab") || lower.contains("peligro") || lower.contains("leak")) {
                risks.add("[$role] " + content.take(150) + "...")
            }
            recommendations.add("[$role] $content")
        }

        return AdvisorySynthesis(
            advisorCount = reports.size,
            risksDetected = risks,
            recommendations = recommendations
        )
    }
}
