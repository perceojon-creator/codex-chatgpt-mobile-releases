package com.codex.chat.core.repository

import android.content.Context
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SkillInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

class SkillsRepository(private val context: Context? = null) {

    private val customSkillsFile: File? = context?.let { File(it.filesDir, "custom_skills.json") }
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private val nativeSkills = mutableListOf(
        SkillInfo(
            id = "systematic-debugging",
            name = "Systematic Debugging",
            description = "Diagnóstico exhaustivo y causa raíz antes de proponer cualquier solución. Principio de cero adivinanzas.",
            category = "Claude & Codex",
            systemPrompt = """Eres un Staff Debugging Engineer operando bajo el protocolo de Depuración Sistemática:
1. LA REGLA DE ORO: Ninguna corrección sin antes aislar la causa raíz verificada.
2. Analiza trazas de pila, dumps de memoria, tiempos de ejecución y condiciones de carrera.
3. Formula hipótesis falsables y descarta una a una con evidencia concluyente antes de tocar código.""",
            iconEmoji = "🔍",
            author = "Anthropic & Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SkillInfo(
            id = "test-driven-development",
            name = "TDD Implementer",
            description = "Desarrollo guiado por pruebas rigurosas, cobertura de casos límite, caminos de fallo y métricas empíricas.",
            category = "Ingeniería",
            systemPrompt = """Eres un Senior TDD Specialist. Sigues el ciclo estricto Red-Green-Refactor:
1. Escribe la prueba unitaria o de integración verificando la falla esperada.
2. Diseña la implementación mínima que satisfaga la aserción sin excesos ni stubs vacíos.
3. Valida caminos felices, entradas nulas o malformadas y métricas de rendimiento estadístico.""",
            iconEmoji = "🧪",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "system-architect",
            name = "System Architect",
            description = "Descomposición modular limpia, diseño de contratos de interfaz, concurrencia y tolerancia a fallos sin monolitos.",
            category = "Ingeniería",
            systemPrompt = """Eres un Principal Software Architect. Principio de Descomposición Autónoma:
1. NUNCA comprimas lógica compleja en scripts monolíticos o archivos sobrecargados.
2. Separa dominio, red, persistencia y vistas en capas con contratos fuertemente tipados.
3. Diseña interfaces resilientes ante desconexión, desbordamiento y sincronización asíncrona.""",
            iconEmoji = "🏛️",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SkillInfo(
            id = "adversarial-reviewer",
            name = "Adversarial Reviewer",
            description = "Auditoría implacable: fugas de memoria, seguridad, condiciones de carrera y validación anti-stubs.",
            category = "Ingeniería",
            systemPrompt = """Eres un Auditor Adversarial y Abogado del Diablo:
1. Inspecciona cada línea buscando desbordamientos de buffer, descriptores abiertos sin cerrar y datos hardcodeados.
2. Evalúa estrés bajo alta concurrencia, pérdidas de conectividad y desincronización de hilos.
3. Exige evidencia verificable antes de validar cualquier afirmación técnica.""",
            iconEmoji = "🛡️",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SkillInfo(
            id = "codebase-memory",
            name = "Codebase Knowledge Graph",
            description = "Mapeo y navegación estructural de dependencias, grafos de llamadas, símbolos y entidades en repositorios.",
            category = "Claude",
            systemPrompt = """Eres el motor de memoria de código y grafo de conocimiento (Codebase Knowledge Graph):
1. Rastrea definiciones, usos, flujo de datos y dependencias cíclicas entre módulos.
2. Identifica puntos de impacto y efectos colaterales antes de refactorizar cualquier contrato.
3. Mantén una vista estructural coherente de la arquitectura del proyecto.""",
            iconEmoji = "🧠",
            author = "Anthropic Claude",
            defaultModel = "astra",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "codebase-obsidian-mcp",
            name = "Obsidian MCP Bridge",
            description = "Sincronización bidireccional entre investigación técnica, grafo de código y notas en Markdown estructurado.",
            category = "Claude",
            systemPrompt = """Eres un Investigador Técnico con persistencia en Obsidian Vault:
1. Convierte análisis y descubrimientos en notas técnicas modulares con frontmatter YAML.
2. Enlaza conceptos mediante wikilinks [[Concepto]] y diagramas de flujo claros.
3. Documenta decisiones de arquitectura (ADRs) con contexto, pros y contras.""",
            iconEmoji = "📓",
            author = "Anthropic Claude",
            defaultModel = "astra",
            reasoningEffort = ReasoningEffort.MEDIUM
        ),
        SkillInfo(
            id = "caveman",
            name = "Caveman Compression",
            description = "Respuestas en máxima densidad informativa, directo al grano, cero relleno ni cortesías innecesarias.",
            category = "Productividad",
            systemPrompt = """Modo Caveman activo: Máxima densidad de información.
- Suprime cortesías, introducciones y despedidas.
- Responde directamente con los hechos, código o respuestas en formato compacto.
- Cero redundancia, precisión técnica al 100%.""",
            iconEmoji = "🪨",
            author = "Anthropic Claude",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.LOW
        ),
        SkillInfo(
            id = "security-auditor",
            name = "OWASP Security Auditor",
            description = "Auditoría de ciberseguridad, prevención de inyecciones, criptografía, desinfección y hardening.",
            category = "Ciberseguridad",
            systemPrompt = """Eres un Principal Application Security Engineer:
1. Evalúa vulnerabilidades bajo el estándar OWASP Top 10 y MITRE ATT&CK.
2. Verifica validación de límites, desinfección de entradas y permisos estrictos de sandbox.
3. Garantiza el uso de algoritmos criptográficos modernos y almacenamiento seguro de credenciales.""",
            iconEmoji = "🔐",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "database-architect",
            name = "Database & Storage Engineer",
            description = "Modelado relacional y NoSQL, transacciones ACID, índices B-Tree/LSM y consistencia eventual.",
            category = "Ingeniería",
            systemPrompt = """Eres un Principal Database Engineer:
1. Diseña esquemas relacionales normalizados y modelos documentales eficientes.
2. Optimiza planes de ejecución de consultas, índices compuestos y contención de bloqueos.
3. Asegura persistencia atómica, durabilidad y transaccionalidad sin corrupción de datos.""",
            iconEmoji = "💾",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "performance-profiler",
            name = "Performance Profiler",
            description = "Métricas estadísticas reales, latencias percentiles p50/p90/p99 y optimización de throughput.",
            category = "Ingeniería",
            systemPrompt = """Eres un Performance Engineering Specialist:
1. Exige y calcula métricas empíricas: operaciones por segundo y percentiles de latencia (min, p50, p90, p99).
2. Identifica cuellos de botella en I/O, recolección de basura y asignación excesiva de memoria.
3. Optimiza algoritmos hacia la complejidad asintótica óptima.""",
            iconEmoji = "📊",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "brainstorming",
            name = "Creative Brainstorming",
            description = "Exploración multidimensional de ideas, análisis de viabilidad, pros/contras y síntesis convergente.",
            category = "Productividad",
            systemPrompt = """Eres un Facilitador de Innovación y Diseño Estratégico:
1. Genera múltiples perspectivas y soluciones alternativas antes de converger.
2. Analiza viabilidad técnica, riesgos operativos y valor para el usuario final.
3. Estructura las mejores opciones con matrices de decisión claras.""",
            iconEmoji = "💡",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "watch-video",
            name = "Watch Video Analyst",
            description = "Análisis e inspección multimodal de video, extracción de transcripciones y síntesis semántica.",
            category = "Claude",
            systemPrompt = """Eres un Analista Multimodal de Video y Transcripciones:
1. Procesa y correlaciona líneas de tiempo, subtítulos y elementos visuales clave.
2. Genera resúmenes ejecutivos estructurados por capítulos y marcas de tiempo.
3. Extrae conclusiones técnicas y citas textuales relevantes con precisión.""",
            iconEmoji = "🎥",
            author = "Anthropic Claude",
            defaultModel = "astra",
            reasoningEffort = ReasoningEffort.HIGH
        )
    )

    private val customSkills = mutableListOf<SkillInfo>()
    private val remoteSkills = mutableListOf<SkillInfo>()

    init {
        loadCustomSkills()
    }

    private fun loadCustomSkills() {
        if (customSkillsFile == null || !customSkillsFile.exists()) return
        try {
            val text = customSkillsFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(text)
            synchronized(customSkills) {
                customSkills.clear()
                for (i in 0 until jsonArray.length()) {
                    val obj = jsonArray.getJSONObject(i)
                    customSkills.add(
                        SkillInfo(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            description = obj.optString("description", ""),
                            category = obj.optString("category", "Personalizado"),
                            systemPrompt = obj.getString("system_prompt"),
                            iconEmoji = obj.optString("icon_emoji", "⚡"),
                            author = obj.optString("author", "Usuario"),
                            isInstalled = obj.optBoolean("is_installed", true),
                            isCustom = true,
                            defaultModel = obj.optString("default_model", "gpt-5.6-sol"),
                            reasoningEffort = ReasoningEffort.fromString(obj.optString("reasoning_effort", "high"))
                        )
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun saveCustomSkills() {
        if (customSkillsFile == null) return
        try {
            val array = JSONArray()
            synchronized(customSkills) {
                for (s in customSkills) {
                    val obj = JSONObject().apply {
                        put("id", s.id)
                        put("name", s.name)
                        put("description", s.description)
                        put("category", s.category)
                        put("system_prompt", s.systemPrompt)
                        put("icon_emoji", s.iconEmoji)
                        put("author", s.author)
                        put("is_installed", s.isInstalled)
                        put("default_model", s.defaultModel)
                        put("reasoning_effort", s.reasoningEffort.value)
                    }
                    array.put(obj)
                }
            }
            customSkillsFile.writeText(array.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllSkills(): List<SkillInfo> = synchronized(this) {
        val list = mutableListOf<SkillInfo>()
        list.addAll(nativeSkills)
        list.addAll(remoteSkills)
        list.addAll(customSkills)
        list
    }

    fun getSkillById(id: String): SkillInfo? = synchronized(this) {
        getAllSkills().firstOrNull { it.id == id }
    }

    fun getCategories(): List<String> = synchronized(this) {
        val categories = linkedSetOf("Todas", "Activas", "Claude & Codex", "Ingeniería", "Ciberseguridad", "Productividad", "Personalizadas")
        getAllSkills().forEach { categories.add(it.category) }
        categories.toList()
    }

    fun addCustomSkill(skill: SkillInfo) = synchronized(this) {
        val custom = skill.copy(isCustom = true, category = "Personalizadas")
        customSkills.removeAll { it.id == custom.id }
        customSkills.add(0, custom)
        saveCustomSkills()
    }

    fun deleteCustomSkill(id: String): Boolean = synchronized(this) {
        val removed = customSkills.removeAll { it.id == id }
        if (removed) saveCustomSkills()
        removed
    }

    /**
     * Sincroniza en tiempo real las skills instaladas en el PC (~/.codex/skills y ~/.claude/skills)
     */
    fun syncWithPcServer(baseUrl: String): Pair<Boolean, Int> {
        val cleanBase = baseUrl.trim().removeSuffix("/").removeSuffix("/v1")
        val url = cleanBase + "/api/skills"
        try {
            val request = Request.Builder().url(url).get().build()
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return Pair(false, 0)

            val json = JSONObject(response.body?.string() ?: "{}")
            val items = json.optJSONArray("skills") ?: return Pair(true, 0)

            val newRemote = mutableListOf<SkillInfo>()
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                val id = obj.getString("id")
                // Avoid duplicating built-in skills if they already exist
                if (nativeSkills.any { it.id == id }) continue

                newRemote.add(
                    SkillInfo(
                        id = id,
                        name = obj.getString("name"),
                        description = obj.optString("description", "Skill sincronizada de PC"),
                        category = obj.optString("category", "Claude & Codex"),
                        systemPrompt = obj.getString("system_prompt"),
                        iconEmoji = obj.optString("icon_emoji", "⚡"),
                        author = obj.optString("author", "PC Desktop"),
                        isInstalled = true,
                        isCustom = false
                    )
                )
            }

            synchronized(this) {
                remoteSkills.clear()
                remoteSkills.addAll(newRemote)
            }
            return Pair(true, newRemote.size)
        } catch (e: Exception) {
            return Pair(false, 0)
        }
    }
}
