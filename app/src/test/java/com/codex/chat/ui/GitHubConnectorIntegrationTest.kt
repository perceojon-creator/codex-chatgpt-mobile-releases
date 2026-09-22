package com.codex.chat.ui

import com.codex.chat.core.connector.ConnectorProvider
import com.codex.chat.core.connector.GitHubConnectorClient
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GitHubConnectorIntegrationTest {

    @Test
    fun testGitHubProviderIsAvailable() {
        val provider = ConnectorProvider.GITHUB
        assertTrue("GitHub debe estar disponible en ConnectorProvider", provider.isAvailable)
        assertTrue(provider.displayName == "GitHub")
        assertTrue(provider.iconEmoji == "🐙")
    }

    @Test
    fun testConnectorsSheetIncludesGitHubCardAndSwitch() {
        val file = File("src/main/res/layout/bottom_sheet_connectors.xml")
        assertTrue("bottom_sheet_connectors.xml debe existir", file.exists())
        val text = file.readText()
        assertTrue("Debe incluir cardConnectorGitHub", text.contains("cardConnectorGitHub"))
        assertTrue("Debe incluir switchGitHubActive", text.contains("switchGitHubActive"))
        assertTrue("Debe incluir GitHub", text.contains("GitHub"))
    }

    @Test
    fun testGitHubConnectorClientCanInstantiate() {
        val client = GitHubConnectorClient()
        val result = client.getFileRawContent("openai", "openai-python", "README.md")
        // No arremete excepción de instanciación
        assertTrue("Resultado no nulo", result != null)
    }
}
