package com.codex.chat.core.mcp.server

import com.codex.chat.core.mcp.model.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.TimeUnit

class NetworkMcpServer : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-network",
        name = "Mobile Network & Diagnostics",
        description = "Peticiones HTTP directas, resolución de nombres DNS y prueba de conectividad y latencia desde el móvil",
        iconEmoji = "🌐",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 3
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .build()

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "http_get",
            description = "Realiza una petición HTTP GET a cualquier URL desde el teléfono y devuelve el cuerpo de la respuesta en texto.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("url", JSONObject().put("type", "string").put("description", "URL HTTP/HTTPS a consultar"))
                }
                put("properties", props)
                put("required", JSONArray().put("url"))
            }
        ),
        McpTool(
            name = "dns_resolve",
            description = "Resuelve un nombre de dominio (hostname) a sus direcciones IP correspondientes.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("hostname", JSONObject().put("type", "string").put("description", "Nombre de dominio (ej. 'api.openai.com', 'github.com')"))
                }
                put("properties", props)
                put("required", JSONArray().put("hostname"))
            }
        ),
        McpTool(
            name = "ping_host",
            description = "Mide la latencia de conexión TCP (en milisegundos) hacia un host y puerto específico.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("host", JSONObject().put("type", "string").put("description", "Host o dirección IP"))
                    put("port", JSONObject().put("type", "integer").put("description", "Puerto TCP (por defecto 80 o 443)"))
                }
                put("properties", props)
                put("required", JSONArray().put("host"))
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
                "http_get" -> {
                    var url = args.optString("url", "").trim()
                    if (url.isEmpty()) return McpToolResult(call.id, call.toolName, "URL vacía", isError = true)
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        url = "https://$url"
                    }
                    val req = Request.Builder().url(url).header("User-Agent", "Codex-Mobile-MCP/1.0").get().build()
                    httpClient.newCall(req).execute().use { resp ->
                        val code = resp.code
                        val body = resp.body?.string() ?: ""
                        McpToolResult(call.id, call.toolName, "HTTP $code\n$body")
                    }
                }
                "dns_resolve" -> {
                    val host = args.optString("hostname", "").trim()
                    if (host.isEmpty()) return McpToolResult(call.id, call.toolName, "Hostname vacío", isError = true)
                    val addresses = InetAddress.getAllByName(host)
                    val arr = JSONArray()
                    for (a in addresses) {
                        arr.put(a.hostAddress)
                    }
                    val res = JSONObject().apply {
                        put("hostname", host)
                        put("ip_addresses", arr)
                    }
                    McpToolResult(call.id, call.toolName, res.toString(2))
                }
                "ping_host" -> {
                    val host = args.optString("host", "").trim()
                    val port = args.optInt("port", 443)
                    if (host.isEmpty()) return McpToolResult(call.id, call.toolName, "Host vacío", isError = true)

                    val start = System.currentTimeMillis()
                    Socket().use { sock ->
                        sock.connect(InetSocketAddress(host, port), 4000)
                    }
                    val latency = System.currentTimeMillis() - start
                    McpToolResult(call.id, call.toolName, "✅ Conectado a $host:$port en ${latency}ms (TCP OK)")
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error de red: ${e.message}", isError = true)
        }
    }
}
