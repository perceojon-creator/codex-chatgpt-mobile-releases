package com.codex.chat.core.mcp

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min

enum class ToolHealthState(val value: String) {
    HEALTHY("healthy"),
    QUARANTINED("quarantined"),
    PROBATION("probation");

    companion object {
        fun from(value: String): ToolHealthState =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: HEALTHY
    }
}

data class ToolTelemetry(
    val toolName: String,
    var state: ToolHealthState = ToolHealthState.HEALTHY,
    var totalCalls: Long = 0L,
    var successCount: Long = 0L,
    var failureCount: Long = 0L,
    var consecutiveFailures: Int = 0,
    var consecutiveTimeouts: Int = 0,
    var totalDurationMs: Long = 0L,
    var minDurationMs: Long = Long.MAX_VALUE,
    var maxDurationMs: Long = 0L,
    var lastExecutedAt: Long = 0L,
    var quarantinedAt: Long? = null,
    var cooldownDurationMs: Long = 5 * 60 * 1000L, // 5 minutos por defecto
    var lastError: String? = null
) {
    val avgDurationMs: Double
        get() = if (totalCalls > 0) totalDurationMs.toDouble() / totalCalls.toDouble() else 0.0

    val remainingCooldownSec: Long
        get() {
            if (state != ToolHealthState.QUARANTINED || quarantinedAt == null) return 0L
            val elapsed = System.currentTimeMillis() - (quarantinedAt ?: 0L)
            val remaining = cooldownDurationMs - elapsed
            return max(0L, remaining / 1000L)
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("tool_name", toolName)
        put("state", state.value)
        put("total_calls", totalCalls)
        put("success_count", successCount)
        put("failure_count", failureCount)
        put("consecutive_failures", consecutiveFailures)
        put("consecutive_timeouts", consecutiveTimeouts)
        put("total_duration_ms", totalDurationMs)
        put("avg_duration_ms", String.format(java.util.Locale.US, "%.2f", avgDurationMs).toDoubleOrNull() ?: 0.0)
        put("min_duration_ms", if (minDurationMs == Long.MAX_VALUE) 0L else minDurationMs)
        put("max_duration_ms", maxDurationMs)
        put("last_executed_at", lastExecutedAt)
        put("quarantined_at", quarantinedAt ?: JSONObject.NULL)
        put("remaining_cooldown_sec", remainingCooldownSec)
        put("last_error", lastError ?: JSONObject.NULL)
    }
}

/**
 * Registro autónomo de telemetría de herramientas y ciclo de vida de autocuarentena (Auto-Quarantine).
 * Portado directamente de DeepSeek Harness (~/.dsh) y arquitectura Codex Apex.
 * Aísla automáticamente herramientas inestables o que fallan repetidamente, previniendo
 * bucles infinitos de consumo de tokens y llamadas destructivas.
 */
class ToolQuarantineRegistry(
    val maxConsecutiveFailures: Int = 5,
    val maxConsecutiveTimeouts: Int = 3,
    val defaultCooldownMs: Long = 5 * 60 * 1000L
) {

    companion object {
        private const val TAG = "ToolQuarantineRegistry"

        @Volatile
        private var instance: ToolQuarantineRegistry? = null

        fun getInstance(
            maxFailures: Int = 5,
            maxTimeouts: Int = 3,
            cooldownMs: Long = 5 * 60 * 1000L
        ): ToolQuarantineRegistry {
            return instance ?: synchronized(this) {
                instance ?: ToolQuarantineRegistry(maxFailures, maxTimeouts, cooldownMs).also { instance = it }
            }
        }
    }

    private val telemetryMap = ConcurrentHashMap<String, ToolTelemetry>()
    private val lock = Any()

    /**
     * Obtiene o crea el registro de telemetría para una herramienta dada.
     */
    fun getOrCreateTelemetry(toolName: String): ToolTelemetry {
        val key = toolName.lowercase().trim()
        return telemetryMap.computeIfAbsent(key) {
            ToolTelemetry(
                toolName = key,
                cooldownDurationMs = defaultCooldownMs
            )
        }
    }

    /**
     * Verifica si una herramienta está bajo cuarentena activa.
     * Si el periodo de enfriamiento (cooldown) ha expirado, transmuta automáticamente a PROBATION.
     */
    fun isQuarantined(toolName: String): Boolean {
        val key = toolName.lowercase().trim()
        val tel = telemetryMap[key] ?: return false

        synchronized(lock) {
            if (tel.state == ToolHealthState.QUARANTINED) {
                val quarantinedTime = tel.quarantinedAt ?: return false
                val elapsed = System.currentTimeMillis() - quarantinedTime
                if (elapsed >= tel.cooldownDurationMs) {
                    tel.state = ToolHealthState.PROBATION
                    Log.i(TAG, "Herramienta '$key' entra en PROBATION tras enfriamiento (${elapsed / 1000}s).")
                    return false
                }
                return true
            }
            return false
        }
    }

    /**
     * Valida si la herramienta puede ejecutarse según su estado de salud.
     */
    fun canExecute(toolName: String): Pair<Boolean, String?> {
        val key = toolName.lowercase().trim()
        val tel = telemetryMap[key] ?: return Pair(true, null)

        synchronized(lock) {
            if (tel.state == ToolHealthState.QUARANTINED) {
                val quarantinedTime = tel.quarantinedAt ?: 0L
                val elapsed = System.currentTimeMillis() - quarantinedTime
                if (elapsed >= tel.cooldownDurationMs) {
                    tel.state = ToolHealthState.PROBATION
                    return Pair(true, null)
                }
                val remainingSec = max(1L, (tel.cooldownDurationMs - elapsed) / 1000L)
                return Pair(
                    false,
                    "[AUTO_QUARANTINE]: Herramienta '$key' aislada preventivamente por ${tel.consecutiveFailures} fallos consecutivos. Enfriamiento activo: ${remainingSec}s restantes."
                )
            }
            return Pair(true, null)
        }
    }

    /**
     * Registra el resultado de una ejecución, actualiza la telemetría y evalúa
     * el umbral de aislamiento para autocuarentena.
     */
    fun recordExecution(
        toolName: String,
        isSuccess: Boolean,
        durationMs: Long,
        errorMessage: String? = null,
        isTimeout: Boolean = false
    ): ToolTelemetry {
        val key = toolName.lowercase().trim()
        val now = System.currentTimeMillis()

        synchronized(lock) {
            val tel = getOrCreateTelemetry(key)
            tel.totalCalls++
            tel.lastExecutedAt = now
            tel.totalDurationMs += durationMs
            tel.minDurationMs = min(tel.minDurationMs, durationMs)
            tel.maxDurationMs = max(tel.maxDurationMs, durationMs)

            if (isSuccess) {
                tel.successCount++
                tel.consecutiveFailures = 0
                tel.consecutiveTimeouts = 0
                tel.lastError = null

                if (tel.state == ToolHealthState.PROBATION) {
                    // Prueba de salud exitosa: restaurar a HEALTHY
                    tel.state = ToolHealthState.HEALTHY
                    tel.quarantinedAt = null
                    Log.i(TAG, "Herramienta '$key' restaurada exitosamente a HEALTHY tras prueba en PROBATION.")
                }
            } else {
                tel.failureCount++
                tel.consecutiveFailures++
                if (isTimeout) {
                    tel.consecutiveTimeouts++
                }
                tel.lastError = errorMessage

                if (tel.state == ToolHealthState.PROBATION) {
                    // Falló en periodo de prueba: retorno inmediato a QUARANTINED con duplicación de cooldown
                    tel.state = ToolHealthState.QUARANTINED
                    tel.quarantinedAt = now
                    tel.cooldownDurationMs = min(tel.cooldownDurationMs * 2, 60 * 60 * 1000L) // máx 1 hora
                    Log.w(TAG, "Herramienta '$key' falló durante PROBATION. Reaislada a QUARANTINED (cooldown: ${tel.cooldownDurationMs / 1000}s).")
                } else if (tel.consecutiveFailures >= maxConsecutiveFailures || tel.consecutiveTimeouts >= maxConsecutiveTimeouts) {
                    // Umbral de aislamiento alcanzado
                    tel.state = ToolHealthState.QUARANTINED
                    tel.quarantinedAt = now
                    Log.w(TAG, "CIRCUIT_BREAKER: Herramienta '$key' ha sido aislada (QUARANTINED) tras ${tel.consecutiveFailures} fallos (${tel.consecutiveTimeouts} timeouts).")
                }
            }

            return tel
        }
    }

    /**
     * Restaura manualmente una herramienta a estado HEALTHY y reinicia sus rachas.
     */
    fun resetTool(toolName: String): Boolean {
        val key = toolName.lowercase().trim()
        synchronized(lock) {
            val tel = telemetryMap[key] ?: return false
            tel.state = ToolHealthState.HEALTHY
            tel.consecutiveFailures = 0
            tel.consecutiveTimeouts = 0
            tel.quarantinedAt = null
            tel.lastError = null
            tel.cooldownDurationMs = defaultCooldownMs
            Log.i(TAG, "Herramienta '$key' restablecida manualmente a HEALTHY.")
            return true
        }
    }

    /**
     * Restablece todas las herramientas rastreadas.
     */
    fun resetAll() {
        synchronized(lock) {
            telemetryMap.clear()
        }
    }

    /**
     * Retorna una lista con la telemetría de todas las herramientas registradas.
     */
    fun getAllTelemetry(): List<ToolTelemetry> = telemetryMap.values.toList()

    /**
     * Retorna resumen JSON de telemetría completa.
     */
    fun toJsonSummary(): JSONObject {
        val root = JSONObject()
        val array = JSONArray()
        for (tel in telemetryMap.values) {
            array.put(tel.toJson())
        }
        root.put("tools", array)
        root.put("total_tracked_tools", telemetryMap.size)
        root.put("quarantined_tools_count", telemetryMap.values.count { it.state == ToolHealthState.QUARANTINED })
        return root
    }
}
