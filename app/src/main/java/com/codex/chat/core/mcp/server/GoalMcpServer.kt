package com.codex.chat.core.mcp.server

import com.codex.chat.core.goal.engine.GoalEngine
import com.codex.chat.core.goal.model.*
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Servidor MCP nativo del Motor de Objetivos Autónomos (Autonomous Goal Engine).
 * Expone create_goal, get_goal y update_goal con paridad a DeepSeek Harness.
 */
class GoalMcpServer(
    private val engine: GoalEngine = GoalEngine()
) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-autonomous-goal",
        name = "Autonomous Goal Engine",
        description = "Gestión autónoma de objetivos persistentes en sesión (creación, lectura, actualización CAS y finalización)",
        iconEmoji = "🎯",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "create_goal",
            description = "Crea un objetivo persistente en la sesión actual para ejecución autónoma multi-ronda. Solo puede haber un objetivo activo a la vez.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("objective", JSONObject().put("type", "string").put("description", "El objetivo concreto que debe perseguirse"))
                    put("max_goal_rounds", JSONObject().put("type", "integer").put("description", "Límite positivo de rondas automáticas (default 10)"))
                }
                put("properties", props)
                put("required", JSONArray().put("objective"))
            }
        ),
        McpTool(
            name = "get_goal",
            description = "Lee el objetivo actual de la sesión, incluyendo id exacto, revisión actual, fase, rondas iniciadas, límite y si está armado.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "update_goal",
            description = "Actualiza el objetivo actual usando control de concurrencia optimista (CAS). Requiere goal_id y revisión exacta obtenidos de get_goal.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("goal_id", JSONObject().put("type", "string").put("description", "Identificador exacto devuelto por get_goal o create_goal"))
                    put("revision", JSONObject().put("type", "integer").put("description", "Revisión exacta positiva devuelta por get_goal"))
                    put("action", JSONObject().put("type", "string").put("description", "Acción: 'edit', 'pause', 'resume', 'complete' o 'blocked'"))
                    put("objective", JSONObject().put("type", "string").put("description", "Nuevo objetivo (válido solo con acción 'edit')"))
                    put("max_goal_rounds", JSONObject().put("type", "integer").put("description", "Nuevo límite de rondas (válido solo con acción 'edit')"))
                    put("blocked_reason", JSONObject().put("type", "string").put("description", "Explicación del bloqueo (requerido con acción 'blocked')"))
                }
                put("properties", props)
                put("required", JSONArray().put("goal_id").put("revision").put("action"))
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
                "create_goal" -> executeCreateGoal(call, args)
                "get_goal" -> executeGetGoal(call)
                "update_goal" -> executeUpdateGoal(call, args)
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error en Goal Engine: " + (e.message ?: e.javaClass.simpleName), isError = true)
        }
    }

    private fun executeCreateGoal(call: McpToolCallRequest, args: JSONObject): McpToolResult {
        val objective = args.optString("objective", "").trim()
        val maxRounds = args.optInt("max_goal_rounds", 10)

        if (objective.isBlank()) {
            return McpToolResult(call.id, call.toolName, "Error: 'objective' no puede estar vacio", isError = true)
        }

        val snapshot = engine.createGoal(objective, maxRounds)
        val res = JSONObject().apply {
            put("goal", snapshot.toJson())
            put("activation", snapshot.activation.name.lowercase())
        }
        return McpToolResult(call.id, call.toolName, res.toString())
    }

    private fun executeGetGoal(call: McpToolCallRequest): McpToolResult {
        val snapshot = engine.getGoal()
        val res = JSONObject().apply {
            if (snapshot != null) {
                put("goal", snapshot.toJson())
                put("activation", snapshot.activation.name.lowercase())
            } else {
                put("goal", JSONObject.NULL)
                put("activation", "disarmed")
            }
        }
        return McpToolResult(call.id, call.toolName, res.toString())
    }

    private fun executeUpdateGoal(call: McpToolCallRequest, args: JSONObject): McpToolResult {
        val goalId = args.optString("goal_id", "").trim()
        val revision = args.optInt("revision", -1)
        val rawAction = args.optString("action", "").trim().uppercase()

        if (goalId.isBlank()) {
            return McpToolResult(call.id, call.toolName, "Error: 'goal_id' requerido", isError = true)
        }
        if (revision <= 0) {
            return McpToolResult(call.id, call.toolName, "Error: 'revision' positiva requerida", isError = true)
        }

        val action = try {
            GoalAction.valueOf(rawAction)
        } catch (_: Exception) {
            return McpToolResult(call.id, call.toolName, "Error: accion no valida '" + rawAction + "'. Validas: edit, pause, resume, complete, blocked", isError = true)
        }

        val newObjective = if (args.has("objective")) args.getString("objective") else null
        val newMaxRounds = if (args.has("max_goal_rounds")) args.getInt("max_goal_rounds") else null
        val blockedReason = if (args.has("blocked_reason")) {
            GoalBlockReason(code = "model-blocked", message = args.getString("blocked_reason"))
        } else null

        val mutResult = engine.updateGoal(
            goalId = goalId,
            revision = revision,
            action = action,
            newObjective = newObjective,
            newMaxRounds = newMaxRounds,
            blockedReason = blockedReason
        )

        return when (mutResult) {
            is GoalMutationResult.Success -> {
                val res = JSONObject().apply {
                    put("success", true)
                    put("goal", mutResult.snapshot.toJson())
                    put("activation", mutResult.snapshot.activation.name.lowercase())
                }
                McpToolResult(call.id, call.toolName, res.toString())
            }
            is GoalMutationResult.Conflict -> {
                McpToolResult(call.id, call.toolName, "Conflicto CAS: revision esperada " + mutResult.expectedRevision + " pero la actual es " + mutResult.actualRevision + ". " + mutResult.message, isError = true)
            }
            is GoalMutationResult.Error -> {
                McpToolResult(call.id, call.toolName, mutResult.message, isError = true)
            }
        }
    }
}