package com.codex.chat.mcp

import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.HtmlSandboxMcpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HtmlSandboxMcpServerTest {

    private lateinit var sandboxServer: HtmlSandboxMcpServer

    @Before
    fun setUp() {
        sandboxServer = HtmlSandboxMcpServer(context = null)
    }

    @Test
    fun testServerMetadataAndTools() {
        assertEquals("mcp-android-html-sandbox", sandboxServer.info.id)
        assertEquals("HTML & Live Web Sandbox", sandboxServer.info.name)
        assertTrue(sandboxServer.info.isEnabled)

        val tools = sandboxServer.getTools()
        assertEquals(2, tools.size)

        val toolNames = tools.map { it.name }
        assertTrue("Debe incluir test_html_code", toolNames.contains("test_html_code"))
        assertTrue("Debe incluir inspect_html_dom", toolNames.contains("inspect_html_dom"))
    }

    @Test
    fun testEmptyHtmlFailsGracefully() {
        val call = McpToolCallRequest(
            id = "call-1",
            toolName = "test_html_code",
            argumentsJson = JSONObject().put("html", "").toString()
        )
        val result = sandboxServer.executeTool(call)
        assertTrue("Debe reportar error con HTML vacío", result.isError)
        assertTrue(result.content.contains("no puede estar vacío"))
    }

    @Test
    fun testOfflineContextReturnsValidSyntheticReport() {
        val sampleHtml = "<!DOCTYPE html><html><body><canvas id='c'></canvas><button>Click</button></body></html>"
        val call = McpToolCallRequest(
            id = "call-2",
            toolName = "test_html_code",
            argumentsJson = JSONObject().put("html", sampleHtml).toString()
        )
        val result = sandboxServer.executeTool(call)
        assertFalse("En contexto nulo no debe crashear", result.isError)
        assertTrue("Debe contener reporte de verificación", result.content.contains("HTML Sandbox Verification Passed"))
        assertEquals("call-2", result.callId)
    }

    @Test
    fun testInspectHtmlDomToolExecution() {
        val sampleHtml = "<div><h1>Titulo</h1></div>"
        val call = McpToolCallRequest(
            id = "call-3",
            toolName = "inspect_html_dom",
            argumentsJson = JSONObject().apply {
                put("html", sampleHtml)
                put("eval_js", "document.querySelector('h1').innerText")
            }.toString()
        )
        val result = sandboxServer.executeTool(call)
        assertFalse(result.isError)
        assertTrue(result.content.contains("HTML Sandbox Verification Passed"))
    }

    @Test
    fun testReportFormattingForAgentOnFailure() {
        val report = HtmlSandboxMcpServer.SandboxReport(
            success = false,
            errors = listOf("ReferenceError: undefinedVariable is not defined"),
            logs = listOf("Iniciando script..."),
            loadTimeMs = 35L,
            evalResult = null,
            domSummary = "0 canvas"
        )
        val formatted = report.formatForAgent()
        assertTrue("Debe indicar que la verificación falló", formatted.contains("HTML Sandbox Verification Failed"))
        assertTrue("Debe incluir el error de JS", formatted.contains("ReferenceError: undefinedVariable is not defined"))
        assertTrue("Debe incluir instrucción de auto-corrección", formatted.contains("Instrucción de auto-corrección"))
    }
}
