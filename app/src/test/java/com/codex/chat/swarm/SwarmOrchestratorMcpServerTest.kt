package com.codex.chat.swarm

import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.SwarmOrchestratorMcpServer
import com.codex.chat.core.swarm.engine.SwarmAgentDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 2: Pruebas del servidor de herramientas MCP del enjambre.
 */
class SwarmOrchestratorMcpServerTest {

    private lateinit var dispatcher: SwarmAgentDispatcher
    private lateinit var server: SwarmOrchestratorMcpServer

    @Before
    fun setUp() {
        dispatcher = SwarmAgentDispatcher(maxConcurrency = 4)
        server = SwarmOrchestratorMcpServer(dispatcher)
    }

    @After
    fun tearDown() {
        dispatcher.shutdown()
    }

    @Test
    fun servidor_declara_las_tres_herramientas_con_esquemas_validos() {
        val tools = server.getTools()
        assertEquals(3, tools.size)

        val names = tools.map { it.name }.toSet()
        assertTrue(names.contains("spawn_worker"))
        assertTrue(names.contains("await_workers"))
        assertTrue(names.contains("get_worker_status"))

        for (t in tools) {
            assertEquals("object", t.inputSchema.getString("type"))
            assertTrue(t.inputSchema.has("properties"))
            assertTrue(t.inputSchema.has("required"))
        }
    }

    @Test
    fun spawn_worker_retorna_ticket_valido_en_json() {
        val args = JSONObject().apply {
            put("role", "SENSOR_OS")
            put("task_prompt", "Comprueba nivel de bateria")
            put("timeout_sec", 15)
        }

        val req = McpToolCallRequest(id = "call-1", toolName = "spawn_worker", argumentsJson = args.toString())
        val result = server.executeTool(req)

        assertFalse(result.isError)
        val json = JSONObject(result.content)
        assertTrue(json.has("ticket_id"))
        assertTrue(json.getString("ticket_id").startsWith("ticket_"))
        assertEquals("SENSOR_OS", json.getString("role"))
        assertEquals("QUEUED", json.getString("status"))
    }

    @Test
    fun await_workers_recolecta_multiples_resultados() {
        val spawn1 = server.executeTool(
            McpToolCallRequest(
                id = "c1", toolName = "spawn_worker",
                argumentsJson = JSONObject().put("role", "SENSOR_OS").put("task_prompt", "Tarea 1").toString()
            )
        )
        val spawn2 = server.executeTool(
            McpToolCallRequest(
                id = "c2", toolName = "spawn_worker",
                argumentsJson = JSONObject().put("role", "CODE_COMPUTE").put("task_prompt", "Tarea 2").toString()
            )
        )

        val ticket1 = JSONObject(spawn1.content).getString("ticket_id")
        val ticket2 = JSONObject(spawn2.content).getString("ticket_id")

        val awaitArgs = JSONObject().apply {
            put("ticket_ids", JSONArray().put(ticket1).put(ticket2))
            put("timeout_sec", 5)
        }
        val awaitReq = McpToolCallRequest(id = "c3", toolName = "await_workers", argumentsJson = awaitArgs.toString())
        val awaitResult = server.executeTool(awaitReq)

        assertFalse(awaitResult.isError)
        val resArr = JSONArray(awaitResult.content)
        assertEquals(2, resArr.length())
        val res1 = resArr.getJSONObject(0)
        val res2 = resArr.getJSONObject(1)
        assertTrue(res1.has("status"))
        assertTrue(res2.has("status"))
    }

    @Test
    fun spawn_worker_con_rol_invalido_devuelve_error() {
        val args = JSONObject().apply {
            put("role", "ROL_INEXISTENTE")
            put("task_prompt", "Invalido")
        }
        val req = McpToolCallRequest(id = "c4", toolName = "spawn_worker", argumentsJson = args.toString())
        val result = server.executeTool(req)
        assertTrue(result.isError)
        assertTrue(result.content.contains("Rol no valido", ignoreCase = true) || result.content.contains("Error", ignoreCase = true))
    }

    @Test
    fun get_worker_status_retorna_estado() {
        val spawn = server.executeTool(
            McpToolCallRequest(
                id = "c5", toolName = "spawn_worker",
                argumentsJson = JSONObject().put("role", "MULTIMEDIA").put("task_prompt", "Generar").toString()
            )
        )
        val ticket = JSONObject(spawn.content).getString("ticket_id")

        val statusReq = McpToolCallRequest(
            id = "c6", toolName = "get_worker_status",
            argumentsJson = JSONObject().put("ticket_id", ticket).toString()
        )
        val statusRes = server.executeTool(statusReq)
        assertFalse(statusRes.isError)
        val json = JSONObject(statusRes.content)
        assertTrue(json.has("status"))
    }
}