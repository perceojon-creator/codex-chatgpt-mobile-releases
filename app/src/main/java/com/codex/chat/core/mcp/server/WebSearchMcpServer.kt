package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.network.MobileWebSearchClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

/**
 * Servidor MCP Nativo de Búsqueda y Extracción Web para Android (Paridad con DSH web_search & web_fetch).
 * Ejecuta consultas directas multi-motor (DuckDuckGo HTML + Tavily) sin depender de binarios de escritorio.
 */
class WebSearchMcpServer(
    private val context: Context? = null,
    private val searchClient: MobileWebSearchClient = MobileWebSearchClient(),
    private val httpClient: OkHttpClient = defaultHttpClient()
) : McpServer {

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }
    }

    override val info = McpServerInfo(
        id = "mcp-android-web-search",
        name = "Web Search & Fetch",
        description = "Búsqueda web en vivo (DuckDuckGo/Tavily) y extracción de páginas web HTTP(S) nativa desde Android",
        iconEmoji = "🌐",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 2
    )

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "web_search",
            description = "Busca información actualizada en la web en tiempo real desde el dispositivo móvil. Devuelve títulos, fragmentos y URLs.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Término o consulta de búsqueda web."))
                    put("max_results", JSONObject().put("type", "integer").put("description", "Número máximo de resultados (1-10, por defecto 4)."))
                }
                put("properties", props)
                put("required", JSONArray().put("query"))
            }
        ),
        McpTool(
            name = "fetch_web_page",
            description = "Descarga y extrae el texto legible de una página web a partir de una URL HTTP o HTTPS.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("url", JSONObject().put("type", "string").put("description", "URL HTTP o HTTPS de la página a consultar."))
                    put("max_chars", JSONObject().put("type", "integer").put("description", "Límite máximo de caracteres de texto a retornar (por defecto 4000)."))
                }
                put("properties", props)
                put("required", JSONArray().put("url"))
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
                "web_search" -> {
                    val query = args.optString("query", "").trim()
                    if (query.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "La consulta de búsqueda no puede estar vacía.", isError = true)
                    }
                    val maxResults = args.optInt("max_results", 4).coerceIn(1, 10)
                    val hits = searchClient.search(query, maxResults)

                    if (hits.isEmpty()) {
                        McpToolResult(call.id, call.toolName, "No se encontraron resultados en la web para '$query'.")
                    } else {
                        val sb = StringBuilder("Resultados de búsqueda web para '$query':\n\n")
                        hits.forEachIndexed { i, hit ->
                            sb.append("${i + 1}. **${hit.title}**\n")
                            sb.append("   ${hit.snippet}\n")
                            sb.append("   URL: ${hit.url}\n\n")
                        }
                        McpToolResult(call.id, call.toolName, sb.toString().trim())
                    }
                }
                "fetch_web_page" -> {
                    val urlStr = args.optString("url", "").trim()
                    if (!urlStr.startsWith("http://") && !urlStr.startsWith("https://")) {
                        return McpToolResult(call.id, call.toolName, "URL inválida: Debe comenzar con http:// o https://", isError = true)
                    }
                    val maxChars = args.optInt("max_chars", 4000).coerceIn(200, 20000)

                    val request = Request.Builder()
                        .url(urlStr)
                        .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Mobile Safari/537.36")
                        .get()
                        .build()

                    httpClient.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) {
                            return McpToolResult(call.id, call.toolName, "Error HTTP ${response.code}: ${response.message}", isError = true)
                        }
                        val rawBody = response.body?.string() ?: ""
                        val cleanText = extractReadableText(rawBody).take(maxChars)
                        McpToolResult(call.id, call.toolName, "Contenido extraído de $urlStr (primeros ${cleanText.length} caracteres):\n\n$cleanText")
                    }
                }
                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }

    private fun extractReadableText(html: String): String {
        var text = html
        text = text.replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<style[^>]*>[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<nav[^>]*>[\\s\\S]*?</nav>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<footer[^>]*>[\\s\\S]*?</footer>", RegexOption.IGNORE_CASE), " ")
        text = text.replace(Regex("<[^>]+>"), " ")
        text = text.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
        return text.replace(Regex("\\s+"), " ").trim()
    }
}
