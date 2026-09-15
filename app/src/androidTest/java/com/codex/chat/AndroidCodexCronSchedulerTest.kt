package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.scheduler.CodexCronScheduler
import com.codex.chat.core.scheduler.CodexMaintenanceWorker
import com.codex.chat.core.scheduler.JobState
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class AndroidCodexCronSchedulerTest {

    private lateinit var scheduler: CodexCronScheduler

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        scheduler = CodexCronScheduler.getInstance(context)
        scheduler.releaseLock()
    }

    @Test
    fun testRegisterAndListJobs() {
        val jobName = "test_telemetry_sync"
        val registered = scheduler.registerJob(
            name = jobName,
            intervalSec = 600L,
            description = "Test sync",
            maxStreak = 3
        )
        assertTrue("El trabajo debe registrarse", registered)

        val jobs = scheduler.listJobs()
        val found = jobs.find { it.name == jobName }
        assertNotNull("El trabajo debe aparecer en listJobs()", found)
        assertEquals(JobState.ENABLED, found!!.state)
        assertEquals(0, found.failureStreak)
        assertEquals(3, found.maxStreak)
        assertEquals(600L, found.intervalSec)
    }

    @Test
    fun testRunDueJobsExecution() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val jobName = "test_success_task"
        val executionCounter = AtomicInteger(0)

        scheduler.registerTaskHandler(jobName) {
            executionCounter.incrementAndGet()
            Pair(true, "OK desde Android Real Device")
        }

        scheduler.registerJob(name = jobName, intervalSec = 30L, maxStreak = 3)
        scheduler.enableJob(jobName)

        val results = scheduler.runDueJobs()
        val result = results.find { it.jobName == jobName }

        assertNotNull("Debe ejecutar el trabajo vencido", result)
        assertTrue("La ejecución debe ser exitosa", result!!.success)
        assertEquals(1, executionCounter.get())
        assertFalse("Circuit breaker no debe dispararse en éxito", result.circuitBreakerTripped)

        val jobAfter = scheduler.listJobs().find { it.name == jobName }
        assertEquals(0, jobAfter!!.failureStreak)
        assertNotNull("lastRunAt debe actualizarse", jobAfter.lastRunAt)
    }

    @Test
    fun testCircuitBreakerTrippedOnConsecutiveFailures() {
        val jobName = "test_failing_task"
        val failCounter = AtomicInteger(0)

        scheduler.registerTaskHandler(jobName) {
            failCounter.incrementAndGet()
            Pair(false, "Simulated network timeout")
        }

        scheduler.registerJob(name = jobName, intervalSec = 60L, maxStreak = 3)
        scheduler.enableJob(jobName)

        // Intento 1: Fallo 1 (racha = 1, sigue ENABLED)
        var res = scheduler.runDueJobs().find { it.jobName == jobName }
        assertNotNull(res)
        assertFalse(res!!.success)
        assertFalse(res.circuitBreakerTripped)

        var job = scheduler.listJobs().find { it.name == jobName }
        assertEquals(1, job!!.failureStreak)
        assertEquals(JobState.ENABLED, job.state)

        // Intento 2: Forzamos next_run_at a vencido y ejecutamos (racha = 2, sigue ENABLED)
        scheduler.forceNextRun(jobName, System.currentTimeMillis() - 1000L)
        res = scheduler.runDueJobs().find { it.jobName == jobName }
        assertNotNull(res)
        assertFalse(res!!.success)
        assertFalse(res.circuitBreakerTripped)

        job = scheduler.listJobs().find { it.name == jobName }
        assertEquals(2, job!!.failureStreak)
        assertEquals(JobState.ENABLED, job.state)

        // Intento 3: Tercer fallo consecutivo alcanza maxStreak = 3 -> Circuit breaker tripped
        scheduler.forceNextRun(jobName, System.currentTimeMillis() - 1000L)
        res = scheduler.runDueJobs().find { it.jobName == jobName }
        assertNotNull(res)
        assertFalse(res!!.success)
        assertTrue("Circuit breaker debe dispararse en el tercer fallo consecutivo", res.circuitBreakerTripped)

        job = scheduler.listJobs().find { it.name == jobName }
        assertEquals(3, job!!.failureStreak)
        assertEquals(JobState.DISABLED_STREAK, job.state)
    }

    @Test
    fun testCircuitBreakerTripsDirectly() {
        val jobName = "test_cb_trip_direct"
        scheduler.registerTaskHandler(jobName) {
            Pair(false, "Fallo critico")
        }
        // maxStreak = 1: se dispara en el primer fallo
        scheduler.registerJob(name = jobName, intervalSec = 10L, maxStreak = 1)
        scheduler.enableJob(jobName)

        val results = scheduler.runDueJobs()
        val res = results.find { it.jobName == jobName }
        assertNotNull(res)
        assertFalse(res!!.success)
        assertTrue("Circuit breaker debe dispararse cuando racha >= maxStreak", res.circuitBreakerTripped)

        val jobState = scheduler.listJobs().find { it.name == jobName }
        assertEquals(JobState.DISABLED_STREAK, jobState!!.state)
    }

    @Test
    fun testEnableJobResetsStreak() {
        val jobName = "test_reset_streak"
        scheduler.registerTaskHandler(jobName) {
            Pair(false, "Error inicial")
        }
        scheduler.registerJob(name = jobName, intervalSec = 10L, maxStreak = 1)
        scheduler.runDueJobs()

        var job = scheduler.listJobs().find { it.name == jobName }
        assertEquals(JobState.DISABLED_STREAK, job!!.state)

        // Reactivación manual / automática
        val enabled = scheduler.enableJob(jobName)
        assertTrue("enableJob debe retornar true", enabled)

        job = scheduler.listJobs().find { it.name == jobName }
        assertEquals(JobState.ENABLED, job!!.state)
        assertEquals(0, job.failureStreak)
    }

    @Test
    fun testSchedulerLockConcurrency() {
        assertTrue("Debe adquirir el cerrojo inicialmente", scheduler.acquireLock())
        assertFalse("No debe permitir un segundo cerrojo simultáneo", scheduler.acquireLock())
        scheduler.releaseLock()
        assertTrue("Debe poder adquirir de nuevo tras liberar", scheduler.acquireLock())
        scheduler.releaseLock()
    }

    @Test
    fun testMaintenanceWorkerIntegratedRun() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        CodexMaintenanceWorker.start(context)
        assertTrue("CodexMaintenanceWorker debe reportar estado corriendo", CodexMaintenanceWorker.isRunning())

        val sched = CodexCronScheduler.getInstance(context)
        sched.forceNextRun("sqlite_wal_checkpoint", 0L)
        sched.forceNextRun("estop_heartbeat", 0L)

        val results = CodexMaintenanceWorker.runOnceNow(context)
        // Debe ejecutar los trabajos integrados (sqlite_wal_checkpoint y estop_heartbeat)
        assertTrue("Debe procesar trabajos de mantenimiento", results.isNotEmpty())
        val walJob = results.find { it.jobName == "sqlite_wal_checkpoint" }
        assertNotNull("Debe ejecutar sqlite_wal_checkpoint", walJob)
        assertTrue("sqlite_wal_checkpoint debe completarse", walJob!!.success)

        val estopJob = results.find { it.jobName == "estop_heartbeat" }
        assertNotNull("Debe ejecutar estop_heartbeat", estopJob)
        assertTrue("estop_heartbeat debe completarse", estopJob!!.success)

        CodexMaintenanceWorker.stop()
        assertFalse("CodexMaintenanceWorker debe detenerse limpiamente", CodexMaintenanceWorker.isRunning())
    }
}
