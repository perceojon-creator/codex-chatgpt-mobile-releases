package com.codex.chat.core.mcp.server

import android.content.Context
import android.os.Build
import android.os.Environment
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class FileSystemMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-filesystem",
        name = "Local App & Device Filesystem",
        description = "Exploración, lectura y creación de archivos en el almacenamiento interno de la app y almacenamiento del dispositivo (Download, Documents, etc.)",
        iconEmoji = "📁",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 6
    )

    private val workspaceDir: File by lazy {
        val base = context?.filesDir ?: File(System.getProperty("java.io.tmpdir"), "android_mcp_workspace")
        val ws = File(base, "mcp_workspace")
        if (!ws.exists()) ws.mkdirs()
        ws
    }

    private fun getSharedStorageDir(): File {
        return try {
            Environment.getExternalStorageDirectory()
        } catch (e: Throwable) {
            File(System.getProperty("java.io.tmpdir"), "android_shared_storage").apply { mkdirs() }
        }
    }

    private fun hasAllFilesPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                Environment.isExternalStorageManager()
            } catch (e: Throwable) {
                false
            }
        } else {
            true
        }
    }

    private fun resolveTargetFile(rawPath: String, createParentDirs: Boolean = false): File {
        val clean = rawPath.trim()
        if (clean.contains("..") || clean.startsWith("/system") || clean.startsWith("/data/data") || clean.startsWith("/proc") || clean.startsWith("/sys")) {
            throw SecurityException("Acceso denegado: intento de path traversal o acceso a partición de sistema restringida.")
        }

        val target = when {
            clean.startsWith("/storage/emulated/0/") || clean.startsWith("/sdcard/") -> {
                File(clean)
            }
            clean.startsWith("storage:") -> {
                val rel = clean.removePrefix("storage:").trimStart('/', '\\')
                File(getSharedStorageDir(), rel)
            }
            clean.startsWith("Download/") || clean.startsWith("Downloads/") ||
            clean.startsWith("Documents/") || clean.startsWith("Pictures/") ||
            clean.startsWith("DCIM/") || clean.startsWith("Music/") -> {
                File(getSharedStorageDir(), clean)
            }
            else -> {
                File(workspaceDir, clean)
            }
        }

        if (createParentDirs) {
            target.parentFile?.mkdirs()
        }
        return target
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "list_files",
            description = "Lista los archivos disponibles en el espacio de trabajo local o en carpetas de almacenamiento (Download, Documents, etc.).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("directory_path", JSONObject().put("type", "string").put("description", "Ruta opcional: 'workspace' (por defecto), 'Download', 'Documents', o 'storage:subcarpeta'"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "read_file",
            description = "Lee el contenido de un archivo de texto en el espacio de trabajo móvil o almacenamiento compartido.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("file_path", JSONObject().put("type", "string").put("description", "Nombre o ruta del archivo a leer (ej. 'notas.txt', 'Download/reporte.md')"))
                }
                put("properties", props)
                put("required", JSONArray().put("file_path"))
            }
        ),
        McpTool(
            name = "write_file",
            description = "Crea o sobrescribe un archivo de texto en el almacenamiento del teléfono o espacio de trabajo de la app.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("file_path", JSONObject().put("type", "string").put("description", "Ruta o nombre del archivo (ej. 'script.py', 'Download/ideas.txt', 'Documents/resumen.md')"))
                    put("content", JSONObject().put("type", "string").put("description", "Contenido textual completo a guardar"))
                }
                put("properties", props)
                put("required", JSONArray().put("file_path").put("content"))
            }
        ),
        McpTool(
            name = "delete_file",
            description = "Elimina un archivo del espacio de trabajo local o almacenamiento del dispositivo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("file_path", JSONObject().put("type", "string").put("description", "Nombre o ruta del archivo a borrar"))
                }
                put("properties", props)
                put("required", JSONArray().put("file_path"))
            }
        ),
        McpTool(
            name = "create_directory",
            description = "Crea una nueva carpeta en el almacenamiento del celular o en el espacio de trabajo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("directory_path", JSONObject().put("type", "string").put("description", "Ruta de la carpeta a crear (ej. 'Download/Proyectos', 'Documents/Codex')"))
                }
                put("properties", props)
                put("required", JSONArray().put("directory_path"))
            }
        ),
        McpTool(
            name = "get_storage_root",
            description = "Obtiene información sobre las rutas de almacenamiento disponibles y si el permiso de Todos los Archivos está activo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
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
                "get_storage_root" -> {
                    val res = JSONObject().apply {
                        put("workspace_internal_path", workspaceDir.absolutePath)
                        put("shared_storage_path", getSharedStorageDir().absolutePath)
                        put("has_all_files_permission", hasAllFilesPermission())
                        put("common_directories", JSONArray().apply {
                            put("Download")
                            put("Documents")
                            put("Pictures")
                            put("DCIM")
                            put("Music")
                        })
                    }
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "list_files" -> {
                    val rawDir = args.optString("directory_path", "workspace").trim()
                    val targetDir = if (rawDir.isEmpty() || rawDir == "workspace") {
                        workspaceDir
                    } else {
                        resolveTargetFile(rawDir)
                    }

                    if (!targetDir.exists()) {
                        return McpToolResult(call.id, call.toolName, "La carpeta '${targetDir.absolutePath}' no existe.", isError = true)
                    }
                    val files = targetDir.listFiles() ?: emptyArray()
                    val arr = JSONArray()
                    for (f in files.take(100)) {
                        arr.put(JSONObject().apply {
                            put("name", f.name)
                            put("size_bytes", f.length())
                            put("is_directory", f.isDirectory)
                            put("last_modified", f.lastModified())
                        })
                    }
                    val res = JSONObject().apply {
                        put("path", targetDir.absolutePath)
                        put("file_count", files.size)
                        put("files", arr)
                    }
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "read_file" -> {
                    val rawPath = args.optString("file_path", "").ifEmpty { args.optString("file_name", "") }.trim()
                    if (rawPath.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el parámetro 'file_path'", isError = true)
                    }
                    val target = resolveTargetFile(rawPath)
                    if (!target.exists() || !target.isFile) {
                        return McpToolResult(call.id, call.toolName, "El archivo '${target.absolutePath}' no existe.", isError = true)
                    }
                    val content = target.readText(Charsets.UTF_8)
                    McpToolResult(call.id, call.toolName, content)
                }
                "write_file" -> {
                    val rawPath = args.optString("file_path", "").ifEmpty { args.optString("file_name", "") }.trim()
                    val content = args.optString("content", "")
                    if (rawPath.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el parámetro 'file_path'", isError = true)
                    }
                    val target = resolveTargetFile(rawPath, createParentDirs = true)
                    target.writeText(content, Charsets.UTF_8)
                    McpToolResult(
                        call.id,
                        call.toolName,
                        "✅ Archivo guardado correctamente en '${target.absolutePath}' (${content.length} caracteres, ${target.length()} bytes)."
                    )
                }
                "create_directory" -> {
                    val rawDir = args.optString("directory_path", "").trim()
                    if (rawDir.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el parámetro 'directory_path'", isError = true)
                    }
                    val target = resolveTargetFile(rawDir)
                    val created = target.mkdirs() || target.exists()
                    if (created) {
                        McpToolResult(call.id, call.toolName, "✅ Carpeta creada o confirmada en '${target.absolutePath}'.")
                    } else {
                        McpToolResult(call.id, call.toolName, "No se pudo crear la carpeta '${target.absolutePath}'.", isError = true)
                    }
                }
                "delete_file" -> {
                    val rawPath = args.optString("file_path", "").ifEmpty { args.optString("file_name", "") }.trim()
                    if (rawPath.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el parámetro 'file_path'", isError = true)
                    }
                    val target = resolveTargetFile(rawPath)
                    if (!target.exists()) {
                        return McpToolResult(call.id, call.toolName, "El archivo '${target.absolutePath}' no existe.")
                    }
                    val deleted = target.delete()
                    if (deleted) {
                        McpToolResult(call.id, call.toolName, "✅ Archivo '${target.absolutePath}' eliminado correctamente.")
                    } else {
                        McpToolResult(call.id, call.toolName, "No se pudo eliminar el archivo '${target.absolutePath}'.", isError = true)
                    }
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }
}
