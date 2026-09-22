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

    enum class GuardrailSignal {
        PROCEED,
        WARN_SWITCH_HYPOTHESIS,
        HARD_STOP_BLOCK
    }

    companion object {
        const val WARN_EXACT_FAILURE = 2
        const val WARN_SAME_TOOL_FAILURE = 3
        const val HARD_STOP_EXACT_FAILURE = 5
        const val HARD_STOP_SAME_TOOL_FAILURE = 8
    }

    private val sessionAllowlist = mutableSetOf<String>()
    private val toolFailureCounts = mutableMapOf<String, Int>()
    private val exactFailureCounts = mutableMapOf<String, Int>()
    private val blockedTools = mutableSetOf<String>()
    private val lock = Any()

    fun recordToolFailure(toolName: String, errorSignature: String): GuardrailSignal {
        synchronized(lock) {
            val toolKey = toolName.lowercase().trim()
            val exactKey = "$toolKey::${errorSignature.trim()}"

            val toolFails = (toolFailureCounts[toolKey] ?: 0) + 1
            toolFailureCounts[toolKey] = toolFails

            val exactFails = (exactFailureCounts[exactKey] ?: 0) + 1
            exactFailureCounts[exactKey] = exactFails

            if (exactFails >= HARD_STOP_EXACT_FAILURE || toolFails >= HARD_STOP_SAME_TOOL_FAILURE) {
                blockedTools.add(toolKey)
                return GuardrailSignal.HARD_STOP_BLOCK
            }
            if (exactFails >= WARN_EXACT_FAILURE || toolFails >= WARN_SAME_TOOL_FAILURE) {
                return GuardrailSignal.WARN_SWITCH_HYPOTHESIS
            }
            return GuardrailSignal.PROCEED
        }
    }

    fun recordToolSuccess(toolName: String) {
        synchronized(lock) {
            val toolKey = toolName.lowercase().trim()
            toolFailureCounts.remove(toolKey)
            exactFailureCounts.keys.filter { it.startsWith("$toolKey::") }.forEach { exactFailureCounts.remove(it) }
            blockedTools.remove(toolKey)
        }
    }

    fun resetTurnFailures() {
        synchronized(lock) {
            toolFailureCounts.clear()
            exactFailureCounts.clear()
            blockedTools.clear()
        }
    }

    fun isToolBlockedByGuardrails(toolName: String): Boolean {
        synchronized(lock) {
            return blockedTools.contains(toolName.lowercase().trim())
        }
    }

    fun clearSessionAllowlist() {
        synchronized(lock) {
            sessionAllowlist.clear()
            toolFailureCounts.clear()
            exactFailureCounts.clear()
            blockedTools.clear()
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

        // GUARDA ESTOP: Parada de Emergencia Global (Hermes Agent / DeepSeek Harness)
        if (com.codex.chat.core.security.EstopSentinel.isEngaged()) {
            return ApprovalDecision.DENIED
        }

        // GUARDA ANTI-LOOP HARD STOP: Si la herramienta alcanzó el límite crítico de fallos
        if (isToolBlockedByGuardrails(req.toolName)) {
            return ApprovalDecision.DENIED
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
