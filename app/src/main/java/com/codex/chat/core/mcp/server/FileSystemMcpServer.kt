package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileSystemMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-filesystem",
        name = "Local App Filesystem",
        description = "Exploración y manipulación segura de archivos y documentos de código en el almacenamiento local del teléfono",
        iconEmoji = "📁",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 4
    )

    private val workspaceDir: File by lazy {
        val base = context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "android_mcp_workspace")
        val ws = File(base, "mcp_workspace")
        if (!ws.exists()) ws.mkdirs()
        ws
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_files",
            description = "Lista los archivos disponibles en el espacio de trabajo local del dispositivo móvil.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "read_file",
            description = "Lee el contenido de un archivo de texto en el espacio de trabajo móvil.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("file_name", JSONObject().put("type", "string").put("description", "Nombre del archivo a leer (ej. 'notas.txt', 'script.py')"))
                }
                put("properties", props)
                put("required", JSONArray().put("file_name"))
            }
        ),
        McpTool(
            name = "write_file",
            description = "Crea o sobrescribe un archivo de texto en el espacio de trabajo local del teléfono.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("file_name", JSONObject().put("type", "string").put("description", "Nombre del archivo"))
                    put("content", JSONObject().put("type", "string").put("description", "Contenido textual completo a escribir"))
                }
                put("properties", props)
                put("required", JSONArray().put("file_name").put("content"))
            }
        ),
        McpTool(
            name = "delete_file",
            description = "Elimina un archivo del espacio de trabajo local.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("file_name", JSONObject().put("type", "string").put("description", "Nombre del archivo a borrar"))
                }
                put("properties", props)
                put("required", JSONArray().put("file_name"))
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
                "list_files" -> {
                    val files = workspaceDir.listFiles() ?: emptyArray()
                    val arr = JSONArray()
                    for (f in files) {
                        arr.put(JSONObject().apply {
                            put("name", f.name)
                            put("size_bytes", f.length())
                            put("is_directory", f.isDirectory)
                            put("last_modified", f.lastModified())
                        })
                    }
                    val res = JSONObject().apply {
                        put("workspace_path", workspaceDir.absolutePath)
                        put("file_count", files.size)
                        put("files", arr)
                    }
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "read_file" -> {
                    val fileName = args.optString("file_name", "").trim()
                    if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                        return McpToolResult(call.id, call.toolName, "Nombre de archivo inválido o intento de path traversal", isError = true)
                    }
                    val target = File(workspaceDir, fileName)
                    if (!target.exists() || !target.isFile) {
                        return McpToolResult(call.id, call.toolName, "El archivo '$fileName' no existe.", isError = true)
                    }
                    val content = target.readText(Charsets.UTF_8)
                    McpToolResult(call.id, call.toolName, content)
                }
                "write_file" -> {
                    val fileName = args.optString("file_name", "").trim()
                    val content = args.optString("content", "")
                    if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                        return McpToolResult(call.id, call.toolName, "Nombre de archivo inválido o intento de path traversal", isError = true)
                    }
                    val target = File(workspaceDir, fileName)
                    target.writeText(content, Charsets.UTF_8)
                    McpToolResult(call.id, call.toolName, "✅ Archivo '$fileName' guardado correctamente (${content.length} caracteres, ${target.length()} bytes).")
                }
                "delete_file" -> {
                    val fileName = args.optString("file_name", "").trim()
                    if (fileName.isEmpty() || fileName.contains("..") || fileName.contains("/") || fileName.contains("\\")) {
                        return McpToolResult(call.id, call.toolName, "Nombre de archivo inválido", isError = true)
                    }
                    val target = File(workspaceDir, fileName)
                    if (!target.exists()) {
                        return McpToolResult(call.id, call.toolName, "El archivo '$fileName' no existe.")
                    }
                    val deleted = target.delete()
                    if (deleted) {
                        McpToolResult(call.id, call.toolName, "✅ Archivo '$fileName' eliminado correctamente.")
                    } else {
                        McpToolResult(call.id, call.toolName, "No se pudo eliminar el archivo '$fileName'.", isError = true)
                    }
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }
}
