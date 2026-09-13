package com.codex.chat.core.mcp.approval

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ToolApprovalGate(
    private val enHiloUi: (() -> Unit) -> Unit,
    private val actividadViva: () -> Boolean,
    private val enHiloPrincipal: () -> Boolean = { false },
    private val timeoutSegundos: Long = 120,
    private val dialogRenderer: (ApprovalRequest, (ApprovalDecision) -> Unit) -> Unit
) {

    private val sessionAllowlist = mutableSetOf<String>()
    private val lock = Any()

    fun clearSessionAllowlist() {
        synchronized(lock) {
            sessionAllowlist.clear()
        }
    }

    fun limpiarAllowlist() = clearSessionAllowlist()

    fun allowlistActual(): Set<String> = synchronized(lock) { sessionAllowlist.toSet() }

    fun getSessionAllowlist(): Set<String> = allowlistActual()

    fun isAllowedInSession(toolName: String): Boolean {
        synchronized(lock) {
            return sessionAllowlist.contains(toolName.lowercase().trim())
        }
    }

    fun decide(req: ApprovalRequest, policy: ApprovalPolicy): ApprovalDecision {
        // GUARDA ANTI-DEADLOCK: decide() bloquea el hilo; nunca invocar desde UI
        if (enHiloPrincipal()) {
            throw IllegalStateException("ToolApprovalGate.decide() bloquea el hilo. Nunca llamarlo desde UI.")
        }

        if (!ToolApprovalPolicy.requiresApproval(req.risk, policy)) {
            return ApprovalDecision.APPROVED
        }

        val toolKey = req.toolName.lowercase().trim()
        synchronized(lock) {
            if (sessionAllowlist.contains(toolKey)) {
                return ApprovalDecision.APPROVED
            }
        }

        if (!actividadViva()) {
            return ApprovalDecision.DENIED
        }

        val latch = CountDownLatch(1)
        val decisionRef = AtomicReference(ApprovalDecision.TIMEOUT)

        enHiloUi {
            try {
                if (!actividadViva()) {
                    decisionRef.set(ApprovalDecision.DENIED)
                    latch.countDown()
                    return@enHiloUi
                }
                dialogRenderer(req) { decision ->
                    decisionRef.set(decision)
                    if (decision == ApprovalDecision.APPROVED_SESSION && !ToolApprovalPolicy.isIrreversible(req.toolName)) {
                        synchronized(lock) {
                            sessionAllowlist.add(toolKey)
                        }
                    }
                    latch.countDown()
                }
            } catch (e: Exception) {
                decisionRef.set(ApprovalDecision.DENIED)
                latch.countDown()
            }
        }

        val completed = latch.await(timeoutSegundos, TimeUnit.SECONDS)
        return if (completed) decisionRef.get() else ApprovalDecision.TIMEOUT
    }
}
