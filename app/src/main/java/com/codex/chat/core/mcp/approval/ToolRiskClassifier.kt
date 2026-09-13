package com.codex.chat.core.mcp.approval

object ToolRiskClassifier {

    private val SAFE = setOf(
        "evaluate_math", "compute_hash", "get_battery_status",
        "get_device_telemetry", "get_storage_info", "vibrate_device",
        "get_wifi_status", "get_storage_root", "dns_resolve", "ping_host",
        "check_root_status", "list_memories", "get_memory",
        "get_device_settings"
    )

    private val SENSITIVE = setOf(
        "get_device_location", "get_clipboard_text", "list_files",
        "read_file", "get_call_log", "read_sms_messages", "list_contacts",
        "list_calendar_events", "get_app_usage_stats",
        "get_captured_notifications"
    )

    private val DESTRUCTIVE = setOf(
        "set_clipboard_text", "write_file", "delete_file", "create_directory",
        "send_sms", "save_memory", "delete_memory", "create_calendar_event",
        "set_audio_volume", "set_screen_brightness", "http_get",
        "execute_python", "execute_sandbox_command"
    )

    private val ROOT = setOf(
        "execute_root_command", "root_read_file", "root_write_file",
        "root_grant_permissions", "root_reboot_device"
    )

    /**
     * PROPIEDAD DE SEGURIDAD CRÍTICA: fail-closed.
     * Una herramienta desconocida NUNCA se clasifica como SAFE. Se trata como
     * DESTRUCTIVE. Cualquier nombre que empiece por "root_" o contenga "sudo"
     * escala a ROOT aunque no esté en la lista.
     */
    fun classify(toolName: String): ToolRiskLevel {
        val n = toolName.lowercase().trim()
        return when {
            n in ROOT                        -> ToolRiskLevel.ROOT
            n.startsWith("root_")            -> ToolRiskLevel.ROOT
            n.contains("sudo")               -> ToolRiskLevel.ROOT
            n in DESTRUCTIVE                 -> ToolRiskLevel.DESTRUCTIVE
            n in SENSITIVE                   -> ToolRiskLevel.SENSITIVE
            n in SAFE                        -> ToolRiskLevel.SAFE
            else                             -> ToolRiskLevel.DESTRUCTIVE  // fail-closed
        }
    }

    /** Solo para tests y verificación: todas las herramientas conocidas. */
    fun conocidas(): Set<String> = SAFE + SENSITIVE + DESTRUCTIVE + ROOT
}
