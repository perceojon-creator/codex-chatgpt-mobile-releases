package com.codex.chat.core.scheduler

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

enum class JobState(val value: String) {
    ENABLED("enabled"),
    DISABLED("disabled"),
    DISABLED_STREAK("disabled_streak");

    companion object {
        fun from(value: String): JobState =
            entries.find { it.value.equals(value, ignoreCase = true) } ?: ENABLED
    }
}

data class ScheduledJob(
    val id: Long,
    val name: String,
    val description: String,
    val command: String,
    val intervalSec: Long,
    val lastRunAt: Long?,
    val nextRunAt: Long,
    val failureStreak: Int,
    val maxStreak: Int,
    val state: JobState,
    val lastResult: String?,
    val createdAt: Long
)

data class JobExecutionResult(
    val jobName: String,
    val success: Boolean,
    val durationMs: Long,
    val message: String,
    val circuitBreakerTripped: Boolean
)

/**
 * Planificador Cron autónomo con cerrojos de concurrencia y Circuit Breaker de racha de fallos.
 * Portado directamente de DeepSeek Harness (~/.dsh/bin/cron.cjs) y arquitectura Hermes Agent.
 */
class CodexCronScheduler private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CodexCronScheduler"
        private const val DB_NAME = "cron_scheduler.sqlite"
        private const val DB_VERSION = 1
        private const val LOCK_FILE_NAME = ".scheduler_tick.lock"
        private const val STALE_LOCK_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutos

        @Volatile
        private var instance: CodexCronScheduler? = null

        fun getInstance(context: Context): CodexCronScheduler {
            return instance ?: synchronized(this) {
                instance ?: CodexCronScheduler(context.applicationContext).also { instance = it }
            }
        }
    }

    private val dbHelper = CronDbHelper(context)
    private val memoryLock = ReentrantLock()
    private val lockFile by lazy { File(context.filesDir, LOCK_FILE_NAME) }
    private val taskRegistry = ConcurrentHashMap<String, (Context) -> Pair<Boolean, String>>()

    private class CronDbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("""
                CREATE TABLE IF NOT EXISTS scheduled_jobs (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    name TEXT UNIQUE NOT NULL,
                    description TEXT,
                    command TEXT NOT NULL,
                    interval_sec INTEGER NOT NULL DEFAULT 3600,
                    last_run_at INTEGER,
                    next_run_at INTEGER NOT NULL,
                    failure_streak INTEGER DEFAULT 0,
                    max_streak INTEGER DEFAULT 3,
                    state TEXT DEFAULT 'enabled',
                    last_result TEXT,
                    created_at INTEGER NOT NULL
                );
            """.trimIndent())

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS job_run_history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    job_name TEXT NOT NULL,
                    started_at INTEGER NOT NULL,
                    finished_at INTEGER NOT NULL,
                    duration_ms INTEGER NOT NULL,
                    exit_code INTEGER NOT NULL,
                    output TEXT,
                    status TEXT NOT NULL
                );
            """.trimIndent())

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_state ON scheduled_jobs(state);")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_jobs_next_run ON scheduled_jobs(next_run_at);")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Migraciones futuras
        }
    }

    /**
     * Registra una función ejecutable asociada al nombre del trabajo.
     */
    fun registerTaskHandler(name: String, handler: (Context) -> Pair<Boolean, String>) {
        taskRegistry[name] = handler
    }

    /**
     * Adquiere un cerrojo atómico con expiración para evitar ejecuciones concurrentes.
     */
    fun acquireLock(): Boolean {
        if (!memoryLock.tryLock()) return false
        return try {
            if (lockFile.exists()) {
                val ageMs = System.currentTimeMillis() - lockFile.lastModified()
                if (ageMs > STALE_LOCK_TIMEOUT_MS) {
                    lockFile.delete()
                } else {
                    memoryLock.unlock()
                    return false
                }
            }
            lockFile.writeText("{\"pid\": ${android.os.Process.myPid()}, \"time\": ${System.currentTimeMillis()}}")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Error adquiriendo lock: ${e.message}")
            if (memoryLock.isHeldByCurrentThread) memoryLock.unlock()
            false
        }
    }

    /**
     * Libera el cerrojo atómico del planificador.
     */
    fun releaseLock() {
        try {
            if (lockFile.exists()) {
                lockFile.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error eliminando lock file: ${e.message}")
        } finally {
            if (memoryLock.isHeldByCurrentThread) {
                memoryLock.unlock()
            }
        }
    }

    /**
     * Registra o actualiza un trabajo cron programado.
     */
    fun registerJob(
        name: String,
        intervalSec: Long,
        description: String = "",
        command: String = name,
        maxStreak: Int = 3
    ): Boolean {
        val db = dbHelper.writableDatabase
        val now = System.currentTimeMillis()
        val values = ContentValues().apply {
            put("name", name)
            put("description", description)
            put("command", command)
            put("interval_sec", intervalSec)
            put("next_run_at", now)
            put("failure_streak", 0)
            put("max_streak", maxStreak)
            put("state", JobState.ENABLED.value)
            put("created_at", now)
        }

        val rows = db.insertWithOnConflict(
            "scheduled_jobs",
            null,
            values,
            SQLiteDatabase.CONFLICT_IGNORE
        )

        return if (rows == -1L) {
            // Ya existía: actualizar parámetros
            val updateValues = ContentValues().apply {
                put("description", description)
                put("command", command)
                put("interval_sec", intervalSec)
                put("max_streak", maxStreak)
            }
            db.update("scheduled_jobs", updateValues, "name = ?", arrayOf(name)) > 0
        } else {
            true
        }
    }

    /**
     * Ejecuta todos los trabajos cuyo vencimiento ha llegado.
     * Incorpora Circuit Breaker: si la racha de fallos alcanza maxStreak,
     * el estado transmuta automáticamente a 'disabled_streak'.
     */
    fun runDueJobs(): List<JobExecutionResult> {
        if (!acquireLock()) {
            Log.d(TAG, "No se pudo adquirir el lock de ejecución cron (otro hilo/proceso activo).")
            return emptyList()
        }

        val results = mutableListOf<JobExecutionResult>()
        try {
            val db = dbHelper.writableDatabase
            val now = System.currentTimeMillis()

            val cursor = db.rawQuery(
                """
                SELECT id, name, description, command, interval_sec, last_run_at, next_run_at,
                       failure_streak, max_streak, state, last_result, created_at
                FROM scheduled_jobs
                WHERE state = ? AND next_run_at <= ?
                ORDER BY next_run_at ASC
                """.trimIndent(),
                arrayOf(JobState.ENABLED.value, now.toString())
            )

            val dueJobs = mutableListOf<ScheduledJob>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    dueJobs.add(
                        ScheduledJob(
                            id = c.getLong(0),
                            name = c.getString(1),
                            description = c.getString(2) ?: "",
                            command = c.getString(3),
                            intervalSec = c.getLong(4),
                            lastRunAt = if (c.isNull(5)) null else c.getLong(5),
                            nextRunAt = c.getLong(6),
                            failureStreak = c.getInt(7),
                            maxStreak = c.getInt(8),
                            state = JobState.from(c.getString(9)),
                            lastResult = c.getString(10),
                            createdAt = c.getLong(11)
                        )
                    )
                }
            }

            for (job in dueJobs) {
                val startMs = System.currentTimeMillis()
                val handler = taskRegistry[job.name]

                val (success, output) = try {
                    if (handler != null) {
                        handler.invoke(context)
                    } else {
                        Pair(true, "Completado (sin handler específico)")
                    }
                } catch (e: Throwable) {
                    Pair(false, "Excepción: ${e.message ?: e.javaClass.simpleName}")
                }

                val endMs = System.currentTimeMillis()
                val durationMs = endMs - startMs
                var tripped = false

                val updateValues = ContentValues()
                updateValues.put("last_run_at", endMs)
                updateValues.put("next_run_at", endMs + (job.intervalSec * 1000L))

                if (success) {
                    updateValues.put("failure_streak", 0)
                    updateValues.put("last_result", output)
                } else {
                    val newStreak = job.failureStreak + 1
                    updateValues.put("failure_streak", newStreak)
                    if (newStreak >= job.maxStreak) {
                        tripped = true
                        updateValues.put("state", JobState.DISABLED_STREAK.value)
                        updateValues.put("last_result", "CIRCUIT_BREAKER_TRIPPED: Racha de $newStreak fallos. $output")
                    } else {
                        updateValues.put("last_result", "FALLO (intento $newStreak/${job.maxStreak}): $output")
                    }
                }

                db.update("scheduled_jobs", updateValues, "name = ?", arrayOf(job.name))

                // Historial
                val historyValues = ContentValues().apply {
                    put("job_name", job.name)
                    put("started_at", startMs)
                    put("finished_at", endMs)
                    put("duration_ms", durationMs)
                    put("exit_code", if (success) 0 else 1)
                    put("output", output)
                    put("status", if (success) "success" else "failed")
                }
                db.insert("job_run_history", null, historyValues)

                results.add(
                    JobExecutionResult(
                        jobName = job.name,
                        success = success,
                        durationMs = durationMs,
                        message = output,
                        circuitBreakerTripped = tripped
                    )
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error ejecutando trabajos cron: ${e.message}", e)
        } finally {
            releaseLock()
        }

        return results
    }

    /**
     * Lista todos los trabajos registrados en la base de datos.
     */
    fun listJobs(): List<ScheduledJob> {
        val list = mutableListOf<ScheduledJob>()
        val db = dbHelper.readableDatabase
        val cursor = db.rawQuery(
            """
            SELECT id, name, description, command, interval_sec, last_run_at, next_run_at,
                   failure_streak, max_streak, state, last_result, created_at
            FROM scheduled_jobs
            ORDER BY name ASC
            """.trimIndent(),
            null
        )

        cursor.use { c ->
            while (c.moveToNext()) {
                list.add(
                    ScheduledJob(
                        id = c.getLong(0),
                        name = c.getString(1),
                        description = c.getString(2) ?: "",
                        command = c.getString(3),
                        intervalSec = c.getLong(4),
                        lastRunAt = if (c.isNull(5)) null else c.getLong(5),
                        nextRunAt = c.getLong(6),
                        failureStreak = c.getInt(7),
                        maxStreak = c.getInt(8),
                        state = JobState.from(c.getString(9)),
                        lastResult = c.getString(10),
                        createdAt = c.getLong(11)
                    )
                )
            }
        }
        return list
    }

    /**
     * Habilita un trabajo previamente deshabilitado o disparado por Circuit Breaker,
     * reiniciando su racha de fallos.
     */
    fun enableJob(name: String): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("state", JobState.ENABLED.value)
            put("failure_streak", 0)
            put("next_run_at", System.currentTimeMillis())
        }
        return db.update("scheduled_jobs", values, "name = ?", arrayOf(name)) > 0
    }

    /**
     * Ajusta next_run_at para pruebas o reintentos inmediatos sin reiniciar la racha de fallos.
     */
    fun forceNextRun(name: String, timestamp: Long = System.currentTimeMillis()): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("next_run_at", timestamp)
        }
        return db.update("scheduled_jobs", values, "name = ?", arrayOf(name)) > 0
    }

    /**
     * Deshabilita voluntariamente un trabajo.
     */
    fun disableJob(name: String): Boolean {
        val db = dbHelper.writableDatabase
        val values = ContentValues().apply {
            put("state", JobState.DISABLED.value)
        }
        return db.update("scheduled_jobs", values, "name = ?", arrayOf(name)) > 0
    }

    /**
     * Elimina un trabajo de la programación.
     */
    fun deleteJob(name: String): Boolean {
        val db = dbHelper.writableDatabase
        return db.delete("scheduled_jobs", "name = ?", arrayOf(name)) > 0
    }
}
