package com.codex.chat.core.mcp

import android.content.Context
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.mcp.server.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

class McpRegistry(private val context: Context? = null) {

    private val servers = mutableListOf<McpServer>()
    private val configFile: File? = context?.let { File(it.filesDir, "mcp_servers.json") }
    private val lock = Any()

    private val officialCatalog = mutableListOf<OfficialMcpServerInfo>()
    val quarantineRegistry = ToolQuarantineRegistry.getInstance()

    init {
        registerBuiltInServers()
        loadRemoteServers()
        loadOfficialCatalog()
    }

    private fun loadOfficialCatalog() {
        if (context == null) return
        try {
            val jsonString = context.assets.open("official_mcp_servers.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", "")
                val name = obj.optString("name", "")
                val desc = obj.optString("description", "")
                val icon = obj.optString("iconEmoji", "🔌")
                val author = obj.optString("author", "Anthropic")
                val cat = obj.optString("category", "General")
                val defaultUrl = obj.optString("defaultUrl", "")
                val toolsArr = obj.optJSONArray("tools") ?: JSONArray()
                val toolList = mutableListOf<McpTool>()
                for (j in 0 until toolsArr.length()) {
                    val tObj = toolsArr.optJSONObject(j) ?: continue
                    toolList.add(McpTool(tObj.optString("name"), tObj.optString("description"), name))
                }
                if (id.isNotEmpty() && name.isNotEmpty()) {
                    officialCatalog.add(
                        OfficialMcpServerInfo(id, name, desc, icon, author, cat, defaultUrl, toolList)
                    )
                }
            }
        } catch (e: Exception) {
            // Ignore asset missing in tests
        }
    }

    fun getOfficialCatalog(): List<OfficialMcpServerInfo> = officialCatalog.toList()

    private fun registerBuiltInServers() {
        synchronized(lock) {
            servers.add(DeviceMcpServer(context))
            servers.add(MemoryMcpServer(context))
            servers.add(FileSystemMcpServer(context))
            servers.add(ClipboardMcpServer(context))
            servers.add(CalculatorMcpServer())
            servers.add(NetworkMcpServer())
            servers.add(PersonalDataMcpServer(context))
            servers.add(TelephonySmsMcpServer(context))
            servers.add(SystemSettingsMcpServer(context))
            servers.add(RootMcpServer(context))
            servers.add(E2bCloudMcpServer(context))
            servers.add(WebSearchMcpServer(context))
        }
    }

    private fun loadRemoteServers() {
        val file = configFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isEmpty()) return
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val id = obj.optString("id", UUID.randomUUID().toString())
                val name = obj.optString("name", "Servidor MCP")
                val url = obj.optString("url", "")
                val token = obj.optString("token", "").ifBlank { null }
                val isEnabled = obj.optBoolean("is_enabled", true)
                if (url.isNotBlank()) {
                    val s = RemoteHttpMcpServer(id, name, url, token)
                    s.info.isEnabled = isEnabled
                    synchronized(lock) {
                        servers.add(s)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
    }

    private fun saveRemoteServers() {
        val file = configFile ?: return
        try {
            val array = JSONArray()
            synchronized(lock) {
                for (s in servers) {
                    if (s is RemoteHttpMcpServer) {
                        array.put(JSONObject().apply {
                            put("id", s.info.id)
                            put("name", s.info.name)
                            put("url", s.endpointUrl)
                            put("token", s.authToken ?: "")
                            put("is_enabled", s.info.isEnabled)
                        })
                    }
                }
            }
            val parent = file.parentFile ?: return
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(array.toString(2), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    fun getServers(): List<McpServerInfo> {
        synchronized(lock) {
            return servers.map { s ->
                val count = try { s.getTools().size } catch (e: Exception) { 0 }
                s.info.copy(toolsCount = count)
            }
        }
    }

    fun getAllActiveTools(): List<McpTool> {
        val list = mutableListOf<McpTool>()
        synchronized(lock) {
            for (s in servers) {
                if (s.info.isEnabled) {
                    try {
                        list.addAll(s.getTools())
                    } catch (e: Exception) {
                        // Skip unreachable server
                    }
                }
            }
        }
        // Filtrar herramientas en autocuarentena para no contaminar el esquema del modelo
        return list.filter { tool -> !quarantineRegistry.isQuarantined(tool.name) }
    }

    fun setServerEnabled(serverId: String, enabled: Boolean) {
        synchronized(lock) {
            val server = servers.find { it.info.id == serverId }
            server?.info?.isEnabled = enabled
        }
        saveRemoteServers()
    }

    fun addRemoteServer(name: String, url: String, token: String? = null): McpServerInfo {
        val id = "remote-mcp-" + System.currentTimeMillis()
        val server = RemoteHttpMcpServer(id, name, url, token)
        synchronized(lock) {
            servers.add(server)
        }
        saveRemoteServers()
        return server.info
    }

    fun removeServer(serverId: String): Boolean {
        val removed = synchronized(lock) {
            val s = servers.find { it.info.id == serverId && it is RemoteHttpMcpServer }
            if (s != null) {
                servers.remove(s)
            } else false
        }
        if (removed) saveRemoteServers()
        return removed
    }

    fun servidorDe(toolName: String): String? {
        synchronized(lock) {
            for (s in servers) {
                if (!s.info.isEnabled) continue
                val tiene = try {
                    s.getTools().any { it.name.equals(toolName, ignoreCase = true) }
                } catch (e: Exception) { false }
                if (tiene) return s.info.name
            }
        }
        return null
    }

    fun executeTool(toolName: String, argumentsJson: String = "{}"): McpToolResult {
        return executeToolWithCallId("call-" + UUID.randomUUID().toString().take(8), toolName, argumentsJson)
    }

    /**
     * Ejecuta una herramienta preservando el tool_call_id ORIGINAL emitido por el modelo.
     * Esto es imprescindible para el protocolo OpenAI function-calling: la respuesta
     * role:"tool" DEBE referenciar el mismo id, o el servidor rechaza el turno completo.
     */
    fun executeToolWithCallId(callId: String, toolName: String, argumentsJson: String = "{}"): McpToolResult {
        // 1. Verificación de compuerta de Autocuarentena
        val (canRun, quarantineReason) = quarantineRegistry.canExecute(toolName)
        if (!canRun) {
            return McpToolResult(
                callId = callId,
                toolName = toolName,
                content = quarantineReason ?: "[AUTO_QUARANTINE]: Herramienta '$toolName' en cuarentena preventiva.",
                isError = true
            )
        }

        val call = McpToolCallRequest(
            id = callId,
            toolName = toolName,
            argumentsJson = argumentsJson
        )

        var targetServer: McpServer? = null
        synchronized(lock) {
            for (s in servers) {
                if (s.info.isEnabled) {
                    val hasTool = try { s.getTools().any { it.name.equals(toolName, ignoreCase = true) } } catch (e: Exception) { false }
                    if (hasTool) {
                        targetServer = s
                        break
                    }
                }
            }
        }

        if (targetServer == null) {
            quarantineRegistry.recordExecution(toolName, isSuccess = false, durationMs = 0L, errorMessage = "Herramienta no encontrada")
            return McpToolResult(call.id, toolName, "No se encontró ninguna herramienta activa con nombre '$toolName'.", isError = true)
        }

        val start = System.currentTimeMillis()
        val result = try {
            targetServer!!.executeTool(call)
        } catch (e: Throwable) {
            val dur = System.currentTimeMillis() - start
            quarantineRegistry.recordExecution(toolName, isSuccess = false, durationMs = dur, errorMessage = e.message)
            return McpToolResult(call.id, toolName, "Excepción ejecutando herramienta '$toolName': ${e.message}", isError = true)
        }
        val dur = System.currentTimeMillis() - start

        // 2. Telemetría y actualización del ciclo de autocuarentena
        quarantineRegistry.recordExecution(
            toolName = toolName,
            isSuccess = !result.isError,
            durationMs = dur,
            errorMessage = if (result.isError) result.content else null
        )

        return result
    }

    fun buildMcpSystemPromptSummary(): String {
        val tools = getAllActiveTools()
        if (tools.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("### Active Native Mobile MCP Servers (Model Context Protocol):\n")
        sb.append("Tienes acceso directo y nativo a las siguientes herramientas en el dispositivo Android:\n")
        for (t in tools) {
            sb.append("- **").append(t.name).append("**: ").append(t.description).append(" [Servidor: ").append(t.serverName).append("]\n")
        }
        sb.append("\nInstrucciones de uso de herramientas:\n")
        sb.append("- Si el usuario te pide datos del teléfono (batería, almacenamiento, portapapeles, archivos locales o memoria persistente), responde utilizando los datos o indicando la herramienta MCP adecuada.\n\n")
        return sb.toString()
    }
}
