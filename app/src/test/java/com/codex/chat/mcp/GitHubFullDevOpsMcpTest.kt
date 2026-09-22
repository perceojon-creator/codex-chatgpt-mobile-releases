package com.codex.chat.mcp

import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.GitHubMcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFullDevOpsMcpTest {

    @Test
    fun testAllTenGitHubToolsAreExposed() {
        val server = GitHubMcpServer()
        assertEquals(11, server.info.toolsCount)
        val tools = server.getTools()
        assertEquals(11, tools.size)

        val toolNames = tools.map { it.name }
        assertTrue(toolNames.contains("github_user_profile"))
        assertTrue(toolNames.contains("github_list_repos"))
        assertTrue(toolNames.contains("github_create_repo"))
        assertTrue(toolNames.contains("github_get_file"))
        assertTrue(toolNames.contains("github_commit_file"))
        assertTrue(toolNames.contains("github_list_branches"))
        assertTrue(toolNames.contains("github_create_branch"))
        assertTrue(toolNames.contains("github_list_issues"))
        assertTrue(toolNames.contains("github_create_issue"))
        assertTrue(toolNames.contains("github_create_pr"))
    }

    @Test
    fun testToolValidationFailsGracefullyOnMissingRequiredParams() {
        val server = GitHubMcpServer()
        val res = server.executeTool(McpToolCallRequest("call-1", "github_commit_file", "{}"))
        assertTrue("Debe reportar error cuando faltan parámetros", res.isError)
        assertTrue(res.content.contains("Faltan parámetros"))
    }
}
