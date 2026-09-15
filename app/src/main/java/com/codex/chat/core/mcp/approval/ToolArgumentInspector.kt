package com.codex.chat.core.mcp.approval

import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Resultado de la inspección profunda de argumentos para una llamada de herramienta.
 */
data class ArgumentInspectionResult(
    val isCriticalDanger: Boolean,
    val escalatedRisk: ToolRiskLevel? = null,
    val dangerReason: String? = null
)

/**
 * Motor de Inspección Profunda de Argumentos (Deep Argument Inspection).
 *
 * Aplica los principios del estándar CaMeL (Google DeepMind 2025/2026) y OWASP LLM06:
 * En lugar de evaluar únicamente el identificador estático de la herramienta,
 * inspecciona exhaustivamente los parámetros reales en tiempo de ejecución para detectar:
 * 1. Comandos de terminal/root destructivos (formateo, borrado de particiones, alteración de SELinux).
 * 2. Intentos de exfiltración de datos hacia servidores externos (HTTP / SMS).
 * 3. Rutas de almacenamiento críticas protegidas.
 * 4. Payloads maliciosos inyectados desde fuentes de internet no confiables.
 */
object ToolArgumentInspector {

    // Patrones de comandos Root/Shell de extrema peligrosidad
    private val CRITICAL_ROOT_PATTERNS = listOf(
        Pattern.compile("""(?:\brm\b|\brmdir\b)\s+(-[a-zA-Z]*r[a-zA-Z]*f[a-zA-Z]*|-[a-zA-Z]*f[a-zA-Z]*r[a-zA-Z]*)\s+([/~*]|/data|/system|/sdcard|/storage)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""mkfs(\.\w+)?\s+""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""dd\s+if=[^\s]+\s+of=/dev/(block/)?(mmcblk|sd|boot|recovery|system|userdata)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""setenforce\s+0""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""mount\s+-o\s+remount,rw\s+/(system|vendor|product)?""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""reboot\s+(recovery|bootloader|fastboot|edl)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile(""":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;\s*:""", Pattern.CASE_INSENSITIVE) // Fork bomb
    )

    // Patrones de exfiltración sospechosa en URLs salientes (http_get)
    private val EXFILTRATION_URL_PATTERNS = listOf(
        Pattern.compile("""[\?&](?:data|exfil|stolen|sms|contacts|token|secret|key|api_key|password|cookie)=([a-zA-Z0-9_\-\+/=]{16,})""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""[\?&](?:password|passwd|pwd|pass)=([^\s&]+)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""[\?&](?:msg|body|text)=.*(?:otp|2fa|codigo|código|clave|contraseña|password)""", Pattern.CASE_INSENSITIVE)
    )

    // Patrones de inyección indirecta dentro de los argumentos
    private val INJECTION_TRIGGER_PATTERNS = listOf(
        Pattern.compile("""(?:ignore|olvida)\s+(?:all\s+|todas\s+)?(?:previous\s+|anteriores\s+)?(?:instructions|instrucciones)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:system\s+(?:prompt|override)|override\s+system|modo\s+administrador)""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""\b(?:jailbreak|dan\s+mode|do\s+anything\s+now)\b""", Pattern.CASE_INSENSITIVE),
        Pattern.compile("""(?:developer\s+(?:mode|prompt)|modo\s+desarrollador)""", Pattern.CASE_INSENSITIVE)
    )

    fun inspect(toolName: String, argumentsJson: String): ArgumentInspectionResult {
        val t = toolName.lowercase().trim()
        val json = try {
            if (argumentsJson.isBlank()) JSONObject() else JSONObject(argumentsJson)
        } catch (_: Exception) {
            JSONObject()
        }

        // 1. Detección de patrones de inyección explícitos en cualquier campo de argumentos
        for (key in json.keys()) {
            val valStr = json.optString(key, "")
            for (injPattern in INJECTION_TRIGGER_PATTERNS) {
                if (injPattern.matcher(valStr).find()) {
                    return ArgumentInspectionResult(
                        isCriticalDanger = true,
                        escalatedRisk = ToolRiskLevel.ROOT,
                        dangerReason = "Se detectó un intento de inyección de prompt en el argumento '$key': posible intento de salto de seguridad."
                    )
                }
            }
        }

        // 2. Inspección de comandos ROOT
        if (t == "execute_root_command" || t.startsWith("root_")) {
            val command = json.optString("command", "") + " " + json.optString("cmd", "")
            for (pattern in CRITICAL_ROOT_PATTERNS) {
                if (pattern.matcher(command).find()) {
                    return ArgumentInspectionResult(
                        isCriticalDanger = true,
                        escalatedRisk = ToolRiskLevel.ROOT,
                        dangerReason = "Comando Root Crítico: Intenta ejecutar una instrucción potencialmente destructiva para el dispositivo ($command)."
                    )
                }
            }
        }

        // 3. Inspección de HTTP GET (Prevención de Exfiltración)
        if (t == "http_get") {
            val url = json.optString("url", "")
            for (pattern in EXFILTRATION_URL_PATTERNS) {
                if (pattern.matcher(url).find()) {
                    return ArgumentInspectionResult(
                        isCriticalDanger = true,
                        escalatedRisk = ToolRiskLevel.DESTRUCTIVE,
                        dangerReason = "Sospecha de Exfiltración: La URL de destino contiene parámetros sospechosos con datos codificados o credenciales sensibles."
                    )
                }
            }
        }

        // 4. Inspección de Telefonía y SMS (Prevención de Fraude o Fuga de OTP)
        if (t == "send_sms") {
            val phoneNumber = json.optString("phone_number", "").trim()
            val message = json.optString("message", "")

            // Números cortos de tarificación especial o servicios premium (3 a 6 dígitos no estándar)
            if (phoneNumber.length in 3..6 && phoneNumber.all { it.isDigit() } && !phoneNumber.startsWith("911") && !phoneNumber.startsWith("112")) {
                return ArgumentInspectionResult(
                    isCriticalDanger = true,
                    escalatedRisk = ToolRiskLevel.DESTRUCTIVE,
                    dangerReason = "SMS de Tarificación Especial: El número de destino '$phoneNumber' podría ser un servicio de cobro revertido o suscripción premium."
                )
            }

            // Detección de reenvío de códigos de seguridad o tokens (2FA, OTP, contraseñas bancarias)
            val sensitiveLeak = Regex("""(?i)(?:2fa|código|codigo|clave|contraseña|password|token|pin|bancari)""")
            if (sensitiveLeak.containsMatchIn(message)) {
                return ArgumentInspectionResult(
                    isCriticalDanger = true,
                    escalatedRisk = ToolRiskLevel.DESTRUCTIVE,
                    dangerReason = "Exfiltración de Credencial 2FA: El mensaje contiene posibles códigos de verificación o contraseñas bancarias/2FA."
                )
            }
        }

        // 5. Inspección de Eliminación de Archivos (delete_file)
        if (t == "delete_file") {
            val path = json.optString("file_path", "") + json.optString("path", "")
            if (path == "/" || path == "/sdcard" || path == "/storage/emulated/0" ||
                path.startsWith("/system") || path.startsWith("/data") || path.contains("..") || path.contains("/keystore")) {
                return ArgumentInspectionResult(
                    isCriticalDanger = true,
                    escalatedRisk = ToolRiskLevel.ROOT,
                    dangerReason = "Eliminación de Directorio Raíz o de Sistema: Ruta crítica detectada: $path"
                )
            }
        }

        return ArgumentInspectionResult(isCriticalDanger = false)
    }
}
