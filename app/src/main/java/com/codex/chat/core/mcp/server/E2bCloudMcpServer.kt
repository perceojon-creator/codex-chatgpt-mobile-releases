package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.network.DirectE2BClient
import org.json.JSONArray
import org.json.JSONObject

class E2bCloudMcpServer(
    private val context: Context? = null,
    private val directE2bClient: DirectE2BClient = DirectE2BClient()
) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-cloud-e2b",
        name = "E2B Cloud Code Interpreter",
        description = "Entorno sandbox MicroVM en la nube para ejecutar código Python, Node.js, Bash y scripts multilinguaje",
        iconEmoji = "⚡",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 2
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "execute_python",
            description = "Ejecuta código Python 3 (con soporte para numpy, pandas, matplotlib, scipy, etc.) en un sandbox MicroVM en la nube (E2B Cloud).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("code", JSONObject().put("type", "string").put("description", "Código Python a ejecutar"))
                }
                put("properties", props)
                put("required", JSONArray().put("code"))
            }
        ),
        McpTool(
            name = "execute_sandbox_command",
            description = "Ejecuta comandos de shell (Bash, Linux), Node.js, C/C++ o scripts en otros lenguajes en el contenedor E2B.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("command", JSONObject().put("type", "string").put("description", "Comando de shell o código a ejecutar"))
                    put("language", JSONObject().put("type", "string").put("description", "Lenguaje o entorno: 'bash' (por defecto), 'python', 'javascript', 'node'"))
                }
                put("properties", props)
                put("required", JSONArray().put("command"))
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
            when (call.toolName) {
                "execute_python" -> {
                    val code = args.optString("code", "").trim()
                    if (code.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el código Python a ejecutar", isError = true)
                    }
                    val res = runPython(code)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "execute_sandbox_command" -> {
                    val cmd = args.optString("command", "").trim()
                    val lang = args.optString("language", "bash").lowercase().trim()
                    if (cmd.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el comando o código a ejecutar", isError = true)
                    }
                    val res = runPolyglot(cmd, lang)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun runPython(code: String): JSONObject {
        val root = JSONObject()
        val apiKey = DirectE2BClient.DEFAULT_API_KEY
        val sessionId = "mobile_mcp_session"

        val result = directE2bClient.executePython(
            apiKey = apiKey,
            sessionId = sessionId,
            code = code
        )

        root.put("success", result.success)
        root.put("stdout", result.stdout)
        root.put("stderr", result.stderr)
        if (result.error != null) {
            root.put("error", result.error)
        }
        root.put("sandbox_id", result.sandboxId)
        return root
    }

    private fun runPolyglot(command: String, language: String): JSONObject {
        // In E2B Jupyter kernel, Bash and other languages can be executed seamlessly via subprocess or magic commands
        val pythonWrapper = when (language) {
            "bash", "sh", "shell" -> {
                """
import subprocess
p = subprocess.run('''$command''', shell=True, capture_output=True, text=True)
if p.stdout:
    print(p.stdout, end='')
if p.stderr:
    import sys
    sys.stderr.write(p.stderr)
                """.trimIndent()
            }
            "javascript", "js", "node" -> {
                """
import subprocess
p = subprocess.run(['node', '-e', '''$command'''], capture_output=True, text=True)
if p.stdout:
    print(p.stdout, end='')
if p.stderr:
    import sys
    sys.stderr.write(p.stderr)
                """.trimIndent()
            }
            else -> command
        }
        return runPython(pythonWrapper)
    }
}
