package com.codex.chat.parser

import com.codex.chat.core.parser.AstraDirectivesParser
import org.junit.Assert.*
import org.junit.Test

class AstraDirectivesParserTest {

    @Test
    fun testParseFollowUpChips() {
        val input = """
            He terminado la refactorización solicitada con éxito.
            
            - :codex-followup[Ejecutar tests unitarios]{prompt="Ejecuta ./gradlew testDebugUnitTest"}
            - :codex-followup[Ver reporte]{prompt="Muestra el reporte detallado"}
        """.trimIndent()

        val parsed = AstraDirectivesParser.parse(input)

        assertEquals("He terminado la refactorización solicitada con éxito.", parsed.cleanContent.trim())
        assertEquals(2, parsed.followUps.size)
        assertEquals("Ejecutar tests unitarios", parsed.followUps[0].label)
        assertEquals("Ejecuta ./gradlew testDebugUnitTest", parsed.followUps[0].prompt)
        assertEquals("Ver reporte", parsed.followUps[1].label)
        assertEquals("Muestra el reporte detallado", parsed.followUps[1].prompt)
    }

    @Test
    fun testParseCodeComments() {
        val input = """
            Aquí está la revisión de código:
            ::code-comment{title="[P1] Fuga de recursos" body="El cursor SQLite no se cierra en el bloque finally." file="/app/MainActivity.kt" start=120 end=125 priority=1}
            Todo lo demás se ve correcto.
        """.trimIndent()

        val parsed = AstraDirectivesParser.parse(input)

        assertEquals(1, parsed.codeComments.size)
        val comment = parsed.codeComments[0]
        assertEquals("[P1] Fuga de recursos", comment.title)
        assertEquals("El cursor SQLite no se cierra en el bloque finally.", comment.body)
        assertEquals("/app/MainActivity.kt", comment.file)
        assertEquals(120, comment.startLine)
        assertEquals(125, comment.endLine)
        assertEquals(1, comment.priority)
        assertFalse(parsed.cleanContent.contains("::code-comment"))
    }

    @Test
    fun testPassthroughNormalText() {
        val input = "Hola, ¿en qué puedo ayudarte hoy?"
        val parsed = AstraDirectivesParser.parse(input)
        assertEquals(input, parsed.cleanContent)
        assertTrue(parsed.followUps.isEmpty())
        assertTrue(parsed.codeComments.isEmpty())
    }

    @Test
    fun testAstraSystemPromptContainsAutonomyAndAntiSlop() {
        val prompt = com.codex.chat.core.network.CodexPayloadBuilder.buildSystemPrompt(null, "")
        assertTrue(prompt.contains("Astra Matrix"))
        assertTrue(prompt.contains("DIRECTRICES DE AGENCIA Y COMPORTAMIENTO GPT-6 ASTRA"))
        assertTrue(prompt.contains("Sesgo Total Hacia la Acción"))
        assertTrue(prompt.contains("Work First, Approve Last"))
        assertTrue(prompt.contains("Anti-Slop"))
        assertTrue(prompt.contains("- :codex-followup"))
        assertTrue(prompt.contains("::code-comment"))
    }

    @Test
    fun testAstraSkillsRegistered() {
        val repo = com.codex.chat.core.repository.SkillsRepository()
        val skills = repo.getAllSkills()
        assertNotNull(skills.find { it.id == "visualize" })
        assertNotNull(skills.find { it.id == "data-analytics" })
        assertNotNull(skills.find { it.id == "deep-research" })
        assertNotNull(skills.find { it.id == "sites-building" })
    }
}
