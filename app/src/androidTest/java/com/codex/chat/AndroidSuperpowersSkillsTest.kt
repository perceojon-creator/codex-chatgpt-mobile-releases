package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.repository.SkillsRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSuperpowersSkillsTest {

    private lateinit var repository: SkillsRepository

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        repository = SkillsRepository(context)
    }

    @Test
    fun testAllSuperpowersSkillsAreLoaded() {
        val allSkills = repository.getAllSkills()
        assertTrue("Debe cargar al menos 40 habilidades oficiales", allSkills.size >= 40)

        val requiredSkillIds = listOf(
            "codex-systematic-debugging",
            "codex-tdd-implementer",
            "codex-verification-before-completion",
            "codex-subagent-driven-development",
            "codex-writing-plans",
            "codex-requesting-code-review",
            "codex-receiving-code-review",
            "codex-agent-guardrails",
            "codex-graphify-navigator"
        )

        for (id in requiredSkillIds) {
            val skill = repository.getSkillById(id)
            assertNotNull("La habilidad Superpowers '$id' debe estar registrada", skill)
            assertTrue("El nombre no debe estar vacío para '$id'", skill!!.name.isNotBlank())
            assertTrue("La descripción no debe estar vacía para '$id'", skill.description.isNotBlank())
            assertTrue("El systemPrompt no debe estar vacío para '$id'", skill.systemPrompt.isNotBlank())
            assertTrue("El emoji de icono no debe estar vacío para '$id'", skill.iconEmoji.isNotBlank())
        }
    }

    @Test
    fun testVerificationBeforeCompletionMandate() {
        val skill = repository.getSkillById("codex-verification-before-completion")
        assertNotNull(skill)
        assertTrue(
            "Debe contener la Ley de Hierro de la Verificación en el prompt",
            skill!!.systemPrompt.contains("Ley de Hierro de la Verificación") ||
            skill.systemPrompt.contains("Evidence Before Claims")
        )
    }

    @Test
    fun testSubagentDrivenDevelopmentMandate() {
        val skill = repository.getSkillById("codex-subagent-driven-development")
        assertNotNull(skill)
        assertTrue(
            "Debe contemplar el patrón Implementador y Revisor",
            skill!!.systemPrompt.contains("Implementador") &&
            skill.systemPrompt.contains("Revisor")
        )
    }

    @Test
    fun testCategoriesIncludeClaudeAndCodex() {
        val categories = repository.getCategories()
        assertTrue("Las categorías deben incluir 'Claude & Codex'", categories.contains("Claude & Codex"))
        assertTrue("Las categorías deben incluir 'Ingeniería'", categories.contains("Ingeniería"))
    }
}
