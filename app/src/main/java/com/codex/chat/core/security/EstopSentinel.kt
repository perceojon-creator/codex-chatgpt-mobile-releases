package com.codex.chat.core.security

import android.content.Context
import android.util.Log
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

    private const val TAG = "EstopSentinel"
    private const val SENTINEL_FILE_NAME = "ESTOP"
    private val memoryEngaged = AtomicBoolean(false)
    private var lastReason: String? = null
    private var lastEngagedTimestamp: Long = 0L

    @Volatile
    private var appContext: Context? = null

    fun init(context: Context) {
        appContext = context.applicationContext
        isEngaged() // Verifica si el archivo ya existía previamente
    }

    private fun getSentinelFile(): File? {
        val ctx = appContext ?: return null
        return File(ctx.filesDir, SENTINEL_FILE_NAME)
    }

    /**
     * Verifica si el centinela ESTOP está activo, revisando tanto la memoria como el sistema de archivos.
     */
    fun isEngaged(): Boolean {
        if (memoryEngaged.get()) return true

        val file = getSentinelFile()
        return try {
            if (file != null && file.exists()) {
                if (lastReason == null) {
                    readSentinelDetails(file)
                    memoryEngaged.set(true)
                }
                true
            } else {
                false
            }
        } catch (e: Throwable) {
            // Fail-safe: si hay excepción al leer el estado, bloquear por seguridad y registrar
            Log.w(TAG, "Excepción leyendo archivo sentinel; aplicando fail-safe defensivo: ${e.message}")
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
                Log.w(TAG, "Aviso: no se pudo persistir archivo sentinel a disco: ${e.message}")
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
            } catch (e: Throwable) {
                Log.w(TAG, "Aviso: no se pudo eliminar archivo sentinel al desactivar: ${e.message}")
            }
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
        } catch (e: Throwable) {
            Log.w(TAG, "Aviso: fallo al parsear detalles de sentinel JSON: ${e.message}")
            lastReason = "Sentinel file presente en disco"
            lastEngagedTimestamp = System.currentTimeMillis()
        }
    }
}
