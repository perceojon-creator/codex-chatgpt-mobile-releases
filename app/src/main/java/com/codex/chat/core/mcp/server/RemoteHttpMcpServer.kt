package com.codex.chat.core.mcp.server

import com.codex.chat.core.mcp.model.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class RemoteHttpMcpServer(
    id: String,
    name: String,
    val endpointUrl: String,
    val authToken: String? = null,
    iconEmoji: String = "🔌"
) : McpServer {

    override val info = McpServerInfo(
        id = id,
        name = name,
        description = "Servidor MCP Remoto en $endpointUrl",
        iconEmoji = iconEmoji,
        type = McpServerType.REMOTE_HTTP,
        endpoint = endpointUrl,
        isEnabled = true,
        toolsCount = 0
    )

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val cachedTools = mutableListOf<McpTool>()

    fun refreshTools(): List<McpTool> {
        try {
            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "tools/list")
                put("params", JSONObject())
            }
            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .post(payload.toString().toRequestBody(mediaType))

            if (!authToken.isNullOrBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) return cachedTools
                val body = resp.body?.string() ?: return cachedTools
                val json = JSONObject(body)
                val result = json.optJSONObject("result") ?: return cachedTools
                val toolsArr = result.optJSONArray("tools") ?: return cachedTools

                cachedTools.clear()
                for (i in 0 until toolsArr.length()) {
                    val tObj = toolsArr.optJSONObject(i) ?: continue
                    val tName = tObj.optString("name", "")
                    val tDesc = tObj.optString("description", "")
                    val tSchema = tObj.optJSONObject("inputSchema") ?: JSONObject().apply {
                        put("type", "object")
                        put("properties", JSONObject())
                    }
                    if (tName.isNotEmpty()) {
                        cachedTools.add(McpTool(tName, tDesc, info.name, tSchema))
                    }
                }
                info.toolsCount = cachedTools.size
            }
        } catch (e: Exception) {
            // Server offline or unreachable
        }
        return cachedTools
    }

    override fun getTools(): List<McpTool> {
        if (cachedTools.isEmpty()) {
            return refreshTools()
        }
        return cachedTools
    }

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val argsObj = try {
                JSONObject(call.argumentsJson)
            } catch (e: Exception) {
                JSONObject()
            }

            val payload = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", call.id)
                put("method", "tools/call")
                put("params", JSONObject().apply {
                    put("name", call.toolName)
                    put("arguments", argsObj)
                })
            }

            val mediaType = "application/json; charset=utf-8".toMediaType()
            val reqBuilder = Request.Builder()
                .url(endpointUrl)
                .post(payload.toString().toRequestBody(mediaType))

            if (!authToken.isNullOrBlank()) {
                reqBuilder.addHeader("Authorization", "Bearer $authToken")
            }

            httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return McpToolResult(call.id, call.toolName, "HTTP ${resp.code}: Error en servidor MCP remoto", isError = true)
                }
                val body = resp.body?.string() ?: ""
                val json = JSONObject(body)
                val result = json.optJSONObject("result")
                val contentArr = result?.optJSONArray("content")
                if (contentArr != null && contentArr.length() > 0) {
                    val sb = StringBuilder()
                    for (i in 0 until contentArr.length()) {
                        val item = contentArr.optJSONObject(i)
                        sb.append(item?.optString("text", "")).append("\n")
                    }
                    McpToolResult(call.id, call.toolName, sb.toString().trim())
                } else {
                    McpToolResult(call.id, call.toolName, body)
                }
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error de llamada MCP remota: ${e.message}", isError = true)
        }
    }
}
