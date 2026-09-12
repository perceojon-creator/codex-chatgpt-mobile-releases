package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoryMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-memory",
        name = "Local Memory & Knowledge Store",
        description = "Memoria persistente local en el teléfono para almacenar hechos, preferencias y directivas entre sesiones",
        iconEmoji = "🧠",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 4
    )

    private val memoryFile: File? = context?.let { File(it.filesDir, "mcp_memory.json") }
    private val memoryMap = linkedMapOf<String, String>()
    private val lock = Any()

    init {
        loadMemory()
    }

    private fun loadMemory() {
        val file = memoryFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isNotEmpty()) {
                val json = JSONObject(content)
                synchronized(lock) {
                    memoryMap.clear()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        memoryMap[k] = json.optString(k, "")
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted memory file
        }
    }

    private fun persistMemory() {
        val file = memoryFile ?: return
        try {
            val json = JSONObject()
            synchronized(lock) {
                for ((k, v) in memoryMap) {
                    json.put(k, v)
                }
            }
            val parent = file.parentFile ?: return
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(json.toString(2), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "save_memory",
            description = "Guarda un dato, preferencia o hecho importante de forma persistente en la memoria local del dispositivo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Identificador único de la memoria (ej. 'user_name', 'project_tech_stack')"))
                    put("value", JSONObject().put("type", "string").put("description", "Información detallada a recordar"))
                }
                put("properties", props)
                put("required", JSONArray().put("key").put("value"))
            }
        ),
        McpTool(
            name = "get_memory",
            description = "Recupera un dato específico almacenado previamente en la memoria local por su clave.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Clave del dato a consultar"))
                }
                put("properties", props)
                put("required", JSONArray().put("key"))
            }
        ),
        McpTool(
            name = "list_memories",
            description = "Lista todos los datos y preferencias actualmente recordados en la memoria persistente del teléfono.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "delete_memory",
            description = "Elimina un dato específico de la memoria permanente.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Clave a eliminar"))
                }
                put("properties", props)
                put("required", JSONArray().put("key"))
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try {
                JSONObject(call.argumentsJson)
            } catch (e: Exception) {
                JSONObject()
            }

            when (call.toolName) {
                "save_memory" -> {
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    if (key.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Error: 'key' no puede estar vacía", isError = true)
                    }
                    synchronized(lock) {
                        memoryMap[key] = value
                    }
                    persistMemory()
                    McpToolResult(call.id, call.toolName, "✅ Memoria guardada exitosamente: '$key' = '$value'")
                }
                "get_memory" -> {
                    val key = args.optString("key", "").trim()
                    val value = synchronized(lock) { memoryMap[key] }
                    if (value != null) {
                        McpToolResult(call.id, call.toolName, "Memoria encontrada [$key]: $value")
                    } else {
                        McpToolResult(call.id, call.toolName, "No se encontró ningún registro para la clave '$key'.")
                    }
                }
                "list_memories" -> {
                    val root = JSONObject()
                    val arr = JSONArray()
                    synchronized(lock) {
                        for ((k, v) in memoryMap) {
                            arr.put(JSONObject().apply {
                                put("key", k)
                                put("value", v)
                            })
                        }
                    }
                    root.put("total_records", arr.length())
                    root.put("records", arr)
                    McpToolResult(call.id, call.toolName, root.toString(2))
                }
                "delete_memory" -> {
                    val key = args.optString("key", "").trim()
                    val removed = synchronized(lock) { memoryMap.remove(key) != null }
                    if (removed) {
                        persistMemory()
                        McpToolResult(call.id, call.toolName, "✅ Memoria con clave '$key' eliminada correctamente.")
                    } else {
                        McpToolResult(call.id, call.toolName, "La clave '$key' no existía en memoria.")
                    }
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }
}
