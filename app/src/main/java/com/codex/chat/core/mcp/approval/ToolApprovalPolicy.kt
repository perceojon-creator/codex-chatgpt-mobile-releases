package com.codex.chat.core.mcp.approval

object ToolApprovalPolicy {

    /**
     * Única fuente de verdad de la matriz de decisión de 3 niveles.
     * Sin dependencias de Android, sin estado, sin IO -> testeable al 100%.
     */
    fun requiresApproval(risk: ToolRiskLevel, policy: ApprovalPolicy): Boolean {
        return when (policy) {
            ApprovalPolicy.ALWAYS_ASK  -> true
            ApprovalPolicy.FULL_ACCESS -> false
            ApprovalPolicy.ASK_ON_RISK -> risk != ToolRiskLevel.SAFE
        }
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
        "delete_file"
    )
}
