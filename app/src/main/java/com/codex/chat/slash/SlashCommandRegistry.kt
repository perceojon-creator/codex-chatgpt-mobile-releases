package com.codex.chat.slash

import com.codex.chat.core.model.SlashActionType
import com.codex.chat.core.model.SlashCommandInfo
import com.codex.chat.core.model.SkillInfo

/**
 * Registro centralizado de comandos slash para el chat.
 *
 * Extraído de MainActivity (Auditoria v1.0.79 Fase 4 – Task 15).
 * Antes: tres funciones privadas en MainActivity con ciclomático ~42 combinado.
 * Ahora: objeto independiente, sin estado, 100% testeable en JVM sin framework Android.
 *
 * MainActivity pasa un [SlashDelegate] para las acciones que requieren contexto de UI.
 */
object SlashCommandRegistry {

    /** Comandos de sistema fijos, sin dependencia de habilidades dinámicas. */
    fun getSystemCommands(): List<SlashCommandInfo> = listOf(
        SlashCommandInfo("/connectors", "Gestor de Conectores (Google Flow, Imagen, Veo)", "🔌", "CONECTOR", SlashActionType.OPEN_CONNECTORS),
        SlashCommandInfo("/flow",        "Google Flow: Generar imagen o video con IA",       "🌊", "FLOW",    SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/imagen",      "Google Flow Imagen 3.1: Generar imagen",            "🎨", "IMAGEN",  SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/veo",         "Google Flow Veo 3.1: Generar video",                "🎬", "VEO",     SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/skills",      "Abrir la Tienda Oficial de Skills",                 "🧭", "STORE",   SlashActionType.OPEN_STORE),
        SlashCommandInfo("/mcp",         "Administrador de Servidores MCP Nativos",           "🔌", "MCP",     SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/mcp store",   "Imprimir Tienda de Servidores MCP (Claude)",        "🏪", "STORE",   SlashActionType.EXECUTE_INSTANT),
        SlashCommandInfo("/mcp tools",   "Listar herramientas MCP nativas activas",           "🛠️", "MCP",     SlashActionType.EXECUTE_INSTANT),
        SlashCommandInfo("/permissions", "Estado y concesión de todos los permisos del APK",  "🛡️", "PERMS",   SlashActionType.EXECUTE_INSTANT),
        SlashCommandInfo("/battery",     "Consultar batería y hardware del móvil",            "🔋", "MCP",     SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/device",      "Consultar telemetría de hardware Android",          "📱", "MCP",     SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/memory",      "Memoria persistente de hechos e IA",               "🧠", "MCP",     SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/calc",        "Calculadora matemática y utilidades",               "🧮", "MCP",     SlashActionType.AUTOCOMPLETE),
        SlashCommandInfo("/install",     "Instalar skill desde GitHub o URL",                 "📥", "INSTALL", SlashActionType.INSTALL_SKILL_DIALOG),
        SlashCommandInfo("/unskill",     "Desactivar la skill activa actual",                 "❌", "CLEAR",   SlashActionType.EXECUTE_INSTANT),
        SlashCommandInfo("/clear",       "Limpiar mensajes e iniciar nuevo chat",             "🧹", "RESET",   SlashActionType.CLEAR_CHAT),
        SlashCommandInfo("/help",        "Ver comandos disponibles",                          "❓", "HELP",    SlashActionType.AUTOCOMPLETE)
    )

    /**
     * Prefijos que se eliminan del skill ID para construir el comando corto.
     */
    private val SKILL_ID_PREFIXES = listOf(
        "codex-", "anthropic-", "devops-", "security-",
        "fullstack-", "ai-", "style-", "c-level-"
    )

    /**
     * Convierte una lista de [SkillInfo] en comandos slash dinámicos.
     */
    fun skillsToCommands(skills: List<SkillInfo>): List<SlashCommandInfo> =
        skills.map { skill ->
            val shortId = SKILL_ID_PREFIXES.fold(skill.id) { acc, prefix ->
                acc.removePrefix(prefix)
            }
            SlashCommandInfo(
                command     = "/$shortId",
                description = skill.name + " • " + skill.category,
                iconEmoji   = skill.iconEmoji,
                badge       = "SKILL",
                actionType  = SlashActionType.EXECUTE_INSTANT,
                targetSkillId = skill.id
            )
        }

    /**
     * Filtra la lista combinada (sistema + skills) según el texto introducido.
     *
     * @param input Texto actual del campo de mensaje (debe empezar con '/').
     * @param skills Lista de habilidades instaladas (para los comandos dinámicos).
     * @param maxResults Número máximo de sugerencias a devolver.
     * @return Lista filtrada de sugerencias, vacía si la entrada no aplica.
     */
    fun filterSuggestions(
        input: String,
        skills: List<SkillInfo> = emptyList(),
        maxResults: Int = 7
    ): List<SlashCommandInfo> {
        if (!input.startsWith("/") || input.contains(" ")) return emptyList()

        val query = input.removePrefix("/").trim().lowercase()
        val all   = getSystemCommands() + skillsToCommands(skills)

        return if (query.isEmpty()) {
            all.take(maxResults)
        } else {
            all.filter {
                it.command.lowercase().contains(query) ||
                it.description.lowercase().contains(query)
            }.take(maxResults)
        }
    }

    /**
     * Interfaz de delegado para las acciones que requieren contexto de UI (Activity).
     * Se implementa en MainActivity de forma anónima para mantener el registry sin estado.
     */
    interface SlashDelegate {
        fun showConnectors()
        fun showSkillStore()
        fun showPermissions()
        fun showMcpManager()
        fun showInstallSkillDialog()
        fun showRootStatus(arg: String)
        fun startNewChat()
        fun deactivateSkill()
        fun printMcpStore()
        fun printMcpTools()
        fun installMcpServer(url: String)
        fun callMcpTool(toolAndArgs: String)
        fun generateMedia(prompt: String, provider: String, type: String)
        fun executePython(code: String)
        fun activateSkillById(skillId: String, directArg: String)
        fun saveMcpMemory(key: String, value: String)
        fun getMcpMemory(key: String)
        fun listMcpMemories()
        fun evaluateMath(expression: String)
        fun showHelp()
    }

    /**
     * Enruta un comando slash completo hacia la acción correcta a través del [delegate].
     *
     * @param commandText Texto completo del campo de mensaje (incluyendo el '/').
     * @param delegate    Implementación de [SlashDelegate] provista por MainActivity.
     * @param skillLookup Función que busca una skill por nombre/id corto.
     * @return true si el comando fue manejado, false si no era un comando reconocido.
     */
    fun handle(
        commandText: String,
        delegate: SlashDelegate,
        skillLookup: (rawId: String) -> SkillInfo? = { null }
    ): Boolean {
        val trimmed = commandText.trim()
        if (trimmed.isEmpty() || trimmed == "/") return false

        val parts = trimmed.split("\\s+".toRegex(), limit = 2)
        val cmd = parts[0].lowercase()
        val arg = if (parts.size > 1) parts[1].trim() else ""

        when {
            cmd == "/connectors" || cmd == "/conectores" || cmd == "/flow-hub" -> {
                delegate.showConnectors(); return true
            }
            cmd == "/flow" || cmd == "/googleflow" -> {
                if (arg.isBlank()) delegate.showConnectors()
                else delegate.generateMedia(arg, "GOOGLE_FLOW", "IMAGE")
                return true
            }
            cmd == "/imagen" || cmd == "/image" || cmd == "/draw" -> {
                if (arg.isBlank()) delegate.showConnectors()
                else delegate.generateMedia(arg, "GOOGLE_FLOW", "IMAGE")
                return true
            }
            cmd == "/veo" || cmd == "/video" || cmd == "/animate" -> {
                if (arg.isBlank()) delegate.showConnectors()
                else delegate.generateMedia(arg, "GOOGLE_FLOW", "VIDEO")
                return true
            }
            cmd == "/skills" || cmd == "/store" -> {
                delegate.showSkillStore(); return true
            }
            cmd == "/permissions" || cmd == "/perm" || cmd == "/perms" -> {
                delegate.showPermissions(); return true
            }
            cmd == "/mcp" || cmd == "/tools" || cmd == "/mcp-store" || cmd == "/mcpstore" -> {
                when {
                    cmd == "/mcp-store" || cmd == "/mcpstore" ||
                    arg in listOf("store", "claude", "market", "tienda", "catalog") ->
                        delegate.printMcpStore()
                    arg == "tools" || arg == "list" ->
                        delegate.printMcpTools()
                    arg.startsWith("install ") ->
                        delegate.installMcpServer(arg.removePrefix("install ").trim())
                    arg.startsWith("call ") ->
                        delegate.callMcpTool(arg.removePrefix("call ").trim())
                    else ->
                        delegate.showMcpManager()
                }
                return true
            }
            cmd == "/battery"  -> { delegate.callMcpTool("get_battery_status");  return true }
            cmd == "/device"   -> { delegate.callMcpTool("get_device_telemetry"); return true }
            cmd == "/memory"   -> {
                when {
                    arg.isEmpty()          -> delegate.listMcpMemories()
                    arg.startsWith("save ") -> {
                        val payload  = arg.removePrefix("save ").trim()
                        val partsKv  = payload.split("=", ":", limit = 2)
                        val k = partsKv[0].trim()
                        val v = if (partsKv.size > 1) partsKv[1].trim() else ""
                        delegate.saveMcpMemory(k, v)
                    }
                    else -> delegate.getMcpMemory(arg)
                }
                return true
            }
            cmd == "/calc" || cmd == "/calculate" -> {
                delegate.evaluateMath(if (arg.isEmpty()) "2^10 + 24" else arg)
                return true
            }
            cmd == "/root" || cmd == "/su" -> {
                delegate.showRootStatus(arg); return true
            }
            cmd == "/py" || cmd == "/python" || cmd == "/e2b" -> {
                delegate.executePython(if (arg.isEmpty()) "print('¡Hola desde Python en E2B Cloud MicroVM!')" else arg)
                return true
            }
            cmd == "/unskill" || cmd == "/noskill" -> {
                delegate.deactivateSkill(); return true
            }
            cmd == "/clear" || cmd == "/new" -> {
                delegate.startNewChat(); return true
            }
            cmd == "/install" || cmd == "/install-skill" || cmd == "/add-skill" -> {
                if (arg.isEmpty()) delegate.showInstallSkillDialog()
                else delegate.callMcpTool(arg)   // reuse callMcpTool for direct skill URL
                return true
            }
            cmd == "/help" -> {
                delegate.showHelp(); return true
            }
            else -> {
                val rawId = cmd.removePrefix("/")
                if (rawId.length >= 2) {
                    val skill = skillLookup(rawId)
                    if (skill != null) {
                        delegate.activateSkillById(skill.id, arg)
                        return true
                    }
                }
            }
        }
        return false
    }
}
