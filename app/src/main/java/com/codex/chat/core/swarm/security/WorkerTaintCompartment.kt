package com.codex.chat.core.swarm.security

import com.codex.chat.core.mcp.approval.ToolRiskClassifier
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import com.codex.chat.core.mcp.taint.SessionTaintTracker
import com.codex.chat.core.mcp.taint.TaintOrigin
import com.codex.chat.core.swarm.model.SwarmRole

/**
 * Compartimento de aislamiento de contaminación (CaMeL Taint Compartment) por Worker.
 * Asegura que una inyección de prompt o datos no confiables capturados por un worker de red
 * no contaminen los permisos ni capacidades de los workers que operan sobre el sistema operativo o SMS.
 */
class WorkerTaintCompartment(
    val role: SwarmRole,
    val tracker: SessionTaintTracker = SessionTaintTracker()
) {

    fun markTainted(origin: TaintOrigin) {
        tracker.markTainted(origin)
    }

    fun isTainted(): Boolean {
        return tracker.isSessionTainted()
    }

    /**
     * Verifica si el worker tiene autorización para ejecutar una herramienta concreta.
     * Si el worker está contaminado (isTainted = true), bloquea herramientas DESTRUCTIVE o ROOT.
     */
    fun allowsTool(toolName: String): Boolean {
        if (!isTainted()) return true

        val risk = ToolRiskClassifier.classify(toolName)
        return risk != ToolRiskLevel.DESTRUCTIVE && risk != ToolRiskLevel.ROOT
    }

    /**
     * Propaga la contaminación hacia el tracker de la Reina únicamente si esta consume
     * el payload crudo no sanitizado devuelto por el worker.
     */
    fun propagateToOrchestrator(
        orchestratorTracker: SessionTaintTracker,
        rawPayloadConsumed: Boolean
    ) {
        if (rawPayloadConsumed && isTainted()) {
            for (origin in tracker.taintOrigins()) {
                orchestratorTracker.markTainted(origin)
            }
        }
    }
}