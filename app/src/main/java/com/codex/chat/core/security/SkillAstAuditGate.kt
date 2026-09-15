package com.codex.chat.core.security

import com.codex.chat.core.model.SkillInfo
import java.util.regex.Pattern

enum class SkillRiskLevel {
    SAFE,
    WARNING,
    MALICIOUS_REJECTED
}

data class SkillAuditReport(
    val isApproved: Boolean,
    val riskLevel: SkillRiskLevel,
    val violations: List<String>,
    val auditedAt: Long = System.currentTimeMillis()
)

/**
 * Compuerta de Auditoría de Seguridad AST para Habilidades y Argumentos de Herramientas.
 * Inspecciona estáticamente instrucciones de sistema, prompts y scripts antes de permitir
 * su registro o ejecución para neutralizar:
 * 1. Reverse shells y sockets no autorizados
 * 2. Tuberías remotas a shell (curl | bash, wget | sh)
 * 3. Comandos destructivos (rm -rf /, dd, mkfs, fork bombs)
 * 4. Exfiltración de secretos (claves privadas, .env, tokens, certificados)
 * 5. Escalamiento de privilegios no controlado
 */
object SkillAstAuditGate {

    private val MALICIOUS_PATTERNS = listOf(
        Pair(
            Pattern.compile("(?i)(?:curl|wget|fetch)\\s+[^|\\n]+\\s*\\|\\s*(?:ba)?sh"),
            "Ejecución remota insegura vía tubería (curl/wget | sh)"
        ),
        Pair(
            Pattern.compile("(?i)(?:nc|ncat|netcat)\\s+.*-e\\s+(?:/bin/)?(?:ba)?sh"),
            "Reverse shell interactivo detectado (netcat -e sh)"
        ),
        Pair(
            Pattern.compile("(?i)bash\\s+-i\\s+>&\\s+/dev/tcp/"),
            "Reverse shell TCP interactivo en bash"
        ),
        Pair(
            Pattern.compile("(?i)python[0-9]?\\s+-c\\s+['\"].*import\\s+(?:socket|pty|subprocess).*connect"),
            "Payload de socket interactivo o reverse shell en Python"
        ),
        Pair(
            Pattern.compile("(?i)(?:rm|rmdir)\\s+(-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*|-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*)\\s+([/~*]|/data|/system|/sdcard|/storage)"),
            "Comando de eliminación masiva destructiva (rm -rf /)"
        ),
        Pair(
            Pattern.compile("(?i):\\(\\)\\s*\\{\\s*:\\s*\\|\\s*:\\s*&\\s*\\}\\s*;\\s*:"),
            "Bomba fork detectada (:(){ :|:& };:)"
        ),
        Pair(
            Pattern.compile("(?i)(?:cat|type|more|less)\\s+.*(?:/etc/shadow|id_rsa|id_ed25519|\\.env|keystore|credentials\\.db)"),
            "Intento de lectura y exfiltración de credenciales sensibles"
        ),
        Pair(
            Pattern.compile("(?i)(?:setenforce\\s+0|mount\\s+-o\\s+remount,rw\\s+/(?:system|vendor)?)"),
            "Alteración no autorizada de políticas SELinux o remount del sistema"
        ),
        Pair(
            Pattern.compile("(?i)(?:ignore\\s+all\\s+previous\\s+instructions|olvida\\s+todas\\s+las\\s+instrucciones\\s+anteriores|jailbreak\\s+mode)"),
            "Intento de salto de directivas del sistema (Jailbreak / Prompt Injection)"
        )
    )

    private val SUSPICIOUS_PATTERNS = listOf(
        Pair(
            Pattern.compile("(?i)(?:chmod\\s+777|chmod\\s+\\+x)"),
            "Modificación permisiva de permisos ejecutables"
        ),
        Pair(
            Pattern.compile("(?i)(?:su\\s+-c|sudo\\s+-i)"),
            "Invocación de superusuario"
        )
    )

    /**
     * Audita una Habilidad completa antes de permitir su registro o ejecución.
     */
    fun auditSkill(skill: SkillInfo): SkillAuditReport {
        val violations = mutableListOf<String>()
        val combinedText = "${skill.id} ${skill.name} ${skill.description} ${skill.systemPrompt}"

        for ((pattern, description) in MALICIOUS_PATTERNS) {
            if (pattern.matcher(combinedText).find()) {
                violations.add(description)
            }
        }

        if (violations.isNotEmpty()) {
            return SkillAuditReport(
                isApproved = false,
                riskLevel = SkillRiskLevel.MALICIOUS_REJECTED,
                violations = violations
            )
        }

        val warnings = mutableListOf<String>()
        for ((pattern, description) in SUSPICIOUS_PATTERNS) {
            if (pattern.matcher(combinedText).find()) {
                warnings.add(description)
            }
        }

        if (warnings.isNotEmpty()) {
            return SkillAuditReport(
                isApproved = true,
                riskLevel = SkillRiskLevel.WARNING,
                violations = warnings
            )
        }

        return SkillAuditReport(
            isApproved = true,
            riskLevel = SkillRiskLevel.SAFE,
            violations = emptyList()
        )
    }

    /**
     * Audita un comando de shell o script individual antes de ejecución.
     */
    fun auditCommand(command: String): SkillAuditReport {
        val violations = mutableListOf<String>()
        for ((pattern, description) in MALICIOUS_PATTERNS) {
            if (pattern.matcher(command).find()) {
                violations.add(description)
            }
        }

        if (violations.isNotEmpty()) {
            return SkillAuditReport(
                isApproved = false,
                riskLevel = SkillRiskLevel.MALICIOUS_REJECTED,
                violations = violations
            )
        }

        return SkillAuditReport(
            isApproved = true,
            riskLevel = SkillRiskLevel.SAFE,
            violations = emptyList()
        )
    }
}
