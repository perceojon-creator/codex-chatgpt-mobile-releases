package com.codex.chat.core.swarm.engine

import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.SwarmTask
import com.codex.chat.core.swarm.model.SwarmTaskResult
import com.codex.chat.core.swarm.model.WorkerStatus
import com.codex.chat.core.swarm.model.WorkerTicket
import com.codex.chat.core.swarm.security.WorkerTaintCompartment
import java.util.UUID
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicInteger

/**
 * Motor de despacho y orquestacion concurrente de workers para el enjambre móvil.
 * Aplica aislamiento de hilos acotado para prevenir saturacion de CPU en el dispositivo móvil.
 */
class SwarmAgentDispatcher(
    private val maxConcurrency: Int = 4
) {
    private val threadIndex = AtomicInteger(1)
    private val executor: ExecutorService = ThreadPoolExecutor(
        maxConcurrency.coerceAtLeast(2),
        maxConcurrency,
        60L, TimeUnit.SECONDS,
        LinkedBlockingQueue<Runnable>(50),
        ThreadFactory { r ->
            Thread(r, "SwarmWorker-" + threadIndex.getAndIncrement()).apply {
                isDaemon = true
            }
        }
    )

    private val activeFutures = ConcurrentHashMap<String, Future<SwarmTaskResult>>()
    private val cachedResults = ConcurrentHashMap<String, SwarmTaskResult>()
    private val ticketTasks = ConcurrentHashMap<String, SwarmTask>()
    // Per-worker taint compartments — keyed by ticket ID, created at dispatch time.
    private val taintCompartments = ConcurrentHashMap<String, WorkerTaintCompartment>()

    fun dispatch(task: SwarmTask, runner: (SwarmTask) -> String): WorkerTicket {
        SwarmDepthSentinel.auditDepth(task.depth)

        val ticket = WorkerTicket(
            ticketId = "ticket_" + UUID.randomUUID().toString().take(12),
            taskId = task.taskId,
            role = task.role,
            dispatchedAt = System.currentTimeMillis()
        )
        ticketTasks[ticket.ticketId] = task
        val compartment = WorkerTaintCompartment(task.role)
        taintCompartments[ticket.ticketId] = compartment

        val callable = Callable {
            val startMs = System.currentTimeMillis()
            try {
                // CaMeL taint gate: deny execution if the worker is tainted and the task targets a sensitive tool.
                if (!compartment.allowsTool(task.prompt)) {
                    val blocked = SwarmTaskResult(
                        taskId = task.taskId,
                        role = task.role,
                        status = WorkerStatus.FAILED,
                        outputPayload = "",
                        executionDurationMs = 0L,
                        errorMessage = "[TAINT_BLOCKED] Worker ${task.role} denegado por contaminacion activa."
                    )
                    cachedResults[ticket.ticketId] = blocked
                    return@Callable blocked
                }
                val output = runner(task)
                val duration = System.currentTimeMillis() - startMs
                val result = SwarmTaskResult(
                    taskId = task.taskId,
                    role = task.role,
                    status = WorkerStatus.COMPLETED,
                    outputPayload = output,
                    executionDurationMs = duration
                )
                cachedResults[ticket.ticketId] = result
                result
            } catch (t: Throwable) {
                val duration = System.currentTimeMillis() - startMs
                val result = SwarmTaskResult(
                    taskId = task.taskId,
                    role = task.role,
                    status = WorkerStatus.FAILED,
                    outputPayload = "",
                    executionDurationMs = duration,
                    errorMessage = t.message ?: t.javaClass.simpleName
                )
                cachedResults[ticket.ticketId] = result
                result
            }
        }

        val future = executor.submit(callable)
        activeFutures[ticket.ticketId] = future
        return ticket
    }

    fun await(ticket: WorkerTicket, timeoutMs: Long = 30000L): SwarmTaskResult {
        cachedResults[ticket.ticketId]?.let { return it }

        val future = activeFutures[ticket.ticketId] ?: return SwarmTaskResult(
            taskId = ticket.taskId,
            role = ticket.role,
            status = WorkerStatus.FAILED,
            outputPayload = "",
            executionDurationMs = 0L,
            errorMessage = "Ticket no encontrado o ya purgado"
        )

        val taskTimeout = ticketTasks[ticket.ticketId]?.timeoutMs ?: timeoutMs
        val effectiveTimeout = minOf(taskTimeout, timeoutMs)

        return try {
            val res = future.get(effectiveTimeout, TimeUnit.MILLISECONDS)
            cachedResults[ticket.ticketId] = res
            res
        } catch (e: TimeoutException) {
            future.cancel(true)
            val timedOutRes = SwarmTaskResult(
                taskId = ticket.taskId,
                role = ticket.role,
                status = WorkerStatus.TIMED_OUT,
                outputPayload = "",
                executionDurationMs = effectiveTimeout,
                errorMessage = "Tiempo excedido (timed out) tras " + effectiveTimeout + "ms"
            )
            cachedResults[ticket.ticketId] = timedOutRes
            timedOutRes
        } catch (e: CancellationException) {
            val cancelledRes = SwarmTaskResult(
                taskId = ticket.taskId,
                role = ticket.role,
                status = WorkerStatus.CANCELLED,
                outputPayload = "",
                executionDurationMs = 0L,
                errorMessage = "Worker cancelado"
            )
            cachedResults[ticket.ticketId] = cancelledRes
            cancelledRes
        } catch (e: ExecutionException) {
            val cause = e.cause ?: e
            val failedRes = SwarmTaskResult(
                taskId = ticket.taskId,
                role = ticket.role,
                status = WorkerStatus.FAILED,
                outputPayload = "",
                executionDurationMs = 0L,
                errorMessage = cause.message ?: cause.javaClass.simpleName
            )
            cachedResults[ticket.ticketId] = failedRes
            failedRes
        }
    }

    fun awaitAll(tickets: List<WorkerTicket>, timeoutMs: Long = 60000L): List<SwarmTaskResult> {
        val deadline = System.currentTimeMillis() + timeoutMs
        return tickets.map { ticket ->
            val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(1L)
            await(ticket, remaining)
        }
    }

    fun getStatus(ticketId: String): WorkerStatus {
        cachedResults[ticketId]?.let { return it.status }
        val future = activeFutures[ticketId] ?: return WorkerStatus.FAILED
        return if (future.isDone) {
            try {
                future.get().status
            } catch (_: Throwable) {
                WorkerStatus.FAILED
            }
        } else if (future.isCancelled) {
            WorkerStatus.CANCELLED
        } else {
            WorkerStatus.RUNNING
        }
    }

    fun cancel(ticketId: String): Boolean {
        val future = activeFutures[ticketId] ?: return false
        return future.cancel(true)
    }

    fun shutdown() {
        executor.shutdownNow()
    }
}