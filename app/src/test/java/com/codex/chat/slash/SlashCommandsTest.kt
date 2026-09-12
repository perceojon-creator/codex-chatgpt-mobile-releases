package com.codex.chat.slash

import com.codex.chat.core.model.SlashActionType
import com.codex.chat.core.model.SlashCommandInfo
import com.codex.chat.core.repository.SkillsRepository
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SlashCommandsTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var repository: SkillsRepository

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        repository = SkillsRepository(context = null)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testInstallSkillFromMockServerSuccess() {
        val skillMd = """---
name: Playwright Specialist
description: Pruebas automatizadas de extremo a extremo
---
Eres un especialista en testing con Playwright.
REGLA 1: Usa siempre selectores de rol accesible.
REGLA 2: No uses sleeps arbitrarios.
""".trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(skillMd))

        val mockUrl = mockServer.url("/anthropics/skills/main/skills/playwright/SKILL.md").toString()

        val (ok, message) = repository.installSkillFromUrl(mockUrl)
        assertTrue("La instalación debe ser exitosa: $message", ok)
        assertTrue("El mensaje debe contener el nombre de la skill", message.contains("Playwright Specialist"))

        val installed = repository.getSkillById("installed-playwright-specialist")
        assertNotNull("La skill debe encontrarse en el repositorio", installed)
        assertEquals("Playwright Specialist", installed!!.name)
        assertEquals("Pruebas automatizadas de extremo a extremo", installed.description)
        assertTrue("El prompt debe contener las directivas", installed.systemPrompt.contains("REGLA 1"))
        assertTrue("Debe marcarse como personalizada", installed.isCustom)
    }

    @Test
    fun testInstallSkillFromMockServer404Error() {
        mockServer.enqueue(MockResponse().setResponseCode(404).setBody("Not Found"))

        val mockUrl = mockServer.url("/skills/nonexistent/SKILL.md").toString()
        val (ok, message) = repository.installSkillFromUrl(mockUrl)

        assertFalse("Debe fallar con HTTP 404", ok)
        assertTrue("Debe indicar el código de error HTTP 404", message.contains("404"))
    }

    @Test
    fun testInstallSkillFromMockServerEmptyResponse() {
        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(""))

        val mockUrl = mockServer.url("/skills/empty/SKILL.md").toString()
        val (ok, message) = repository.installSkillFromUrl(mockUrl)

        assertFalse("Debe fallar con cuerpo vacío", ok)
        assertTrue("Debe indicar que la respuesta fue vacía", message.contains("vacía") || message.contains("vacío"))
    }

    @Test
    fun testSlashCommandInfoDataModel() {
        val cmd = SlashCommandInfo(
            command = "/install",
            description = "Instalar skill desde GitHub",
            iconEmoji = "📥",
            badge = "CMD",
            actionType = SlashActionType.INSTALL_SKILL_DIALOG
        )

        assertEquals("/install", cmd.command)
        assertEquals(SlashActionType.INSTALL_SKILL_DIALOG, cmd.actionType)
        assertEquals("📥", cmd.iconEmoji)
    }
}
