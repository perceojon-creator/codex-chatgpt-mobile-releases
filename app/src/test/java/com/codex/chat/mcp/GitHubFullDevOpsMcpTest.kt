package com.codex.chat.mcp

import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.GitHubMcpServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubFullDevOpsMcpTest {

    @Test
    fun testAllNineGitHubToolsAreExposed() {
        val server = GitHubMcpServer()
        assertEquals(9, server.info.toolsCount)
        val tools = server.getTools()
        assertEquals(9, tools.size)

        val toolNames = tools.map { it.name }
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
        // Intentar commit sin contenido
        val res = server.executeTool(McpToolCallRequest("call-1", "github_commit_file", "{}"))
        assertTrue("Debe reportar error cuando faltan parámetros", res.isError)
        assertTrue(res.content.contains("Faltan parámetros"))
    }
}
