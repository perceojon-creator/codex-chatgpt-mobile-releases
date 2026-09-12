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

    // Core fallback skills (always present even in unit tests with no Android Context)
    private val coreNativeSkills = mutableListOf(
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
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "adversarial-reviewer",
            name = "Adversarial Code Reviewer",
            description = "El Abogado del Diablo: busca fugas de memoria, condiciones de carrera, errores de precisión y casos extremos.",
            category = "Ingeniería",
            systemPrompt = """Eres un Revisor Adversarial de Código (The Devil's Advocate Protocol):
1. Audita seguridad de memoria: fugas de handles, buffers desbordados, sockets sin liberar.
2. Audita concurrencia: carreras críticas, bloqueos mutuos (deadlocks), reentrancia insegura.
3. Audita resiliencia: entradas de longitud cero, fragmentación de paquetes y límites numéricos.""",
            iconEmoji = "⚔️",
            author = "Codex Apex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SkillInfo(
            id = "codebase-memory",
            name = "Codebase Memory & Graph Navigator",
            description = "Gestión de memoria de proyecto, mapeo de relaciones entre módulos y mantenimiento de contexto de largo plazo.",
            category = "Claude & Codex",
            systemPrompt = """Eres el Especialista en Memoria y Grafo del Proyecto:
1. Mapea la arquitectura de archivos, dependencias entre módulos y responsabilidades de cada componente.
2. Mantén un registro mental de las decisiones técnicas tomadas en turnos anteriores para garantizar coherencia.
3. Detecta código muerto, rutas duplicadas o desincronizadas entre capas.""",
            iconEmoji = "🧠",
            author = "Claude Team",
            defaultModel = "claude-sonnet-4-6",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "codebase-obsidian-mcp",
            name = "Obsidian Vault & Knowledge Architect",
            description = "Estructuración de bóvedas Obsidian, enlaces bidireccionales [[WikiLinks]], mapas de contenido (MOCs) y zettelkasten.",
            category = "Productividad",
            systemPrompt = """Eres el Arquitecto de Conocimiento y Bóvedas Obsidian:
1. Estructura notas atómicas en Markdown con enlaces bidireccionales coherentes ([[Concepto]]).
2. Diseña Mapas de Contenido (MOCs) para conectar temas complejos de forma navegable.
3. Aplica metadatos YAML limpios (tags, date, status, aliases) compatibles con el plugin Dataview.""",
            iconEmoji = "💎",
            author = "Knowledge Guild",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.MEDIUM
        ),
        SkillInfo(
            id = "caveman-minimalist",
            name = "Caveman Minimalist",
            description = "Respuestas ultra concisas estilo cavernícola sabio. Cero rellenos, cero cortesías, código puro y directo.",
            category = "Estilo",
            systemPrompt = """DIRECTIVA INQUEBRANTABLE - MODO CAVERNÍCOLA (CAVEMAN):
Habla como cavernícola sabio.
Cero cortesías ("Hola", "Espero que estés bien").
Cero rellenos, cero introducciones, cero conclusiones floridas.
Frases cortas. Palabras directas. Código directo.
Brutalmente eficiente. Solo esencia pura.""",
            iconEmoji = "🦴",
            author = "Community",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.LOW
        ),
        SkillInfo(
            id = "owasp-top-10",
            name = "OWASP Top 10 Hardener",
            description = "Auditoría estricta contra inyecciones SQL/NoSQL, XSS, CSRF, autenticación rota y exposición de datos.",
            category = "Ciberseguridad",
            systemPrompt = """Eres un Auditor Especialista en Seguridad Aplicada y OWASP Top 10:
1. Analiza cada punto de entrada de datos: sanitización obligatoria, consultas parametrizadas, escape contextual.
2. Audita tokens JWT: algoritmos asimétricos (RS256/EdDSA), rotación de claves, revocación y expiración estricta.
3. Configura cabeceras HTTP de seguridad (CSP, HSTS, X-Frame-Options) y protección CSRF/CORS.""",
            iconEmoji = "🔐",
            author = "Security Guild",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "db-query-optimizer",
            name = "DB Query Optimizer",
            description = "Optimización de planes de ejecución EXPLAIN ANALYZE, indexación avanzada B-Tree/GIN y sharding.",
            category = "Ingeniería",
            systemPrompt = """Eres un Ingeniero Principal de Bases de Datos (PostgreSQL / Relacional):
1. Diseña esquemas normalizados con restricciones de integridad y tipos de datos precisos.
2. Planifica índices compuestos basados en patrones de consulta y costo de ejecución (EXPLAIN ANALYZE).
3. Previene cuellos de botella por locks de tablas, N+1 queries y transacciones no atómicas.""",
            iconEmoji = "⚡",
            author = "Data Guild",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "performance-profiler",
            name = "Performance & Latency Profiler",
            description = "Análisis de cuellos de botella en CPU/RAM, latencias p50/p90/p99, benchmarks y memory leak detection.",
            category = "Ingeniería",
            systemPrompt = """Eres un Ingeniero de Rendimiento de Sistemas de Alta Velocidad:
1. Analiza latencias midiendo percentiles reales (p50, p90, p99) y descartando promedios engañosos.
2. Diagnostica consumo de CPU y memoria: allocations innecesarias, garbage collection pauses y bloqueos de I/O.
3. Optimiza algoritmos reduciendo la complejidad computacional O(N) y el footprint de memoria.""",
            iconEmoji = "📊",
            author = "Codex Apex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.XHIGH
        ),
        SkillInfo(
            id = "brainstorming",
            name = "Brainstorming & Requirements Refiner",
            description = "Refinamiento socrático de requisitos técnicos, árboles de decisión y exploración de trade-offs antes de codificar.",
            category = "Claude & Codex",
            systemPrompt = """Eres un Arquitecto Facilitador de Requisitos Técnicos:
1. Ayuda al usuario a explorar el espacio del problema antes de elegir una implementación.
2. Plantea preguntas incisivas sobre escala, latencia aceptable, modelos de consistencia y modos de fallo.
3. Presenta alternativas técnicas con ventajas y desventajas objetivas para cada camino.""",
            iconEmoji = "💡",
            author = "OpenAI Codex",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.HIGH
        ),
        SkillInfo(
            id = "watch-video",
            name = "Watch Video Analyst",
            description = "Extracción y análisis técnico de videos, transcripciones de YouTube con yt-dlp y fotogramas clave.",
            category = "Productividad",
            systemPrompt = """Eres un Analista Multimedia y Extractor de Videos:
1. Procesa transcripciones extrayendo marcas de tiempo clave, conceptos técnicos y resúmenes ejecutivos.
2. Sintetiza demostraciones visuales y tutoriales en pasos de código reproducibles.
3. Resalta advertencias, versiones de herramientas y configuraciones mencionadas en el video.""",
            iconEmoji = "🎬",
            author = "Multimedia Guild",
            defaultModel = "gpt-5.6-sol",
            reasoningEffort = ReasoningEffort.MEDIUM
        )
    )

    private val customSkills = mutableListOf<SkillInfo>()
    private val pcSkills = mutableListOf<SkillInfo>()
    private val assetsOfficialSkills = mutableListOf<SkillInfo>()

    init {
        loadOfficialSkillsFromAssets()
        loadCustomSkills()
    }

    private fun loadOfficialSkillsFromAssets() {
        if (context == null) return
        try {
            val jsonString = context.assets.open("official_skills.json").bufferedReader().use { it.readText() }
            val array = JSONArray(jsonString)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", "")
                val name = obj.optString("name", "Habilidad")
                val description = obj.optString("description", "")
                val category = obj.optString("category", "General")
                val systemPrompt = obj.optString("systemPrompt", "")
                val iconEmoji = obj.optString("iconEmoji", "⚡")
                val author = obj.optString("author", "Oficial")
                val defaultModel = obj.optString("defaultModel", "gpt-5.6-sol")

                if (id.isNotEmpty() && systemPrompt.isNotEmpty()) {
                    assetsOfficialSkills.add(
                        SkillInfo(
                            id = id,
                            name = name,
                            description = description,
                            category = category,
                            systemPrompt = systemPrompt,
                            iconEmoji = iconEmoji,
                            author = author,
                            defaultModel = defaultModel,
                            reasoningEffort = ReasoningEffort.HIGH,
                            isInstalled = true,
                            isCustom = false
                        )
                    )
                }
            }
        } catch (e: Exception) {
            // Assets not found or error parsing
        }
    }

    private fun loadCustomSkills() {
        customSkills.clear()
        val file = customSkillsFile ?: return
        if (!file.exists()) return

        try {
            val content = file.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                customSkills.add(
                    SkillInfo(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        description = obj.optString("description", ""),
                        category = obj.optString("category", "Personalizadas"),
                        systemPrompt = obj.getString("system_prompt"),
                        iconEmoji = obj.optString("icon_emoji", "⚡"),
                        author = obj.optString("author", "Usuario"),
                        defaultModel = obj.optString("default_model", "gpt-5.6-sol"),
                        reasoningEffort = ReasoningEffort.fromString(obj.optString("reasoning_effort", "high")),
                        isInstalled = true,
                        isCustom = true
                    )
                )
            }
        } catch (e: Exception) {
            // Ignore parse errors on corrupted custom file
        }
    }

    private fun saveCustomSkills() {
        val file = customSkillsFile ?: return
        try {
            val array = JSONArray()
            for (skill in customSkills) {
                val obj = JSONObject()
                obj.put("id", skill.id)
                obj.put("name", skill.name)
                obj.put("description", skill.description)
                obj.put("category", skill.category)
                obj.put("system_prompt", skill.systemPrompt)
                obj.put("icon_emoji", skill.iconEmoji)
                obj.put("author", skill.author)
                obj.put("default_model", skill.defaultModel)
                obj.put("reasoning_effort", skill.reasoningEffort.value)
                obj.put("is_custom", true)
                array.put(obj)
            }
            file.writeText(array.toString(2), Charsets.UTF_8)
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    fun getAllSkills(): List<SkillInfo> {
        val map = linkedMapOf<String, SkillInfo>()
        // 1. Assets official skills (if available) or core native skills
        if (assetsOfficialSkills.isNotEmpty()) {
            for (s in assetsOfficialSkills) map[s.id] = s
        }
        for (s in coreNativeSkills) {
            if (!map.containsKey(s.id)) map[s.id] = s
        }
        // 2. PC synced skills
        for (s in pcSkills) map[s.id] = s
        // 3. User custom skills
        for (s in customSkills) map[s.id] = s

        return map.values.toList()
    }

    fun getSkillById(id: String): SkillInfo? {
        return getAllSkills().find { it.id == id }
    }

    fun getCategories(): List<String> {
        val all = getAllSkills()
        val cats = linkedSetOf("Todas", "Activas", "Claude & Codex", "Oficial Anthropic", "Ingeniería", "DevOps & Cloud", "Ciberseguridad", "IA & MCP", "Frontend", "C-Level & Producto", "Productividad", "Mis Skills")
        for (s in all) {
            if (s.category.isNotBlank() && !cats.contains(s.category)) {
                cats.add(s.category)
            }
        }
        return cats.toList()
    }

    fun addCustomSkill(skill: SkillInfo) {
        customSkills.removeAll { it.id == skill.id }
        customSkills.add(0, skill.copy(isCustom = true, isInstalled = true))
        saveCustomSkills()
    }

    fun deleteCustomSkill(skillId: String): Boolean {
        val removed = customSkills.removeAll { it.id == skillId }
        if (removed) saveCustomSkills()
        return removed
    }

    fun syncWithPcServer(serverBaseUrl: String): Pair<Boolean, Int> {
        val cleanUrl = serverBaseUrl.trimEnd('/')
        val targetUrl = "$cleanUrl/api/skills"

        return try {
            val req = Request.Builder().url(targetUrl).get().build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return Pair(false, 0)
            }
            val body = resp.body?.string() ?: return Pair(false, 0)
            val root = JSONObject(body)
            val array = root.optJSONArray("skills") ?: return Pair(true, 0)

            val parsed = mutableListOf<SkillInfo>()
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                parsed.add(
                    SkillInfo(
                        id = item.optString("id", "pc-skill-$i"),
                        name = item.optString("name", "Skill PC"),
                        description = item.optString("description", ""),
                        category = item.optString("category", "Claude & Codex"),
                        systemPrompt = item.optString("system_prompt", ""),
                        iconEmoji = item.optString("icon_emoji", "💻"),
                        author = item.optString("author", "PC Local"),
                        defaultModel = "gpt-5.6-sol",
                        reasoningEffort = ReasoningEffort.HIGH,
                        isInstalled = true,
                        isCustom = false
                    )
                )
            }

            pcSkills.clear()
            pcSkills.addAll(parsed)
            Pair(true, parsed.size)
        } catch (e: Exception) {
            Pair(false, 0)
        }
    }

    fun installSkillFromUrl(inputUrl: String): Pair<Boolean, String> {
        var cleanUrl = inputUrl.trim()
        if (cleanUrl.isEmpty()) return Pair(false, "URL vacía")

        // Transform github web URLs to raw URLs
        if (cleanUrl.contains("github.com") && !cleanUrl.contains("raw.githubusercontent.com")) {
            cleanUrl = cleanUrl
                .replace("github.com", "raw.githubusercontent.com")
                .replace("/blob/", "/")
                .replace("/tree/", "/")
        }

        // If it's a shorthand like "anthropics/skills/skills/webapp-testing"
        if (!cleanUrl.startsWith("http://") && !cleanUrl.startsWith("https://")) {
            if (cleanUrl.contains("/")) {
                cleanUrl = "https://raw.githubusercontent.com/$cleanUrl"
                if (!cleanUrl.endsWith("SKILL.md")) {
                    cleanUrl = cleanUrl.trimEnd('/') + "/SKILL.md"
                }
            } else {
                // If single name like "webapp-testing" or "mcp-builder", try official anthropics repo
                cleanUrl = "https://raw.githubusercontent.com/anthropics/skills/main/skills/$cleanUrl/SKILL.md"
            }
        }

        if (cleanUrl.endsWith("/") || !cleanUrl.endsWith(".md")) {
            if (!cleanUrl.endsWith("SKILL.md")) {
                cleanUrl = cleanUrl.trimEnd('/') + "/SKILL.md"
            }
        }

        return try {
            val req = Request.Builder().url(cleanUrl).get().build()
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                return Pair(false, "HTTP " + resp.code + ": No se pudo descargar el archivo SKILL.md")
            }
            val content = resp.body?.string()?.trim() ?: return Pair(false, "Respuesta vacía")
            if (content.isEmpty()) return Pair(false, "El contenido de la skill está vacío")

            // Parse frontmatter
            var name = cleanUrl.split("/").dropLast(1).lastOrNull()?.replace("-", " ") ?: "Skill Instalada"
            var desc = "Instalada desde " + cleanUrl
            var systemPrompt = content

            if (content.startsWith("---")) {
                val endIdx = content.indexOf("---", 3)
                if (endIdx != -1) {
                    val frontmatter = content.substring(3, endIdx)
                    systemPrompt = content.substring(endIdx + 3).trim()
                    val lines = frontmatter.lines()
                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("name:")) {
                            name = trimmed.removePrefix("name:").trim().replace(""", "").replace("'", "")
                        } else if (trimmed.startsWith("description:")) {
                            desc = trimmed.removePrefix("description:").trim().replace(""", "").replace("'", "")
                        }
                    }
                }
            }

            val id = "installed-" + name.lowercase().replace("[^a-z0-9]+".toRegex(), "-").trim('-')
            val icon = when {
                name.contains("debug", ignoreCase = true) -> "🔍"
                name.contains("test", ignoreCase = true) || name.contains("tdd", ignoreCase = true) -> "🧪"
                name.contains("mcp", ignoreCase = true) -> "🔌"
                name.contains("art", ignoreCase = true) -> "🎨"
                name.contains("docker", ignoreCase = true) -> "🐳"
                name.contains("sec", ignoreCase = true) -> "🔐"
                name.contains("web", ignoreCase = true) -> "🌐"
                name.contains("data", ignoreCase = true) -> "📊"
                else -> "⚡"
            }

            val newSkill = SkillInfo(
                id = id,
                name = name.split(" ").joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
                description = desc,
                category = "Mis Skills",
                systemPrompt = systemPrompt,
                iconEmoji = icon,
                author = "GitHub / Claude Community",
                defaultModel = "gpt-5.6-sol",
                reasoningEffort = ReasoningEffort.HIGH,
                isInstalled = true,
                isCustom = true
            )

            addCustomSkill(newSkill)
            Pair(true, "Skill '" + newSkill.name + "' instalada exitosamente y lista para usar")
        } catch (e: Exception) {
            Pair(false, "Error: " + e.message)
        }
    }
}

