package com.codex.chat.repository

import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SkillInfo
import com.codex.chat.core.network.CodexPayloadBuilder
import com.codex.chat.core.repository.SkillsRepository
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class SkillsRepositoryTest {

    private lateinit var repository: SkillsRepository

    @Before
    fun setUp() {
        repository = SkillsRepository(context = null)
    }

    @Test
    fun testNativeSkillsArePopulated() {
        val skills = repository.getAllSkills()
        assertTrue("Debe cargar las skills nativas iniciales", skills.size >= 12)

        val debugging = repository.getSkillById("systematic-debugging")
        assertNotNull("Debe existir la skill systematic-debugging", debugging)
        assertEquals("Systematic Debugging", debugging!!.name)
        assertEquals("Anthropic & Codex", debugging.author)
        assertTrue("Debe incluir reglas de causa raíz", debugging.systemPrompt.contains("LA REGLA DE ORO"))

        val tdd = repository.getSkillById("test-driven-development")
        assertNotNull("Debe existir la skill TDD", tdd)
        assertEquals("TDD Implementer", tdd!!.name)
        assertTrue("Debe incluir directivas Red-Green-Refactor", tdd.systemPrompt.contains("Red-Green-Refactor"))

        val claudeMemory = repository.getSkillById("codebase-memory")
        assertNotNull("Debe existir la skill codebase-memory de Claude", claudeMemory)
        assertEquals("Claude", claudeMemory!!.category)

        val obsidian = repository.getSkillById("codebase-obsidian-mcp")
        assertNotNull("Debe existir la skill Obsidian MCP", obsidian)
    }

    @Test
    fun testCategoriesListIncludesCoreCategories() {
        val categories = repository.getCategories()
        assertTrue("Debe incluir categoría Todas", categories.contains("Todas"))
        assertTrue("Debe incluir categoría Activas", categories.contains("Activas"))
        assertTrue("Debe incluir categoría Claude & Codex", categories.contains("Claude & Codex"))
        assertTrue("Debe incluir categoría Ingeniería", categories.contains("Ingeniería"))
        assertTrue("Debe incluir categoría Ciberseguridad", categories.contains("Ciberseguridad"))
        assertTrue("Debe incluir categoría Personalizadas", categories.contains("Personalizadas"))
    }

    @Test
    fun testCustomSkillCreationAndDeletion() {
        val custom = SkillInfo(
            id = "custom-perf-test",
            name = "Micro-benchmark Runner",
            description = "Ejecución de benchmarks en nanosegundos",
            category = "Personalizadas",
            systemPrompt = "Mide CPU cycles y allocs por operacion",
            iconEmoji = "⚡",
            author = "Usuario",
            isCustom = true
        )

        repository.addCustomSkill(custom)

        val retrieved = repository.getSkillById("custom-perf-test")
        assertNotNull("Debe recuperar la skill personalizada agregada", retrieved)
        assertEquals("Micro-benchmark Runner", retrieved!!.name)
        assertTrue("Debe marcarse como personalizada", retrieved.isCustom)

        val deleted = repository.deleteCustomSkill("custom-perf-test")
        assertTrue("Debe retornar true al eliminar la skill", deleted)
        assertNull("Ya no debe existir la skill eliminada", repository.getSkillById("custom-perf-test"))
    }

    @Test
    fun testSkillInjectionIntoSystemPrompt() {
        val skill = SkillInfo(
            id = "test-security",
            name = "Security Hardening Specialist",
            description = "Auditoría estricta de seguridad",
            category = "Ciberseguridad",
            systemPrompt = "REGLA: Rechaza tokens inseguros y sanitiza cada buffer.",
            iconEmoji = "🔐",
            author = "Anthropic & Codex"
        )

        val prompt = CodexPayloadBuilder.buildSystemPrompt(activeSkill = skill)
        assertTrue("Debe contener el nombre de la skill", prompt.contains("Active Native Skill (Security Hardening Specialist"))
        assertTrue("Debe contener el autor", prompt.contains("Anthropic & Codex"))
        assertTrue("Debe contener las instrucciones operativas", prompt.contains("REGLA: Rechaza tokens inseguros"))
        assertTrue("Debe contener fecha actual", prompt.contains("Current date:"))
    }
}
