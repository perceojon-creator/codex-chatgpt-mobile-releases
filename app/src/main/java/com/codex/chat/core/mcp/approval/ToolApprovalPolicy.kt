package com.codex.chat.core.mcp.approval

object ToolApprovalPolicy {

    /**
     * Matriz de decisión básica de 3 niveles basada en el nivel de riesgo estático.
     * Mantiene retrocompatibilidad total con pruebas y lógica heredada.
     */
    fun requiresApproval(risk: ToolRiskLevel, policy: ApprovalPolicy): Boolean {
        return when (policy) {
            ApprovalPolicy.ALWAYS_ASK  -> true
            ApprovalPolicy.FULL_ACCESS -> false
            ApprovalPolicy.ASK_ON_RISK -> risk != ToolRiskLevel.SAFE
        }
    }

    /**
     * Evaluación integral de seguridad basada en el estándar CaMeL (Google DeepMind 2025/2026):
     * Inspecciona argumentos en tiempo de ejecución, rastrea contaminación externa (Taint Tracking)
     * y aplica los invariantes constitucionales de protección para los 3 niveles.
     */
    fun isInternalSandboxVerification(toolName: String): Boolean =
        toolName.equals("test_html_code", ignoreCase = true) ||
        toolName.equals("inspect_html_dom", ignoreCase = true)

    fun isMobileUseTool(toolName: String): Boolean =
        toolName.startsWith("mobile_")

    fun requiresApproval(req: ApprovalRequest, policy: ApprovalPolicy): Boolean {
        // Herramientas de Sandbox y verificación interna en memoria: ejecución autónoma segura
        if (isInternalSandboxVerification(req.toolName)) {
            return false
        }

        // Herramientas móviles supervisadas en vivo por el overlay flotante (botón STOP)
        if (isMobileUseTool(req.toolName)) {
            val inspection = ToolArgumentInspector.inspect(req.toolName, req.argumentsJson)
            if (inspection.isCriticalDanger) {
                return true
            }
            // En Nivel 2 (ASK_ON_RISK) y Nivel 3 (FULL_ACCESS): las herramientas móviles
            // se ejecutan de forma autónoma bajo supervisión visual del usuario (ChatHead + STOP).
            // Solo en Nivel 1 (ALWAYS_ASK) se requiere confirmación manual previa.
            return policy == ApprovalPolicy.ALWAYS_ASK
        }

        val inspection = ToolArgumentInspector.inspect(req.toolName, req.argumentsJson)
        val effectiveRisk = inspection.escalatedRisk ?: req.risk

        // INVARIANTE CONSTITUCIONAL 1: Detección de peligro crítico o inyección de prompt en argumentos.
        // Frena cualquier intento de salto o destrucción en cualquier nivel (incluido FULL_ACCESS).
        if (inspection.isCriticalDanger) {
            return true
        }

        // INVARIANTE CONSTITUCIONAL 2 (CaMeL Taint Tracking):
        // Si el contexto actual fue alimentado por contenido web externo no verificado (webGrounding)
        // y la herramienta es ROOT, DESTRUCTIVE o IRREVERSIBLE, se niega el paso ciego y se exige confirmación.
        if (req.isWebTainted) {
            if (effectiveRisk == ToolRiskLevel.ROOT ||
                effectiveRisk == ToolRiskLevel.DESTRUCTIVE ||
                isIrreversible(req.toolName)) {
                return true
            }
        }

        // Flujo normal bajo control del usuario: se aplica la política estándar
        return requiresApproval(effectiveRisk, policy)
    }

    /** Herramientas cuyo efecto no se puede deshacer. */
    fun isIrreversible(toolName: String): Boolean =
        toolName.lowercase().trim() in IRREVERSIBLES

    private val IRREVERSIBLES = setOf(
        "send_sms",
        "execute_root_command",
        "root_write_file",
        "root_grant_permissions",
        "root_reboot_device",
        "delete_file",
        "delete_memory"
    )
}
