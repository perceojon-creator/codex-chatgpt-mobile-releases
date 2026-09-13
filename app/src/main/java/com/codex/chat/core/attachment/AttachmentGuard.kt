package com.codex.chat.core.attachment

object AttachmentGuard {
    const val MAX_BYTES = 10L * 1024 * 1024 // 10 MB

    fun permitido(bytes: Long): Boolean = bytes in 0..MAX_BYTES

    fun motivoRechazo(bytes: Long): String? = when {
        bytes < 0         -> "Tamano de fichero desconocido."
        bytes > MAX_BYTES -> "El fichero pesa ${bytes / 1024 / 1024} MB. El maximo son ${MAX_BYTES / 1024 / 1024} MB."
        else              -> null
    }
}
