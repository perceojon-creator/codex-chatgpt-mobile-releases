package com.codex.chat.core.mcp.server

import com.codex.chat.core.mcp.model.McpServerInfo
import com.codex.chat.core.mcp.model.McpTool
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.model.McpToolResult

interface McpServer {
    val info: McpServerInfo
    fun getTools(): List<McpTool>
    fun executeTool(call: McpToolCallRequest): McpToolResult
}
