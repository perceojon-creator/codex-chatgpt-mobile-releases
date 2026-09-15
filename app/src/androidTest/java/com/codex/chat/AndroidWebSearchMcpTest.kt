package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.WebSearchMcpServer
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidWebSearchMcpTest {

    private lateinit var registry: McpRegistry
    private lateinit var webServer: WebSearchMcpServer

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        registry = McpRegistry(context)
        webServer = WebSearchMcpServer(context)
    }

    @Test
    fun testWebSearchToolDefinition() {
        val tools = webServer.getTools()
        assertEquals(2, tools.size)

        val searchTool = tools.find { it.name == "web_search" }
        assertNotNull("web_search tool debe existir", searchTool)
        assertEquals("Web Search & Fetch", searchTool!!.serverName)
        assertTrue(searchTool.inputSchema.has("properties"))
        val required = searchTool.inputSchema.getJSONArray("required")
        assertEquals("query", required.getString(0))

        val fetchTool = tools.find { it.name == "fetch_web_page" }
        assertNotNull("fetch_web_page tool debe existir", fetchTool)
        val fetchReq = fetchTool!!.inputSchema.getJSONArray("required")
        assertEquals("url", fetchReq.getString(0))
    }

    @Test
    fun testWebSearchEmptyQueryFailsGracefully() {
        val call = McpToolCallRequest(
            id = "call-web-1",
            toolName = "web_search",
            argumentsJson = "{\"query\": \"   \"}"
        )
        val result = webServer.executeTool(call)
        assertTrue("Consulta vacía debe marcar isError", result.isError)
        assertTrue(result.content.contains("no puede estar vacía"))
    }

    @Test
    fun testFetchWebPageInvalidUrl() {
        val call = McpToolCallRequest(
            id = "call-fetch-1",
            toolName = "fetch_web_page",
            argumentsJson = "{\"url\": \"ftp://invalid-protocol.com\"}"
        )
        val result = webServer.executeTool(call)
        assertTrue("Protocolo no http(s) debe fallar", result.isError)
        assertTrue(result.content.contains("http:// o https://"))
    }

    @Test
    fun testMcpRegistryActiveToolsIncludeWebSearch() {
        val activeTools = registry.getAllActiveTools()
        val foundSearch = activeTools.find { it.name == "web_search" }
        assertNotNull("web_search debe estar registrado en McpRegistry.getAllActiveTools()", foundSearch)

        val foundFetch = activeTools.find { it.name == "fetch_web_page" }
        assertNotNull("fetch_web_page debe estar registrado en McpRegistry.getAllActiveTools()", foundFetch)
    }

    @Test
    fun testRegistryExecuteWebSearchWithCallIdPreservation() {
        val callId = "call-ddg-987654"
        val result = registry.executeToolWithCallId(
            callId = callId,
            toolName = "web_search",
            argumentsJson = "{\"query\": \"kotlin android\", \"max_results\": 2}"
        )

        assertEquals("El callId original debe preservarse para OpenAI function calling", callId, result.callId)
        assertEquals("web_search", result.toolName)
        assertFalse("No debe reportar error fatal", result.isError)
        assertTrue("Debe retornar resultados o confirmación de búsqueda", result.content.isNotEmpty())
    }
}
