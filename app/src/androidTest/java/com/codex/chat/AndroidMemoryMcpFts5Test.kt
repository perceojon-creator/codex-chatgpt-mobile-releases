package com.codex.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.MemoryMcpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AndroidMemoryMcpFts5Test {

    private lateinit var server: MemoryMcpServer

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Clean previous test sqlite db and json if present
        context.deleteDatabase("mcp_memory.sqlite")
        File(context.filesDir, "mcp_memory.json").delete()

        server = MemoryMcpServer(context)
    }

    @Test
    fun testRealDevice_Fts5_BM25_Search_And_Wal_Lifecycle() {
        assertNotNull("sqliteStore must be initialized on real Android device", server.sqliteStore)
        assertEquals("Server must expose 7 tools", 7, server.getTools().size)

        // 1. Guardar memorias complejas con categorías
        val call1 = server.executeTool(
            McpToolCallRequest(
                "call-1",
                "save_memory",
                JSONObject().apply {
                    put("key", "project_architecture")
                    put("value", "Arquitectura hexagonal con microservicios en Kotlin y DeepSeek Harness")
                    put("category", "arquitectura")
                }.toString()
            )
        )
        assertFalse(call1.isError)
        assertTrue(call1.content.contains("guardada exitosamente"))

        val call2 = server.executeTool(
            McpToolCallRequest(
                "call-2",
                "save_memory",
                JSONObject().apply {
                    put("key", "security_directive")
                    put("value", "ESTOP sentinel y redactor de secretos PII activado en todos los endpoints")
                    put("category", "seguridad")
                }.toString()
            )
        )
        assertFalse(call2.isError)

        val call3 = server.executeTool(
            McpToolCallRequest(
                "call-3",
                "save_memory",
                JSONObject().apply {
                    put("key", "user_preferences")
                    put("value", "El usuario prefiere respuestas concisas en español con métricas empíricas")
                    put("category", "preferencias")
                }.toString()
            )
        )
        assertFalse(call3.isError)

        // 2. Búsqueda de texto completo con FTS5 BM25
        val searchCall = server.executeTool(
            McpToolCallRequest(
                "call-search",
                "search_memory",
                JSONObject().apply {
                    put("query", "hexagonal Harness")
                    put("limit", 5)
                }.toString()
            )
        )
        assertFalse("searchCall failed: " + searchCall.content, searchCall.isError)
        android.util.Log.e("TEST_DEBUG", "searchCall.content = " + searchCall.content)
        val searchJson = JSONObject(searchCall.content)
        assertEquals("Content was: " + searchCall.content, 1, searchJson.getInt("total_matches"))
        val match = searchJson.getJSONArray("results").getJSONObject(0)
        assertEquals("project_architecture", match.getString("key"))
        assertEquals("arquitectura", match.getString("category"))
        assertTrue(match.has("snippet"))
        assertTrue(match.has("score_bm25"))

        // 3. Consultar estado del WAL
        val walCall = server.executeTool(
            McpToolCallRequest("call-wal", "wal_status", "{}")
        )
        assertFalse(walCall.isError)
        val walJson = JSONObject(walCall.content)
        assertEquals("wal", walJson.getString("journal_mode"))
        assertEquals(3, walJson.getInt("total_records"))
        assertTrue(walJson.has("wal_size_bytes"))

        // 4. Ejecutar truncamiento del WAL (PRAGMA wal_checkpoint)
        val truncateCall = server.executeTool(
            McpToolCallRequest("call-trunc", "wal_truncate", "{}")
        )
        assertFalse(truncateCall.isError)
        assertTrue(truncateCall.content.contains("ejecutado exitosamente"))

        // 5. Eliminar memoria y comprobar actualización en FTS5
        val delCall = server.executeTool(
            McpToolCallRequest(
                "call-del",
                "delete_memory",
                JSONObject().apply { put("key", "project_architecture") }.toString()
            )
        )
        assertFalse(delCall.isError)

        val searchAfterDel = server.executeTool(
            McpToolCallRequest(
                "call-search2",
                "search_memory",
                JSONObject().apply { put("query", "hexagonal") }.toString()
            )
        )
        assertFalse(searchAfterDel.isError)
        val searchAfterDelJson = JSONObject(searchAfterDel.content)
        assertEquals(0, searchAfterDelJson.getInt("total_matches"))
    }
}
