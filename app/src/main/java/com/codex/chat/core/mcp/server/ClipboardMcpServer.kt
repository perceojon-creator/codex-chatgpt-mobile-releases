package com.codex.chat.core.mcp.server

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject

class ClipboardMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-clipboard",
        name = "System Clipboard",
        description = "Lectura y escritura en el portapapeles del sistema para compartir fragmentos de código y texto",
        iconEmoji = "📋",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 2
    )

    private var fallbackClipboard: String = ""

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "get_clipboard_text",
            description = "Lee el texto actualmente almacenado en el portapapeles del dispositivo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "set_clipboard_text",
            description = "Copia un texto o fragmento de código al portapapeles del dispositivo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("text", JSONObject().put("type", "string").put("description", "Texto a copiar en el portapapeles"))
                }
                put("properties", props)
                put("required", JSONArray().put("text"))
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
                "get_clipboard_text" -> {
                    if (context == null) {
                        return McpToolResult(call.id, call.toolName, fallbackClipboard.ifEmpty { "[Portapapeles vacío]" })
                    }
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    val clip = clipboard?.primaryClip
                    if (clip != null && clip.itemCount > 0) {
                        val text = clip.getItemAt(0).coerceToText(context).toString()
                        McpToolResult(call.id, call.toolName, text)
                    } else {
                        McpToolResult(call.id, call.toolName, "[Portapapeles vacío]")
                    }
                }
                "set_clipboard_text" -> {
                    val text = args.optString("text", "")
                    fallbackClipboard = text
                    if (context != null) {
                        Handler(Looper.getMainLooper()).post {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            val clip = ClipData.newPlainText("ChatGPT MCP", text)
                            clipboard?.setPrimaryClip(clip)
                        }
                    }
                    McpToolResult(call.id, call.toolName, "✅ Texto copiado al portapapeles exitosamente (${text.length} caracteres).")
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }
}
