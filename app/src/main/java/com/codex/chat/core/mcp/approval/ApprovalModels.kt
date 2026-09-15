package com.codex.chat.core.mcp.approval

/** Nivel de riesgo intrínseco de una herramienta. No depende de la política. */
enum class ToolRiskLevel {
    SAFE,         // lectura inocua: sin datos personales, sin efectos laterales
    SENSITIVE,    // lee datos personales del usuario o del dispositivo
    DESTRUCTIVE,  // escribe, envía, borra, o puede exfiltrar datos
    ROOT          // requiere privilegio de superusuario
}

/** Política elegida por el usuario. Los 3 niveles de protección. */
enum class ApprovalPolicy(val nivel: Int, val etiqueta: String) {
    ALWAYS_ASK(1, "Solicitar aprobacion"),
    ASK_ON_RISK(2, "Preguntar por mi"),
    FULL_ACCESS(3, "Acceso completo");

    companion object {
        fun fromNivel(n: Int): ApprovalPolicy =
            values().firstOrNull { it.nivel == n } ?: ALWAYS_ASK // fail-closed
    }
}

/** Resultado de pedir permiso. */
enum class ApprovalDecision {
    APPROVED,          // permitir solo esta vez
    APPROVED_SESSION,  // permitir y recordar hasta cerrar la app
    DENIED,            // el usuario dijo que no
    TIMEOUT            // no respondió a tiempo -> se trata como DENIED
}

data class ApprovalRequest(
    val toolName: String,
    val argumentsJson: String,
    val risk: ToolRiskLevel,
    val serverName: String,
    val isWebTainted: Boolean = false,
    val dangerReason: String? = null
)
