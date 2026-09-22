package com.codex.chat.mcp

import com.codex.chat.core.concurrency.ToolBatchExecutor
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.model.McpServerType
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.TermuxMcpServer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class TermuxMcpServerTest {

    @Test
    fun testServerMetadata() {
        val server = TermuxMcpServer(null)
        assertEquals("mcp-termux", server.info.id)
        assertEquals("Termux Linux Environment", server.info.name)
        assertEquals("🐧", server.info.iconEmoji)
        assertEquals(McpServerType.NATIVE, server.info.type)
        assertEquals(10, server.info.toolsCount)
    }

    @Test
    fun testToolsInventory() {
        val server = TermuxMcpServer(null)
        val tools = server.getTools()
        assertEquals(10, tools.size)

        val names = tools.map { it.name }
        assertTrue(names.contains("termux_execute_bash"))
        assertTrue(names.contains("termux_execute_command"))
        assertTrue(names.contains("termux_read_terminal_screen"))
        assertTrue(names.contains("termux_send_keys"))
        assertTrue(names.contains("termux_list_sessions"))
        assertTrue(names.contains("termux_pkg_manage"))
        assertTrue(names.contains("termux_pkg_install"))
        assertTrue(names.contains("termux_read_file"))
        assertTrue(names.contains("termux_write_file"))
        assertTrue(names.contains("termux_get_environment"))
    }

    @Test
    fun testGetEnvironmentReturnsStructuredTelemetry() {
        val server = TermuxMcpServer(null)
        val call = McpToolCallRequest("c-env", "termux_get_environment", "{}")
        val result = server.executeTool(call)

        assertFalse("Result should not be error: " + result.content, result.isError)
        val json = JSONObject(result.content)
        assertEquals("com.termux", json.getString("package"))
        assertEquals("/data/data/com.termux/files/usr", json.getString("prefix_path"))
        assertEquals("/data/data/com.termux/files/home", json.getString("home_path"))
        assertTrue(json.has("is_installed"))
        assertTrue(json.has("http_bridge_status"))
    }

    @Test
    fun testExecuteCommandRequiresCommandParameter() {
        val server = TermuxMcpServer(null)
        val call = McpToolCallRequest("c-cmd", "termux_execute_command", "{}")
        val result = server.executeTool(call)

        assertTrue(result.isError)
        assertTrue(result.content.contains("obligatorio"))
    }

    @Test
    fun testReadFileRequiresFilePathParameter() {
        val server = TermuxMcpServer(null)
        val call = McpToolCallRequest("c-read", "termux_read_file", "{}")
        val result = server.executeTool(call)

        assertTrue(result.isError)
        assertTrue(result.content.contains("obligatorio"))
    }

    @Test
    fun testWriteFileAndReadDirectly() {
        val tempDir = File(System.getProperty("java.io.tmpdir"), "termux_test_" + System.currentTimeMillis()).apply { mkdirs() }
        val tempFile = File(tempDir, "script.py")

        val server = TermuxMcpServer(null)
        val writeArgs = JSONObject().apply {
            put("file_path", tempFile.absolutePath)
            put("content", "print('Hello Termux')")
            put("executable", true)
        }
        val writeCall = McpToolCallRequest("c-write", "termux_write_file", writeArgs.toString())
        val writeResult = server.executeTool(writeCall)

        assertFalse(writeResult.isError)
        assertTrue(tempFile.exists())
        assertEquals("print('Hello Termux')", tempFile.readText())

        // Ahora leerlo con termux_read_file
        val readArgs = JSONObject().apply {
            put("file_path", tempFile.absolutePath)
        }
        val readCall = McpToolCallRequest("c-read", "termux_read_file", readArgs.toString())
        val readResult = server.executeTool(readCall)

        assertFalse(readResult.isError)
        assertEquals("print('Hello Termux')", readResult.content)

        tempDir.deleteRecursively()
    }

    @Test
    fun testPkgInstallRequiresPackageName() {
        val server = TermuxMcpServer(null)
        val call = McpToolCallRequest("c-pkg", "termux_pkg_install", "{}")
        val result = server.executeTool(call)

        assertTrue(result.isError)
        assertTrue(result.content.contains("obligatorio"))
    }

    @Test
    fun testTermuxToolsConcurrencyInBatchExecutor() {
        val registry = McpRegistry(null)
        val executor = ToolBatchExecutor(registry)

        // Consultas de entorno y lectura son concurrentes (paralelas)
        assertTrue(executor.canRunConcurrently("termux_get_environment"))
        assertTrue(executor.canRunConcurrently("termux_read_file"))

        // Ejecución de comandos y mutaciones son estrictamente secuenciales
        assertFalse(executor.canRunConcurrently("termux_execute_command"))
        assertFalse(executor.canRunConcurrently("termux_write_file"))
        assertFalse(executor.canRunConcurrently("termux_pkg_install"))
    }

    @Test
    fun testServerRegisteredInMcpRegistry() {
        val registry = McpRegistry(null)
        val termuxServer = registry.getServers().firstOrNull { it.id == "mcp-termux" }
        assertNotNull("TermuxMcpServer debe estar registrado en McpRegistry", termuxServer)
        assertEquals("Termux Linux Environment", termuxServer!!.name)
    }
}