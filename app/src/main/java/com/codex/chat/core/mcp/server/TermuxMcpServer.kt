package com.codex.chat.core.mcp.server

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import com.codex.chat.core.mcp.model.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Servidor MCP Nativo para Termux en Android.
 *
 * Conecta a ChatGPT directamente con el subsistema Linux de Termux:
 * 1. Servidor Nativo Termux MCP en loopback (http://127.0.0.1:8080/mcp o :8383).
 * 2. Intent IPC Oficial de Termux (com.termux.RUN_COMMAND) como fallback autónomo.
 */
class TermuxMcpServer(private val context: Context? = null) : McpServer {

    companion object {
        private const val TAG = "TermuxMcpServer"
        const val TERMUX_PACKAGE = "com.termux"
        const val TERMUX_RUN_COMMAND_ACTION = "com.termux.RUN_COMMAND"
        const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
        const val TERMUX_HOME_DEFAULT = "/data/data/com.termux/files/home"
        const val TERMUX_BASH_PATH = "/data/data/com.termux/files/usr/bin/bash"
        const val DEFAULT_HTTP_BRIDGE = "http://127.0.0.1:8080"

        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
    }

    override val info = McpServerInfo(
        id = "mcp-termux",
        name = "Termux Linux Environment",
        description = "Entorno Linux Termux nativo: ejecución de comandos bash, scripts python, paquetes pkg, lectura de pantalla de consola y automatización",
        iconEmoji = "🐧",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 10
    )

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun isTermuxInstalled(): Boolean {
        val pm = context?.packageManager ?: return false
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0)) != null
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0) != null
            }
        } catch (e: Exception) {
            false
        }
    }

    fun getTermuxVersion(): String? {
        val pm = context?.packageManager ?: return null
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                pm.getPackageInfo(TERMUX_PACKAGE, PackageManager.PackageInfoFlags.of(0)).versionName
            } else {
                @Suppress("DEPRECATION")
                pm.getPackageInfo(TERMUX_PACKAGE, 0).versionName
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isHttpBridgeAvailable(bridgeUrl: String = DEFAULT_HTTP_BRIDGE): Boolean {
        return try {
            val req = Request.Builder().url(bridgeUrl + "/status").get().build()
            httpClient.newCall(req).execute().use { it.isSuccessful }
        } catch (e: Exception) {
            false
        }
    }

    override fun getTools(): List<McpTool> {
        return listOf(
            McpTool(
                name = "termux_execute_bash",
                description = "Ejecuta un comando o script Bash en el entorno Linux nativo de Termux. Soporta yt-dlp, ffmpeg, python, pip, git, curl y pipelines complejos.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "El comando bash a ejecutar en Termux (ej: yt-dlp, python, ffmpeg, curl)")
                        })
                        put("workdir", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directorio de trabajo (por defecto $TERMUX_HOME_DEFAULT)")
                        })
                        put("timeout_seconds", JSONObject().apply {
                            put("type", "number")
                            put("description", "Timeout máximo en segundos (por defecto 60)")
                        })
                    })
                    put("required", JSONArray().put("command"))
                }
            ),
            McpTool(
                name = "termux_execute_command",
                description = "Alias compatible de ejecución de comando Bash dentro de Termux.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("command", JSONObject().apply {
                            put("type", "string")
                            put("description", "El comando o pipeline bash a ejecutar")
                        })
                        put("workdir", JSONObject().apply {
                            put("type", "string")
                            put("description", "Directorio de trabajo (por defecto $TERMUX_HOME_DEFAULT)")
                        })
                        put("background", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Ejecutar en segundo plano")
                        })
                        put("timeout_seconds", JSONObject().apply {
                            put("type", "number")
                            put("description", "Timeout en segundos")
                        })
                    })
                    put("required", JSONArray().put("command"))
                }
            ),
            McpTool(
                name = "termux_read_terminal_screen",
                description = "Lee el texto y la transcripción que está actualmente visible en la pantalla de la terminal de Termux.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("session_index", JSONObject().apply {
                            put("type", "number")
                            put("description", "Índice de la sesión de terminal a leer (opcional)")
                        })
                    })
                }
            ),
            McpTool(
                name = "termux_send_keys",
                description = "Envía texto o combinaciones de teclas especiales ('CTRL_C', 'CTRL_Z', 'ENTER', 'TAB') a la terminal activa.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("text", JSONObject().apply {
                            put("type", "string")
                            put("description", "Texto o comando interactivo a escribir")
                        })
                        put("special_key", JSONObject().apply {
                            put("type", "string")
                            put("description", "Tecla especial: 'CTRL_C', 'CTRL_Z', 'ENTER', 'TAB', 'UP', 'DOWN'")
                        })
                    })
                }
            ),
            McpTool(
                name = "termux_list_sessions",
                description = "Lista todas las pestañas y sesiones de terminal abiertas en Termux.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            ),
            McpTool(
                name = "termux_pkg_manage",
                description = "Gestiona paquetes oficiales de Termux (install, update, search, list-installed).",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("action", JSONObject().apply {
                            put("type", "string")
                            put("description", "Acción: 'install', 'update', 'search', 'list-installed'")
                        })
                        put("package_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "Nombre del paquete (ej: 'python-yt-dlp', 'ffmpeg', 'faad2', 'lame')")
                        })
                    })
                    put("required", JSONArray().put("action"))
                }
            ),
            McpTool(
                name = "termux_pkg_install",
                description = "Instala paquetes oficiales de Termux mediante pkg install -y.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("package_name", JSONObject().apply {
                            put("type", "string")
                            put("description", "Nombre del paquete a instalar")
                        })
                    })
                    put("required", JSONArray().put("package_name"))
                }
            ),
            McpTool(
                name = "termux_read_file",
                description = "Lee el contenido de un archivo dentro de Termux (directorio home o /sdcard/Download).",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("file_path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Ruta absoluta o relativa al $TERMUX_HOME_DEFAULT")
                        })
                        put("max_chars", JSONObject().apply {
                            put("type", "number")
                            put("description", "Límite de caracteres a leer")
                        })
                    })
                    put("required", JSONArray().put("file_path"))
                }
            ),
            McpTool(
                name = "termux_write_file",
                description = "Crea o sobrescribe un archivo o script dentro del entorno Termux.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject().apply {
                        put("file_path", JSONObject().apply {
                            put("type", "string")
                            put("description", "Ruta del archivo a escribir")
                        })
                        put("content", JSONObject().apply {
                            put("type", "string")
                            put("description", "Contenido textual")
                        })
                        put("executable", JSONObject().apply {
                            put("type", "boolean")
                            put("description", "Asignar chmod +x si es true")
                        })
                    })
                    put("required", JSONArray().put("file_path").put("content"))
                }
            ),
            McpTool(
                name = "termux_get_environment",
                description = "Obtiene información detallada sobre el entorno Termux, instalación, arquitectura y bridge nativo.",
                serverName = info.name,
                inputSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                }
            )
        )
    }

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        val callId = call.id.ifBlank { "call-" + UUID.randomUUID().toString().take(8) }
        val args = try { JSONObject(call.argumentsJson.ifBlank { "{}" }) } catch (e: Exception) { JSONObject() }

        // RUTA DE ALTA VELOCIDAD: Si el servidor nativo Termux MCP está activo en loopback, invocarlo directamente
        val bridgeResult = callNativeMcpBridge(callId, call.toolName, args)
        if (bridgeResult != null) {
            return bridgeResult
        }

        // RUTA FALLBACK: Métodos locales e Intent IPC
        return try {
            when (call.toolName) {
                "termux_get_environment" -> executeGetEnvironment(callId)
                "termux_execute_command", "termux_execute_bash" -> executeCommand(callId, args)
                "termux_read_file" -> executeReadFile(callId, args)
                "termux_write_file" -> executeWriteFile(callId, args)
                "termux_pkg_install" -> executePkgInstall(callId, args)
                "termux_pkg_manage" -> executePkgInstall(callId, args)
                else -> McpToolResult(callId, call.toolName, "Herramienta no disponible vía fallback Intent: " + call.toolName, isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(callId, call.toolName, "Error ejecutando herramienta Termux: " + (e.message ?: "desconocido"), isError = true)
        }
    }

    private fun callNativeMcpBridge(callId: String, toolName: String, args: JSONObject): McpToolResult? {
        if (!isHttpBridgeAvailable()) return null
        return try {
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", callId)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", toolName)
                    put("arguments", args)
                })
            }
            val req = Request.Builder()
                .url(DEFAULT_HTTP_BRIDGE + "/mcp")
                .post(payload.toString().toRequestBody(JSON_MEDIA))
                .build()
            httpClient.newCall(req).execute().use { resp ->
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val resultObj = json.optJSONObject("result")
                val contentArr = resultObj?.optJSONArray("content")
                val text = contentArr?.optJSONObject(0)?.optString("text") ?: body
                val isError = resultObj?.optBoolean("isError", false) ?: !resp.isSuccessful
                McpToolResult(callId, toolName, text, isError = isError)
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun getSharedExportsDir(): File {
        val dir = try {
            File(Environment.getExternalStorageDirectory(), "TermuxExports")
        } catch (e: Throwable) {
            File(System.getProperty("java.io.tmpdir"), "TermuxExports")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun executeGetEnvironment(callId: String): McpToolResult {
        val installed = isTermuxInstalled()
        val version = getTermuxVersion()
        val bridgeActive = isHttpBridgeAvailable()
        val sharedDir = getSharedExportsDir()

        val json = JSONObject().apply {
            put("is_installed", installed)
            put("package", TERMUX_PACKAGE)
            put("version", version ?: "No detectada")
            val abi = try {
                @Suppress("UNNECESSARY_SAFE_CALL")
                Build.SUPPORTED_ABIS?.firstOrNull()
            } catch (e: Throwable) {
                null
            } ?: System.getProperty("os.arch") ?: "arm64-v8a"
            put("architecture", abi)
            put("prefix_path", "/data/data/com.termux/files/usr")
            put("home_path", TERMUX_HOME_DEFAULT)
            put("http_bridge_status", if (bridgeActive) "activo (127.0.0.1:8080)" else "inactivo")
            put("shared_exchange_dir", sharedDir.absolutePath)
            put("recommended_setup", "Para máxima velocidad sin popups, asegúrate de tener ~/.termux/termux.properties con allow-external-apps = true")
        }
        return McpToolResult(callId, "termux_get_environment", json.toString(2))
    }

    private fun executeCommand(callId: String, args: JSONObject): McpToolResult {
        val command = args.optString("command", "").trim()
        if (command.isEmpty()) {
            return McpToolResult(callId, "termux_execute_command", "El parámetro 'command' es obligatorio.", isError = true)
        }
        val workdir = args.optString("workdir", TERMUX_HOME_DEFAULT)
        val background = args.optBoolean("background", true)
        val timeoutSec = args.optInt("timeout_seconds", 30)

        // RUTA 1: Si hay bridge HTTP local en 127.0.0.1:8080, despachar vía REST
        if (isHttpBridgeAvailable()) {
            try {
                val payload = JSONObject().apply {
                    put("command", command)
                    put("workdir", workdir)
                }
                val req = Request.Builder()
                    .url(DEFAULT_HTTP_BRIDGE + "/execute")
                    .post(payload.toString().toRequestBody(JSON_MEDIA))
                    .build()
                httpClient.newCall(req).execute().use { resp ->
                    val body = resp.body?.string() ?: ""
                    return McpToolResult(callId, "termux_execute_command", body, isError = !resp.isSuccessful)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Bridge HTTP falló, recurriendo a Intent IPC oficial", e)
            }
        }

        // RUTA 2: Intent IPC Oficial Termux RUN_COMMAND
        val ctx = context
        if (ctx == null || !isTermuxInstalled()) {
            return McpToolResult(
                callId,
                "termux_execute_command",
                "Termux no está instalado o el contexto Android no está disponible. Instala com.termux para ejecución nativa Linux.",
                isError = true
            )
        }

        val sharedDir = getSharedExportsDir()
        val outputLog = File(sharedDir, "termux_run_" + System.currentTimeMillis() + ".log")
        val wrappedCommand = command + " > \"" + outputLog.absolutePath + "\" 2>&1"

        try {
            val intent = Intent().apply {
                setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
                action = TERMUX_RUN_COMMAND_ACTION
                putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH_PATH)
                putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", wrappedCommand))
                putExtra("com.termux.RUN_COMMAND_WORKDIR", workdir)
                putExtra("com.termux.RUN_COMMAND_BACKGROUND", background)
                putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
            }

            ctx.startService(intent)

            val startWait = System.currentTimeMillis()
            val maxWaitMs = timeoutSec * 1000L
            while (System.currentTimeMillis() - startWait < maxWaitMs) {
                if (outputLog.exists() && outputLog.length() > 0) {
                    Thread.sleep(150)
                    val outputText = outputLog.readText(Charsets.UTF_8)
                    outputLog.delete()
                    val resObj = JSONObject().apply {
                        put("status", "completed")
                        put("command", command)
                        put("output", outputText)
                    }
                    return McpToolResult(callId, "termux_execute_command", resObj.toString(2))
                }
                Thread.sleep(100)
            }

            val dispatchedObj = JSONObject().apply {
                put("status", "dispatched")
                put("command", command)
                put("message", "Comando enviado con éxito a Termux en segundo plano.")
                put("note", "Si no recibiste salida, verifica que allow-external-apps = true esté en ~/.termux/termux.properties")
            }
            return McpToolResult(callId, "termux_execute_command", dispatchedObj.toString(2))
        } catch (e: Exception) {
            return McpToolResult(callId, "termux_execute_command", "Fallo al enviar comando a Termux: " + e.message, isError = true)
        }
    }

    private fun executeReadFile(callId: String, args: JSONObject): McpToolResult {
        val path = args.optString("file_path", "").trim()
        if (path.isEmpty()) {
            return McpToolResult(callId, "termux_read_file", "El parámetro 'file_path' es obligatorio.", isError = true)
        }
        val maxChars = args.optInt("max_chars", 20000)

        val targetFile = resolvePath(path)
        if (targetFile.exists() && targetFile.canRead()) {
            val text = targetFile.readText(Charsets.UTF_8).take(maxChars)
            return McpToolResult(callId, "termux_read_file", text)
        }

        return executeCommand(callId, JSONObject().apply {
            put("command", "cat \"" + path + "\"")
        })
    }

    private fun executeWriteFile(callId: String, args: JSONObject): McpToolResult {
        val path = args.optString("file_path", "").trim()
        val content = args.optString("content", "")
        val isExecutable = args.optBoolean("executable", false)
        if (path.isEmpty()) {
            return McpToolResult(callId, "termux_write_file", "El parámetro 'file_path' es obligatorio.", isError = true)
        }

        val targetFile = resolvePath(path)
        try {
            targetFile.parentFile?.mkdirs()
            targetFile.writeText(content, Charsets.UTF_8)
            if (isExecutable) {
                targetFile.setExecutable(true)
            }
            val json = JSONObject().apply {
                put("status", "success")
                put("file_path", targetFile.absolutePath)
                put("bytes_written", content.toByteArray().size)
                put("executable", isExecutable)
            }
            return McpToolResult(callId, "termux_write_file", json.toString(2))
        } catch (e: Exception) {
            val chmodCmd = if (isExecutable) " && chmod +x \"" + path + "\"" else ""
            val echoCmd = "echo " + JSONObject.quote(content) + " > \"" + path + "\"" + chmodCmd
            return executeCommand(callId, JSONObject().apply {
                put("command", echoCmd)
            })
        }
    }

    private fun executePkgInstall(callId: String, args: JSONObject): McpToolResult {
        val pkg = args.optString("package_name", "").trim()
        if (pkg.isEmpty()) {
            return McpToolResult(callId, "termux_pkg_install", "El parámetro 'package_name' es obligatorio.", isError = true)
        }
        return executeCommand(callId, JSONObject().apply {
            put("command", "pkg install -y " + pkg)
            put("timeout_seconds", 60)
        })
    }

    private fun resolvePath(path: String): File {
        val clean = path.trim()
        return if (clean.startsWith("/") || (clean.length > 2 && clean[1] == ':' && (clean[2] == '\\' || clean[2] == '/'))) {
            File(clean)
        } else if (clean.startsWith("~")) {
            File(TERMUX_HOME_DEFAULT, clean.removePrefix("~").removePrefix("/"))
        } else {
            File(TERMUX_HOME_DEFAULT, clean)
        }
    }
}
