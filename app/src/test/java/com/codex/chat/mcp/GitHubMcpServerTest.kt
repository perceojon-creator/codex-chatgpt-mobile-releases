package com.codex.chat.mcp

import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.GitHubMcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMcpServerTest {

    @Test
    fun testGitHubMcpServerProvidesTools() {
        val server = GitHubMcpServer()
        assertEquals("GitHub", server.info.name)
        val tools = server.getTools()
        assertTrue(tools.any { it.name == "github_list_repos" })
        assertTrue(tools.any { it.name == "github_get_file" })
        assertTrue(tools.any { it.name == "github_search_repos" })
        assertTrue(tools.any { it.name == "github_user_profile" })
    }

    @Test
    fun testGitHubServerRegisteredInMcpRegistry() {
        val registry = McpRegistry()
        val servers = registry.getServers()
        assertTrue("GitHubMcpServer debe estar registrado en McpRegistry", servers.any { it.name == "GitHub" })
        val tools = registry.getAllActiveTools()
        assertTrue("github_list_repos debe estar activo en McpRegistry", tools.any { it.name == "github_list_repos" })
    }

    @Test
    fun testExecuteToolUserProfileWithoutTokenReturnsAnonymousStatus() {
        val server = GitHubMcpServer()
        val req = McpToolCallRequest(id = "call-1", toolName = "github_user_profile", argumentsJson = "{}")
        val res = server.executeTool(req)
        assertNotNull(res)
        assertEquals("call-1", res.callId)
        assertTrue("Debe informar estado de autenticación", res.content.contains("authenticated") || res.content.contains("Acceso"))
    }
}
