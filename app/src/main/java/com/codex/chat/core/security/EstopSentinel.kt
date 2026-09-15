package com.codex.chat.core.security

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class EstopEngagedException(message: String) : SecurityException(message)

data class EstopStatus(
    val isEngaged: Boolean,
    val reason: String?,
    val engagedAt: Long
)

/**
 * Centinela Global de Parada de Emergencia (ESTOP Sentinel).
 * Inspirado en la arquitectura Hermes Agent / Gastown ESTOP de DeepSeek Harness & Codex.
 * Proporciona un mecanismo a prueba de fallos (fail-safe) para pausar inmediatamente
 * cualquier ejecución de herramientas, subagentes, tareas de cron o comandos shell,
 * tenga o no permisos de root el dispositivo.
 */
object EstopSentinel {

    private const val SENTINEL_FILE_NAME = "ESTOP"
    private val memoryEngaged = AtomicBoolean(false)
    private var lastReason: String? = null
    private var lastEngagedTimestamp: Long = 0L

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        // Sincronizar estado inicial desde disco
        checkEngaged()
    }

    private fun getSentinelFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, SENTINEL_FILE_NAME)
    }

    /**
     * Comprueba si la parada de emergencia está activa.
     * Principio Fail-Safe: ante cualquier duda o error de I/O en el archivo, se considera ACTIVA.
     */
    fun isEngaged(): Boolean {
        if (memoryEngaged.get()) return true

        val file = getSentinelFile() ?: return memoryEngaged.get()
        return try {
            if (file.exists()) {
                if (!memoryEngaged.get()) {
                    readSentinelDetails(file)
                    memoryEngaged.set(true)
                }
                true
            } else {
                false
            }
        } catch (_: Throwable) {
            // Fail-safe: si hay excepción al leer el estado, bloquear por seguridad
            true
        }
    }

    fun checkEngaged(): Boolean = isEngaged()

    fun checkOrThrow() {
        if (isEngaged()) {
            val reason = lastReason ?: "Parada de emergencia manual activada"
            throw EstopEngagedException("[ESTOP ACTIVADO] Operación rechazada. Razón: $reason")
        }
    }

    /**
     * Activa la parada de emergencia global.
     */
    @Synchronized
    fun engage(reason: String = "Activación manual de seguridad") {
        lastReason = reason
        lastEngagedTimestamp = System.currentTimeMillis()
        memoryEngaged.set(true)

        val file = getSentinelFile()
        if (file != null) {
            try {
                val json = JSONObject().apply {
                    put("reason", reason)
                    put("engaged_at", lastEngagedTimestamp)
                    put("package", appContext?.packageName ?: "unknown")
                }
                file.writeText(json.toString(), Charsets.UTF_8)
            } catch (e: Throwable) {
                // Estado en memoria garantiza protección aunque falle I/O
            }
        }
    }

    /**
     * Desactiva la parada de emergencia y reanuda la operación normal.
     */
    @Synchronized
    fun disengage() {
        memoryEngaged.set(false)
        lastReason = null
        lastEngagedTimestamp = 0L

        val file = getSentinelFile()
        if (file != null && file.exists()) {
            try {
                file.delete()
            } catch (_: Throwable) {}
        }
    }

    fun getStatus(): EstopStatus {
        val engaged = isEngaged()
        return EstopStatus(
            isEngaged = engaged,
            reason = if (engaged) (lastReason ?: "Parada de emergencia activa") else null,
            engagedAt = lastEngagedTimestamp
        )
    }

    private fun readSentinelDetails(file: File) {
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            val json = JSONObject(content)
            lastReason = json.optString("reason", "Sentinel file presente en disco")
            lastEngagedTimestamp = json.optLong("engaged_at", System.currentTimeMillis())
        } catch (_: Throwable) {
            lastReason = "Sentinel file presente en disco"
            lastEngagedTimestamp = System.currentTimeMillis()
        }
    }
}
