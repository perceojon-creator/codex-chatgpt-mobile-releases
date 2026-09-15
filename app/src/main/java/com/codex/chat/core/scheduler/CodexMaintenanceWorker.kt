package com.codex.chat.core.scheduler

import android.content.Context
import android.util.Log
import com.codex.chat.core.mcp.server.MemorySqliteStore
import com.codex.chat.core.security.EstopSentinel
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Servicio en segundo plano para mantenimiento periódico del sistema Codex Mobile.
 * Ejecuta periódicamente los trabajos registrados en CodexCronScheduler:
 * - Truncamiento y checkpoint del WAL de SQLite (MemorySqliteStore)
 * - Verificación de latidos de seguridad y centinela ESTOP
 * - Sincronización y control de fugas de memoria
 */
object CodexMaintenanceWorker {

    private const val TAG = "CodexMaintenanceWorker"
    private var schedulerExecutor: ScheduledExecutorService? = null
    private val isRunning = AtomicBoolean(false)

    /**
     * Inicializa y arranca el ciclo de mantenimiento periódico en segundo plano.
     */
    fun start(context: Context, intervalMinutes: Long = 1) {
        if (isRunning.compareAndSet(false, true)) {
            val scheduler = CodexCronScheduler.getInstance(context)

            // 1. Registro de manejador para checkpoint de SQLite WAL (64 MiB auto-truncate)
            scheduler.registerTaskHandler("sqlite_wal_checkpoint") { ctx ->
                try {
                    val store = MemorySqliteStore.getInstance(ctx)
                    val checkpointSuccess = store.truncateWal()
                    val walSize = store.walStatus().optLong("wal_size_bytes", 0L)
                    Pair(checkpointSuccess, "WAL Checkpoint ejecutado. Tamaño WAL actual: $walSize bytes")
                } catch (e: Exception) {
                    Pair(false, "Error en WAL checkpoint: ${e.message}")
                }
            }

            // 2. Registro de manejador para verificación de centinela ESTOP
            scheduler.registerTaskHandler("estop_heartbeat") { _ ->
                val engaged = EstopSentinel.isEngaged()
                if (engaged) {
                    Pair(true, "ESTOP Activo: Sistema en parada de emergencia preventiva.")
                } else {
                    Pair(true, "ESTOP Despejado: Operaciones normales autorizadas.")
                }
            }

            // 3. Registrar los trabajos cron con sus intervalos respectivos
            scheduler.registerJob(
                name = "sqlite_wal_checkpoint",
                intervalSec = 1800L, // Cada 30 minutos
                description = "Checkpoint preventivo de SQLite WAL para limitar crecimiento a 64 MiB",
                maxStreak = 3
            )

            scheduler.registerJob(
                name = "estop_heartbeat",
                intervalSec = 300L, // Cada 5 minutos
                description = "Monitoreo periódico del centinela de emergencia ESTOP",
                maxStreak = 5
            )

            val executor = Executors.newSingleThreadScheduledExecutor { r ->
                Thread(r, "CodexMaintenanceThread").apply { isDaemon = true }
            }
            schedulerExecutor = executor

            executor.scheduleWithFixedDelay({
                try {
                    Log.d(TAG, "Ejecutando tick del planificador de mantenimiento...")
                    val results = scheduler.runDueJobs()
                    for (r in results) {
                        Log.i(TAG, "Cron Job '${r.jobName}': success=${r.success}, dur=${r.durationMs}ms, msg=${r.message}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error en tick de mantenimiento: ${e.message}", e)
                }
            }, 10, intervalMinutes * 60, TimeUnit.SECONDS)

            Log.i(TAG, "CodexMaintenanceWorker arrancado exitosamente (intervalo: $intervalMinutes min).")
        }
    }

    /**
     * Detiene el ejecutor periódico de mantenimiento.
     */
    fun stop() {
        if (isRunning.compareAndSet(true, false)) {
            schedulerExecutor?.shutdownNow()
            schedulerExecutor = null
            Log.i(TAG, "CodexMaintenanceWorker detenido.")
        }
    }

    /**
     * Ejecuta inmediatamente todos los trabajos pendientes de forma síncrona (para pruebas y verificación).
     */
    fun runOnceNow(context: Context): List<JobExecutionResult> {
        val scheduler = CodexCronScheduler.getInstance(context)
        return scheduler.runDueJobs()
    }

    fun isRunning(): Boolean = isRunning.get()
}
