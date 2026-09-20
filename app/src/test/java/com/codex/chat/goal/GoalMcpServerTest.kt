package com.codex.chat.goal

import com.codex.chat.core.goal.engine.GoalEngine
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.GoalMcpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Fase 3: Pruebas unitarias del servidor de herramientas MCP para Objetivos.
 */
class GoalMcpServerTest {

    private lateinit var engine: GoalEngine
    private lateinit var server: GoalMcpServer

    @Before
    fun setUp() {
        engine = GoalEngine()
        server = GoalMcpServer(engine)
    }

    @Test
    fun servidor_declara_las_tres_herramientas_de_objetivos() {
        val tools = server.getTools()
        assertEquals(3, tools.size)
        val names = tools.map { it.name }.toSet()
        assertTrue(names.contains("create_goal"))
        assertTrue(names.contains("get_goal"))
        assertTrue(names.contains("update_goal"))
    }

    @Test
    fun create_goal_mcp_retorna_json_con_ref_y_fase_active() {
        val args = JSONObject().apply {
            put("objective", "Auditar y endurecer ProGuard")
            put("max_goal_rounds", 8)
        }
        val req = McpToolCallRequest("c1", "create_goal", args.toString())
        val res = server.executeTool(req)

        assertFalse(res.isError)
        val json = JSONObject(res.content)
        assertTrue(json.has("goal"))
        val goalObj = json.getJSONObject("goal")
        assertEquals(1, goalObj.getInt("revision"))
        assertEquals("Auditar y endurecer ProGuard", goalObj.getString("objective"))
        assertEquals("active", goalObj.getString("phase"))
        assertEquals("armed", json.getString("activation"))
    }

    @Test
    fun get_goal_mcp_retorna_null_si_no_hay_objetivo_creado() {
        val req = McpToolCallRequest("c2", "get_goal", "{}")
        val res = server.executeTool(req)
        assertFalse(res.isError)
        val json = JSONObject(res.content)
        assertTrue(json.isNull("goal"))
        assertEquals("disarmed", json.getString("activation"))
    }

    @Test
    fun update_goal_mcp_permite_marcar_complete_con_revision_correcta() {
        // 1. Crear
        val createRes = server.executeTool(McpToolCallRequest("c3", "create_goal", "{\"objective\":\"Meta\"}"))
        val goalId = JSONObject(createRes.content).getJSONObject("goal").getString("id")

        // 2. Update a complete con revision 1
        val updateArgs = JSONObject().apply {
            put("goal_id", goalId)
            put("revision", 1)
            put("action", "complete")
        }
        val updateRes = server.executeTool(McpToolCallRequest("c4", "update_goal", updateArgs.toString()))
        assertFalse(updateRes.isError)
        val updateJson = JSONObject(updateRes.content)
        assertEquals("complete", updateJson.getJSONObject("goal").getString("phase"))
        assertEquals(2, updateJson.getJSONObject("goal").getInt("revision"))
    }

    @Test
    fun update_goal_mcp_falla_con_error_si_hay_conflicto_cas() {
        val createRes = server.executeTool(McpToolCallRequest("c5", "create_goal", "{\"objective\":\"Meta\"}"))
        val goalId = JSONObject(createRes.content).getJSONObject("goal").getString("id")

        // Intento con revision 999 desfasada
        val updateArgs = JSONObject().apply {
            put("goal_id", goalId)
            put("revision", 999)
            put("action", "complete")
        }
        val updateRes = server.executeTool(McpToolCallRequest("c6", "update_goal", updateArgs.toString()))
        assertTrue("Debe reportar error ante conflicto CAS", updateRes.isError)
        assertTrue(updateRes.content.contains("Conflicto CAS"))
    }
}