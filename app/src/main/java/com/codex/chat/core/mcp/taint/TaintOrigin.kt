package com.codex.chat.core.mcp.taint

/**
 * Origen que puede contaminar una sesion con datos potencialmente adversariales.
 * Auditoria v1.0.79 SEC-3.
 */
enum class TaintOrigin {
    WEB_SEARCH,
    SMS_READ,
    NOTIFICATION_READ,
    CALL_LOG_READ,
    CLIPBOARD_READ
}
