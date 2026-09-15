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

        val inspection = ToolArgumentInspector.inspect(req.toolName, req.argumentsJson)
        val enrichedReq = if (inspection.dangerReason != null && req.dangerReason == null) {
            req.copy(
                risk = inspection.escalatedRisk ?: req.risk,
                dangerReason = inspection.dangerReason
            )
        } else {
            req
        }

        if (!ToolApprovalPolicy.requiresApproval(enrichedReq, policy)) {
            return ApprovalDecision.APPROVED
        }

        val toolKey = enrichedReq.toolName.lowercase().trim()
        // Si la petición proviene de contaminación web o contiene peligro crítico,
        // no se permite eludir la confirmación mediante la allowlist de la sesión (CaMeL IFC).
        val bypassAllowlist = enrichedReq.isWebTainted || inspection.isCriticalDanger
        if (!bypassAllowlist) {
            synchronized(lock) {
                if (sessionAllowlist.contains(toolKey)) {
                    return ApprovalDecision.APPROVED
                }
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
                val decided = java.util.concurrent.atomic.AtomicBoolean(false)
                dialogRenderer(enrichedReq) { decision ->
                    if (decided.compareAndSet(false, true)) {
                        decisionRef.set(decision)
                        if (decision == ApprovalDecision.APPROVED_SESSION &&
                            !ToolApprovalPolicy.isIrreversible(enrichedReq.toolName) &&
                            !enrichedReq.isWebTainted &&
                            !inspection.isCriticalDanger) {
                            synchronized(lock) {
                                sessionAllowlist.add(toolKey)
                            }
                        }
                        latch.countDown()
                    }
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
