package com.codex.chat.core.mcp.server

import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.swarm.blackboard.SwarmBlackboard
import com.codex.chat.core.swarm.engine.SwarmAgentDispatcher
import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.SwarmTask
import com.codex.chat.core.swarm.model.WorkerStatus
import com.codex.chat.core.swarm.model.WorkerTicket
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Servidor MCP nativo del Enjambre de Agentes (Mobile Hive Orchestrator).
 * Permite a la Reina (modelo principal) delegar, sincronizar y consultar subtareas.
 */
class SwarmOrchestratorMcpServer(
    private val dispatcher: SwarmAgentDispatcher = SwarmAgentDispatcher(),
    private val blackboard: SwarmBlackboard = SwarmBlackboard()
) : McpServer {

    private val registeredTickets = ConcurrentHashMap<String, WorkerTicket>()
    /** Session key scoped to this server instance (stateless per MCP call). */
    private val sessionId = "swarm-" + System.currentTimeMillis().toString()

    override val info = McpServerInfo(
        id = "mcp-swarm-orchestrator",
        name = "Swarm Hive Orchestrator",
        description = "Orquestador de enjambre de agentes móviles concurrentes (despacho, sincronizacion y consulta)",
        iconEmoji = "🐝",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "spawn_worker",
            description = "Despacha un subagente obrero especializado en segundo plano. Roles validos: SENSOR_OS, CODE_COMPUTE, MULTIMEDIA, WEB_RESEARCH, CRITIC. Retorna un JSON con ticket_id.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("role", JSONObject().put("type", "string").put("description", "Rol del worker (ej. SENSOR_OS, CODE_COMPUTE, MULTIMEDIA, WEB_RESEARCH, CRITIC)"))
                    put("task_prompt", JSONObject().put("type", "string").put("description", "Instruccion u objetivo especifico para el subagente"))
                    put("timeout_sec", JSONObject().put("type", "integer").put("description", "Tiempo limite en segundos (default 30)"))
                }
                put("properties", props)
                put("required", JSONArray().put("role").put("task_prompt"))
            }
        ),
        McpTool(
            name = "await_workers",
            description = "Espera concurrentemente la finalizacion de uno o mas workers por sus ticket_ids y recolecta sus resultados consolidados.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("ticket_ids", JSONObject().put("type", "array").put("description", "Lista de ticket_ids a esperar"))
                    put("timeout_sec", JSONObject().put("type", "integer").put("description", "Tiempo limite global en segundos (default 45)"))
                }
                put("properties", props)
                put("required", JSONArray().put("ticket_ids"))
            }
        ),
        McpTool(
            name = "get_worker_status",
            description = "Consulta el estado actual de un worker especifico sin bloquear la ejecucion.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("ticket_id", JSONObject().put("type", "string").put("description", "Identificador del ticket"))
                }
                put("properties", props)
                put("required", JSONArray().put("ticket_id"))
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        val args = try {
            if (call.argumentsJson.isBlank()) JSONObject() else JSONObject(call.argumentsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        return try {
            when (call.toolName) {
                "spawn_worker" -> executeSpawnWorker(call, args)
                "await_workers" -> executeAwaitWorkers(call, args)
                "get_worker_status" -> executeGetWorkerStatus(call, args)
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error en enjambre: " + (e.message ?: e.javaClass.simpleName), isError = true)
        }
    }

    private fun executeSpawnWorker(call: McpToolCallRequest, args: JSONObject): McpToolResult {
        val rawRole = args.optString("role", "").trim()
        val prompt = args.optString("task_prompt", "").trim()
        val timeoutSec = args.optInt("timeout_sec", 30)

        val role = try {
            SwarmRole.valueOf(rawRole.uppercase())
        } catch (_: Exception) {
            return McpToolResult(call.id, call.toolName, "Error: Rol no valido '" + rawRole + "'. Validos: " + SwarmRole.values().joinToString(), isError = true)
        }

        if (prompt.isBlank()) {
            return McpToolResult(call.id, call.toolName, "Error: task_prompt no puede estar vacio", isError = true)
        }

        val task = SwarmTask(
            role = role,
            prompt = prompt,
            timeoutMs = (timeoutSec * 1000L).coerceAtLeast(1000L)
        )

        val ticket = dispatcher.dispatch(task) { workerTask ->
            val output = "Worker [" + workerTask.role.name + "] proceso exitosamente: " + workerTask.prompt
            // Post finding to the shared blackboard so other workers and the orchestrator can read it.
            blackboard.postFinding(sessionId, workerTask.taskId, workerTask.taskId, output)
            output
        }
        registeredTickets[ticket.ticketId] = ticket

        val response = JSONObject().apply {
            put("ticket_id", ticket.ticketId)
            put("task_id", ticket.taskId)
            put("role", ticket.role.name)
            put("status", WorkerStatus.QUEUED.name)
            put("message", "Subagente obrero despachado exitosamente")
        }

        return McpToolResult(call.id, call.toolName, response.toString())
    }

    private fun executeAwaitWorkers(call: McpToolCallRequest, args: JSONObject): McpToolResult {
        val arr = args.optJSONArray("ticket_ids") ?: JSONArray()
        val timeoutSec = args.optInt("timeout_sec", 45)

        val tickets = mutableListOf<WorkerTicket>()
        for (i in 0 until arr.length()) {
            val tid = arr.optString(i, "")
            val ticket = registeredTickets[tid]
            if (ticket != null) {
                tickets.add(ticket)
            } else {
                tickets.add(WorkerTicket(ticketId = tid, taskId = "unknown", role = SwarmRole.ORCHESTRATOR))
            }
        }

        val results = dispatcher.awaitAll(tickets, timeoutMs = timeoutSec * 1000L)
        val resJsonArr = JSONArray()
        for (r in results) {
            resJsonArr.put(JSONObject().apply {
                put("task_id", r.taskId)
                put("role", r.role.name)
                put("status", r.status.name)
                put("output_payload", r.outputPayload)
                put("duration_ms", r.executionDurationMs)
                put("error_message", r.errorMessage ?: JSONObject.NULL)
            })
        }

        return McpToolResult(call.id, call.toolName, resJsonArr.toString())
    }

    private fun executeGetWorkerStatus(call: McpToolCallRequest, args: JSONObject): McpToolResult {
        val tid = args.optString("ticket_id", "")
        if (tid.isBlank()) {
            return McpToolResult(call.id, call.toolName, "Error: ticket_id requerido", isError = true)
        }
        val status = dispatcher.getStatus(tid)
        val res = JSONObject().apply {
            put("ticket_id", tid)
            put("status", status.name)
        }
        return McpToolResult(call.id, call.toolName, res.toString())
    }
}