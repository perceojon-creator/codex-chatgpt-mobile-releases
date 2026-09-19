package com.codex.chat.slash

import com.codex.chat.core.model.SlashActionType
import com.codex.chat.core.model.SkillInfo
import org.junit.Assert.*
import org.junit.Test

class SlashCommandRegistryTest {

    @Test
    fun devuelve_lista_de_comandos_de_sistema_no_vacia() {
        val cmds = SlashCommandRegistry.getSystemCommands()
        assertTrue(cmds.size >= 10)
    }

    @Test
    fun todos_los_comandos_empiezan_con_barra() {
        SlashCommandRegistry.getSystemCommands().forEach { cmd ->
            assertTrue(cmd.command.startsWith("/"))
        }
    }

    @Test
    fun todos_los_comandos_tienen_descripcion() {
        SlashCommandRegistry.getSystemCommands().forEach { cmd ->
            assertTrue(cmd.description.isNotBlank())
        }
    }

    @Test
    fun clear_chat_tiene_accion_correcta() {
        val cmd = SlashCommandRegistry.getSystemCommands().first { it.command == "/clear" }
        assertEquals(SlashActionType.CLEAR_CHAT, cmd.actionType)
    }

    @Test
    fun open_store_tiene_accion_correcta() {
        val cmd = SlashCommandRegistry.getSystemCommands().first { it.command == "/skills" }
        assertEquals(SlashActionType.OPEN_STORE, cmd.actionType)
    }

    @Test
    fun skills_to_commands_elimina_prefijo_codex() {
        val skill = SkillInfo("codex-backend-engineer", "Backend Engineer", "desc", "Dev", "", "icon", "Test")
        val cmds = SlashCommandRegistry.skillsToCommands(listOf(skill))
        assertEquals("/backend-engineer", cmds[0].command)
    }

    @Test
    fun skills_to_commands_elimina_prefijo_anthropic() {
        val skill = SkillInfo("anthropic-reviewer", "Reviewer", "d", "Review", "", "eye", "T")
        val cmds = SlashCommandRegistry.skillsToCommands(listOf(skill))
        assertEquals("/reviewer", cmds[0].command)
    }

    @Test
    fun skills_to_commands_tiene_badge_skill_y_accion_execute() {
        val skill = SkillInfo("test", "T", "d", "C", "", "bolt", "A")
        val cmds = SlashCommandRegistry.skillsToCommands(listOf(skill))
        assertEquals("SKILL", cmds[0].badge)
        assertEquals(SlashActionType.EXECUTE_INSTANT, cmds[0].actionType)
    }

    @Test
    fun filtra_sugerencias_por_prefijo() {
        val result = SlashCommandRegistry.filterSuggestions("/cl")
        assertTrue(result.any { it.command == "/clear" })
    }

    @Test
    fun no_sugerencias_sin_barra() {
        assertTrue(SlashCommandRegistry.filterSuggestions("hola").isEmpty())
    }

    @Test
    fun no_sugerencias_con_espacio() {
        assertTrue(SlashCommandRegistry.filterSuggestions("/clear extra").isEmpty())
    }

    @Test
    fun maximo_7_con_query_vacio() {
        assertEquals(7, SlashCommandRegistry.filterSuggestions("/").size)
    }

    @Test
    fun filtro_insensible_a_mayusculas() {
        assertTrue(SlashCommandRegistry.filterSuggestions("/CALC").any { it.command == "/calc" })
    }

    @Test
    fun skills_vacias_no_lanza_excepcion() {
        assertNotNull(SlashCommandRegistry.filterSuggestions("/test", emptyList()))
    }

    private fun makeDelegate() = object : SlashCommandRegistry.SlashDelegate {
        val log = mutableListOf<String>()
        override fun showConnectors()          { log.add("showConnectors") }
        override fun showSkillStore()          { log.add("showSkillStore") }
        override fun showPermissions()         { log.add("showPermissions") }
        override fun showMcpManager()          { log.add("showMcpManager") }
        override fun showInstallSkillDialog()  { log.add("showInstallSkillDialog") }
        override fun showRootStatus(arg: String) { log.add("showRootStatus") }
        override fun startNewChat()            { log.add("startNewChat") }
        override fun deactivateSkill()         { log.add("deactivateSkill") }
        override fun printMcpStore()           { log.add("printMcpStore") }
        override fun printMcpTools()           { log.add("printMcpTools") }
        override fun installMcpServer(url: String) { log.add("installMcpServer") }
        override fun callMcpTool(ta: String)   { log.add("callMcpTool:" + ta) }
        override fun generateMedia(p: String, pr: String, t: String) { log.add("generateMedia:" + t) }
        override fun executePython(code: String) { log.add("executePython") }
        override fun activateSkillById(id: String, arg: String) { log.add("activateSkill") }
        override fun saveMcpMemory(k: String, v: String) { log.add("saveMcpMemory:" + k) }
        override fun getMcpMemory(key: String) { log.add("getMcpMemory") }
        override fun listMcpMemories()         { log.add("listMcpMemories") }
        override fun evaluateMath(expr: String) { log.add("evaluateMath") }
        override fun showHelp()                { log.add("showHelp") }
    }

    @Test fun clear_llama_startNewChat() {
        val d = makeDelegate()
        assertTrue(SlashCommandRegistry.handle("/clear", d))
        assertTrue(d.log.contains("startNewChat"))
    }

    @Test fun connectors_llama_showConnectors() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/connectors", d)
        assertTrue(d.log.contains("showConnectors"))
    }

    @Test fun imagen_con_arg_genera_imagen() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/imagen un gato", d)
        assertTrue(d.log.any { it.startsWith("generateMedia:IMAGE") })
    }

    @Test fun veo_con_arg_genera_video() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/veo amanecer", d)
        assertTrue(d.log.any { it.startsWith("generateMedia:VIDEO") })
    }

    @Test fun battery_llama_callMcpTool() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/battery", d)
        assertTrue(d.log.contains("callMcpTool:get_battery_status"))
    }

    @Test fun help_llama_showHelp() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/help", d)
        assertTrue(d.log.contains("showHelp"))
    }

    @Test fun unskill_llama_deactivateSkill() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/unskill", d)
        assertTrue(d.log.contains("deactivateSkill"))
    }

    @Test fun memory_save_extrae_clave() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/memory save clave=valor", d)
        assertTrue(d.log.any { it.startsWith("saveMcpMemory:clave") })
    }

    @Test fun calc_llama_evaluateMath() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/calc 2+2", d)
        assertTrue(d.log.contains("evaluateMath"))
    }

    @Test fun mcp_store_llama_printMcpStore() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/mcp store", d)
        assertTrue(d.log.contains("printMcpStore"))
    }

    @Test fun mcp_tools_llama_printMcpTools() {
        val d = makeDelegate()
        SlashCommandRegistry.handle("/mcp tools", d)
        assertTrue(d.log.contains("printMcpTools"))
    }

    @Test fun vacio_devuelve_false() {
        assertFalse(SlashCommandRegistry.handle("", makeDelegate()))
    }

    @Test fun solo_barra_devuelve_false() {
        assertFalse(SlashCommandRegistry.handle("/", makeDelegate()))
    }

    @Test fun desconocido_sin_skill_devuelve_false() {
        assertFalse(SlashCommandRegistry.handle("/xyz", makeDelegate()) { null })
    }

    @Test fun desconocido_con_skill_devuelve_true() {
        val d = makeDelegate()
        val sk = SkillInfo("backend", "B", "d", "D", "", "i", "A")
        assertTrue(SlashCommandRegistry.handle("/backend", d) { sk })
        assertTrue(d.log.any { it.startsWith("activateSkill") })
    }
}