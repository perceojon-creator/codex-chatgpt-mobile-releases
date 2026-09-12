package com.codex.chat.core.mcp.model

import org.json.JSONObject

enum class McpServerType {
    NATIVE,
    REMOTE_HTTP
}

data class McpTool(
    val name: String,
    val description: String,
    val serverName: String,
    val inputSchema: JSONObject = JSONObject().apply {
        put("type", "object")
        put("properties", JSONObject())
    }
) {
    fun toOpenAiToolSchema(): JSONObject {
        val root = JSONObject()
        root.put("type", "function")
        val fn = JSONObject()
        fn.put("name", name)
        fn.put("description", description)
        fn.put("parameters", inputSchema)
        root.put("function", fn)
        return root
    }
}

data class McpServerInfo(
    val id: String,
    val name: String,
    val description: String,
    val iconEmoji: String,
    val type: McpServerType = McpServerType.NATIVE,
    val endpoint: String? = null,
    var isEnabled: Boolean = true,
    var toolsCount: Int = 0
)

data class McpToolCallRequest(
    val id: String,
    val toolName: String,
    val argumentsJson: String
)

data class McpToolResult(
    val callId: String,
    val toolName: String,
    val content: String,
    val isError: Boolean = false
) {
    fun toToolMessageJson(): JSONObject {
        val obj = JSONObject()
        obj.put("role", "tool")
        obj.put("tool_call_id", callId)
        obj.put("name", toolName)
        obj.put("content", content)
        return obj
    }
}
