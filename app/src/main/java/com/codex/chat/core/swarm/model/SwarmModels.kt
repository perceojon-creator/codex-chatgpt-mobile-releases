package com.codex.chat.core.swarm.model

import com.codex.chat.core.mcp.taint.TaintOrigin
import java.util.UUID

/**
 * Roles especializados dentro del panal de agentes.
 */
enum class SwarmRole {
    ORCHESTRATOR,
    SENSOR_OS,
    CODE_COMPUTE,
    MULTIMEDIA,
    WEB_RESEARCH,
    CRITIC
}

/**
 * Estados posibles en el ciclo de vida de un worker del enjambre.
 */
enum class WorkerStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    TIMED_OUT,
    FAILED,
    CANCELLED
}

/**
 * Representa una tarea delegada a un worker especializado.
 */
data class SwarmTask(
    val taskId: String = UUID.randomUUID().toString(),
    val role: SwarmRole,
    val prompt: String,
    val timeoutMs: Long = 30000L,
    val parentTaskId: String? = null,
    val depth: Int = 1
)

/**
 * Resultado consolidado devuelto por un worker tras su ejecucion.
 */
data class SwarmTaskResult(
    val taskId: String,
    val role: SwarmRole,
    val status: WorkerStatus,
    val outputPayload: String,
    val executionDurationMs: Long,
    val taintOrigin: TaintOrigin? = null,
    val errorMessage: String? = null
)

/**
 * Ticket de seguimiento retornado al despachar una subtarea de forma asincrona.
 */
data class WorkerTicket(
    val ticketId: String = UUID.randomUUID().toString(),
    val taskId: String,
    val role: SwarmRole,
    val dispatchedAt: Long = System.currentTimeMillis()
)

/**
 * Perfil de confinamiento de seguridad: asigna herramientas MCP exclusivas por rol.
 * Aplica el principio de minimo privilegio (Principle of Least Privilege).
 */
data class WorkerProfile(
    val role: SwarmRole,
    val name: String,
    val iconEmoji: String,
    val allowedTools: Set<String>
) {
    companion object {
        fun forRole(role: SwarmRole): WorkerProfile {
            return when (role) {
                SwarmRole.ORCHESTRATOR -> WorkerProfile(
                    role = role,
                    name = "Agente Reina (Orchestrator)",
                    iconEmoji = "👑",
                    allowedTools = setOf("spawn_worker", "await_workers", "get_worker_status", "read_blackboard")
                )
                SwarmRole.SENSOR_OS -> WorkerProfile(
                    role = role,
                    name = "Obrero Telemetria y SO",
                    iconEmoji = "📱",
                    allowedTools = setOf(
                        "get_battery_status", "get_device_telemetry", "get_storage_info",
                        "get_wifi_status", "get_storage_root", "get_device_settings",
                        "get_app_usage_stats", "get_captured_notifications"
                    )
                )
                SwarmRole.CODE_COMPUTE -> WorkerProfile(
                    role = role,
                    name = "Obrero Cómputo y Sandbox",
                    iconEmoji = "💻",
                    allowedTools = setOf(
                        "evaluate_math", "compute_hash", "execute_python",
                        "execute_sandbox_command", "list_memories", "get_memory"
                    )
                )
                SwarmRole.MULTIMEDIA -> WorkerProfile(
                    role = role,
                    name = "Obrero Medios y Diagramas",
                    iconEmoji = "🎨",
                    allowedTools = setOf(
                        "test_html_code", "inspect_html_dom"
                    )
                )
                SwarmRole.WEB_RESEARCH -> WorkerProfile(
                    role = role,
                    name = "Obrero Navegación Web",
                    iconEmoji = "🌐",
                    allowedTools = setOf(
                        "dns_resolve", "ping_host", "http_get", "web_search"
                    )
                )
                SwarmRole.CRITIC -> WorkerProfile(
                    role = role,
                    name = "Auditor Crítico",
                    iconEmoji = "🛡️",
                    allowedTools = setOf("read_blackboard", "compute_hash")
                )
            }
        }
    }
}