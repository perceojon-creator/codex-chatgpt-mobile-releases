package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.root.RootShellExecutor
import org.json.JSONArray
import org.json.JSONObject

class RootMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-root",
        name = "Android Root Superuser Engine",
        description = "Control absoluto con privilegios de superusuario (Root/su), shell elevado, lectura/escritura del sistema y gestión avanzada",
        iconEmoji = "⚡",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 6
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "check_root_status",
            description = "Comprueba si el dispositivo Android está rooteado (su, Magisk, KernelSU, APatch) y si los permisos de superusuario están activos.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "execute_root_command",
            description = "Ejecuta cualquier comando con privilegios de superusuario root en el shell del celular.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("command", JSONObject().put("type", "string").put("description", "Comando de shell Linux a ejecutar como root"))
                    put("timeout_seconds", JSONObject().put("type", "integer").put("description", "Tiempo límite en segundos (por defecto 15)"))
                }
                put("properties", props)
                put("required", JSONArray().put("command"))
            }
        ),
        McpTool(
            name = "root_read_file",
            description = "Lee el contenido de cualquier archivo del sistema Android protegido (/data, /system, etc.) usando privilegios root.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("path", JSONObject().put("type", "string").put("description", "Ruta absoluta del archivo a leer (ej. '/system/build.prop')"))
                    put("max_lines", JSONObject().put("type", "integer").put("description", "Máximo de líneas a retornar (por defecto 100)"))
                }
                put("properties", props)
                put("required", JSONArray().put("path"))
            }
        ),
        McpTool(
            name = "root_write_file",
            description = "Escribe o modifica un archivo en cualquier partición del sistema Android con permisos root.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("path", JSONObject().put("type", "string").put("description", "Ruta absoluta del archivo destino"))
                    put("content", JSONObject().put("type", "string").put("description", "Texto o contenido a escribir"))
                    put("append", JSONObject().put("type", "boolean").put("description", "Si es true, añade al final en vez de sobrescribir"))
                }
                put("properties", props)
                put("required", JSONArray().put("path").put("content"))
            }
        ),
        McpTool(
            name = "root_grant_permissions",
            description = "Concede permisos protegidos del sistema al APK mediante 'pm grant' ejecutado como root.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("permission", JSONObject().put("type", "string").put("description", "Nombre del permiso o 'ALL'"))
                }
                put("properties", props)
                put("required", JSONArray().put("permission"))
            }
        ),
        McpTool(
            name = "root_reboot_device",
            description = "Reinicia o apaga el dispositivo móvil con comandos root directos.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("mode", JSONObject().put("type", "string").put("description", "Modo: 'normal', 'recovery', 'bootloader', 'poweroff'"))
                }
                put("properties", props)
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try { JSONObject(call.argumentsJson) } catch (e: Exception) { JSONObject() }
            when (call.toolName) {
                "check_root_status" -> {
                    val res = checkRootStatus()
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "execute_root_command" -> {
                    val cmd = args.optString("command", "").trim()
                    if (cmd.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta el parámetro 'command'", isError = true)
                    }
                    val timeout = args.optLong("timeout_seconds", 15).coerceIn(1, 120)
                    val res = executeCommand(cmd, timeout)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "root_read_file" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta la ruta del archivo 'path'", isError = true)
                    }
                    val maxLines = args.optInt("max_lines", 100)
                    val res = readRootFile(path, maxLines)
                    McpToolResult(call.id, call.toolName, res)
                }
                "root_write_file" -> {
                    val path = args.optString("path", "").trim()
                    val content = args.optString("content", "")
                    val append = args.optBoolean("append", false)
                    if (path.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Falta la ruta del archivo 'path'", isError = true)
                    }
                    val res = writeRootFile(path, content, append)
                    McpToolResult(call.id, call.toolName, res)
                }
                "root_grant_permissions" -> {
                    val perm = args.optString("permission", "ALL").trim()
                    val res = grantPermissionsViaRoot(perm)
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "root_reboot_device" -> {
                    val mode = args.optString("mode", "normal").lowercase().trim()
                    val res = rebootDevice(mode)
                    McpToolResult(call.id, call.toolName, res)
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error en " + call.toolName + ": " + e.message, isError = true)
        }
    }

    private fun checkRootStatus(): JSONObject {
        val root = JSONObject()
        if (context == null) {
            root.put("is_rooted", true)
            root.put("su_binary_present", true)
            root.put("uid", 0)
            root.put("gid", 0)
            root.put("selinux_mode", "Enforcing")
            root.put("su_provider", "Magisk (Modo Mock Test)")
            return root
        }

        val present = RootShellExecutor.isSuBinaryPresent()
        root.put("su_binary_present", present)

        if (!present) {
            root.put("is_rooted", false)
            root.put("message", "Dispositivo sin root actualmente. Si se rootea con Magisk, KernelSU o APatch en el futuro, se activará de forma automática.")
            return root
        }

        val result = RootShellExecutor.checkRootAccess()
        root.put("is_rooted", result.isRooted)
        root.put("output", result.stdout)
        if (!result.isRooted) {
            root.put("stderr", result.stderr)
            root.put("hint", "El binario 'su' existe pero la app no tiene permiso concedido en el gestor de superusuario.")
        }
        return root
    }

    private fun executeCommand(command: String, timeoutSec: Long): JSONObject {
        val root = JSONObject()
        if (context == null) {
            root.put("exit_code", 0)
            root.put("stdout", "mock_root_output_for: " + command)
            root.put("stderr", "")
            root.put("success", true)
            root.put("note", "Modo test sin contexto")
            return root
        }

        val res = RootShellExecutor.executeSu(command, timeoutSec)
        root.put("exit_code", res.exitCode)
        root.put("stdout", res.stdout)
        root.put("stderr", res.stderr)
        root.put("success", res.success)
        return root
    }

    private fun readRootFile(path: String, maxLines: Int): String {
        if (context == null) {
            return "✅ [Modo Test] Contenido simulado de " + path + " (máx " + maxLines + " líneas)."
        }
        val qChar = 34.toChar().toString()
        val safePath = path.replace(qChar, "")
        val cmd = "head -n " + maxLines + " " + qChar + safePath + qChar
        val res = RootShellExecutor.executeSu(cmd)
        return if (res.success) {
            res.stdout.ifEmpty { "Archivo vacío o sin salida legible." }
        } else {
            "Error leyendo archivo con root: " + res.stderr
        }
    }

    private fun writeRootFile(path: String, content: String, append: Boolean): String {
        if (context == null) {
            return "✅ [Modo Test] Archivo " + path + " escrito exitosamente con root."
        }
        val qChar = 34.toChar().toString()
        val dChar = 36.toChar().toString()
        val safePath = path.replace(qChar, "")
        val op = if (append) ">>" else ">"
        val escaped = content.replace(qChar, " ").replace(dChar, " ")
        val cmd = "echo " + qChar + escaped + qChar + " " + op + " " + qChar + safePath + qChar
        val res = RootShellExecutor.executeSu(cmd)
        return if (res.success) {
            "✅ Archivo '" + path + "' " + (if (append) "actualizado (append)" else "escrito") + " con permisos root."
        } else {
            "Error escribiendo con root: " + res.stderr
        }
    }

    private fun grantPermissionsViaRoot(permission: String): JSONObject {
        val root = JSONObject()
        val pkg = context?.packageName ?: "com.codex.chat"

        val permissionsToGrant = if (permission.equals("ALL", ignoreCase = true)) {
            listOf(
                "android.permission.WRITE_SECURE_SETTINGS",
                "android.permission.DUMP",
                "android.permission.READ_LOGS",
                "android.permission.SET_TIME_ZONE",
                "android.permission.PACKAGE_USAGE_STATS",
                "android.permission.SYSTEM_ALERT_WINDOW"
            )
        } else {
            listOf(permission)
        }

        if (context == null) {
            root.put("granted_count", permissionsToGrant.size)
            root.put("permissions", JSONArray(permissionsToGrant))
            root.put("note", "Modo emulado / Test")
            return root
        }

        val results = JSONArray()
        var granted = 0
        for (p in permissionsToGrant) {
            val cmd = "pm grant " + pkg + " " + p
            val res = RootShellExecutor.executeSu(cmd)
            val item = JSONObject().apply {
                put("permission", p)
                put("success", res.exitCode == 0)
                if (res.exitCode != 0) put("error", res.stderr)
            }
            if (res.exitCode == 0) granted++
            results.put(item)
        }

        root.put("granted_count", granted)
        root.put("total", permissionsToGrant.size)
        root.put("details", results)
        return root
    }

    private fun rebootDevice(mode: String): String {
        if (context == null) {
            return "✅ [Modo Test] Solicitud de reinicio en modo '" + mode + "' ejecutada."
        }
        val cmd = when (mode) {
            "recovery" -> "reboot recovery"
            "bootloader", "fastboot" -> "reboot bootloader"
            "poweroff", "shutdown" -> "reboot -p"
            else -> "reboot"
        }
        val res = RootShellExecutor.executeSu(cmd)
        return if (res.success) {
            "⚡ Comando de reinicio (" + cmd + ") enviado al sistema."
        } else {
            "No se pudo ejecutar el reinicio: " + res.stderr
        }
    }
}
