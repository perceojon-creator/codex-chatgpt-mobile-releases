package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class MemoryMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-android-memory",
        name = "Local Memory & Knowledge Store (SQLite FTS5)",
        description = "Memoria persistente SQLite con FTS5 BM25, modo WAL y auto-mantenimiento para almacenar hechos, preferencias y directivas entre sesiones",
        iconEmoji = "🧠",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 11
    )

    companion object {
        private val TRIVIAL_PROMPTS = setOf(
            "hola", "hola!", "¿hola?", "buenas", "buenos dias", "buenas tardes", "buenas noches",
            "hi", "hello", "hey", "ok", "vale", "listo", "gracias", "muchas gracias",
            "thanks", "thank you", "si", "sí", "no", "bien", "perfecto", "entendido", "saludos"
        )

        /**
         * Determina si un prompt del usuario es trivial (saludo o confirmación simple)
         * para suprimir el overhead de RAG y búsquedas en base de datos.
         */
        fun isTrivialPrompt(prompt: String?): Boolean {
            if (prompt.isNullOrBlank()) return true
            val clean = prompt.trim().lowercase(java.util.Locale.ROOT).replace(Regex("[^a-záéíóúñ\\s]"), "").trim()
            return clean.isEmpty() || clean.length <= 2 || TRIVIAL_PROMPTS.contains(clean)
        }
    }

    private val memoryFile: File? = context?.let { File(it.filesDir, "mcp_memory.json") }
    private val memoryMap = linkedMapOf<String, String>()
    private val lock = Any()

    val sqliteStore: MemorySqliteStore? = try {
        context?.let { MemorySqliteStore.getInstance(it) }
    } catch (e: Throwable) {
        null
    }

    init {
        loadMemory()
    }

    private fun loadMemory() {
        val file = memoryFile ?: return
        if (!file.exists()) return
        try {
            val content = file.readText(Charsets.UTF_8).trim()
            if (content.isNotEmpty()) {
                val json = JSONObject(content)
                synchronized(lock) {
                    memoryMap.clear()
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val k = keys.next()
                        val v = json.optString(k, "")
                        memoryMap[k] = v
                        if (sqliteStore?.isAvailable == true) sqliteStore.save(k, v)
                    }
                }
            }
        } catch (e: Exception) {
            // Ignore corrupted memory file
        }
    }

    private fun persistMemory() {
        val file = memoryFile ?: return
        try {
            val json = JSONObject()
            synchronized(lock) {
                for ((k, v) in memoryMap) {
                    json.put(k, v)
                }
            }
            val parent = file.parentFile ?: return
            val tmp = File(parent, file.name + ".tmp")
            tmp.writeText(json.toString(2), Charsets.UTF_8)
            if (file.exists()) file.delete()
            tmp.renameTo(file)
        } catch (e: Exception) {
            // Log or ignore
        }
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "save_memory",
            description = "Guarda un dato, preferencia o hecho importante de forma persistente en la base de datos SQLite y el índice FTS5.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Identificador único de la memoria (ej. 'user_name', 'project_tech_stack')"))
                    put("value", JSONObject().put("type", "string").put("description", "Información detallada a recordar"))
                    put("category", JSONObject().put("type", "string").put("description", "Categoría opcional (ej. 'arquitectura', 'seguridad', 'preferencias')"))
                }
                put("properties", props)
                put("required", JSONArray().put("key").put("value"))
            }
        ),
        McpTool(
            name = "get_memory",
            description = "Recupera un dato específico almacenado previamente en la memoria local por su clave exacta.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Clave del dato a consultar"))
                }
                put("properties", props)
                put("required", JSONArray().put("key"))
            }
        ),
        McpTool(
            name = "search_memory",
            description = "Búsqueda semántica y de texto completo con FTS5 BM25 en la base de datos de memoria, devolviendo coincidencias relevantes y fragmentos contextuales.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Términos de búsqueda en lenguaje natural o palabras clave"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Número máximo de resultados (por defecto 10)"))
                }
                put("properties", props)
                put("required", JSONArray().put("query"))
            }
        ),
        McpTool(
            name = "list_memories",
            description = "Lista todos los datos y preferencias actualmente recordados en la memoria persistente del teléfono.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("category", JSONObject().put("type", "string").put("description", "Filtro opcional por categoría"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Límite de registros (por defecto 100)"))
                }
                put("properties", props)
            }
        ),
        McpTool(
            name = "delete_memory",
            description = "Elimina un dato específico de la memoria permanente y del índice FTS5.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("key", JSONObject().put("type", "string").put("description", "Clave a eliminar"))
                }
                put("properties", props)
                put("required", JSONArray().put("key"))
            }
        ),
        McpTool(
            name = "wal_status",
            description = "Reporta el estado del motor SQLite WAL: tamaño del WAL en MiB, modo de journal y conteo de registros.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "wal_truncate",
            description = "Ejecuta un checkpoint y truncamiento forzado del archivo WAL de SQLite para compactar el almacenamiento.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "set_persona",
            description = "Configura y activa una personalidad o rol persistente en SQLite para modular el comportamiento del modelo.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                val props = JSONObject().apply {
                    put("name", JSONObject().put("type", "string").put("description", "Identificador único de la personalidad (ej. 'technical', 'concise', 'academic')"))
                    put("style_prompt", JSONObject().put("type", "string").put("description", "Directiva de estilo o prompt de la personalidad a activar"))
                }
                put("properties", props)
                put("required", JSONArray().put("name").put("style_prompt"))
            }
        ),
        McpTool(
            name = "get_persona",
            description = "Consulta la personalidad actualmente activa y su directiva de estilo en SQLite.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "list_personas",
            description = "Lista todas las personalidades registradas en la base de datos persistente.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "reset_persona",
            description = "Restablece la personalidad activa volviendo a la configuración por defecto del sistema.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        return try {
            val args = try {
                JSONObject(call.argumentsJson)
            } catch (e: Exception) {
                JSONObject()
            }

            when (call.toolName) {
                "save_memory" -> {
                    val key = args.optString("key", "").trim()
                    val value = args.optString("value", "").trim()
                    val category = args.optString("category", "general").trim()
                    if (key.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Error: 'key' no puede estar vacía", isError = true)
                    }

                    synchronized(lock) {
                        memoryMap[key] = value
                    }
                    persistMemory()
                    if (sqliteStore?.isAvailable == true) sqliteStore.save(key, value, category)

                    McpToolResult(call.id, call.toolName, "✅ Memoria guardada exitosamente en SQLite FTS5: '$key' [$category] = '$value'")
                }

                "get_memory" -> {
                    val key = args.optString("key", "").trim()
                    val value = if (sqliteStore?.isAvailable == true) (sqliteStore.get(key) ?: synchronized(lock) { memoryMap[key] }) else synchronized(lock) { memoryMap[key] }
                    if (value != null) {
                        McpToolResult(call.id, call.toolName, "Memoria encontrada [$key]: $value")
                    } else {
                        McpToolResult(call.id, call.toolName, "No se encontró ningún registro para la clave '$key'.")
                    }
                }

                "search_memory" -> {
                    val query = args.optString("query", "").trim()
                    val limit = args.optInt("limit", 10)
                    if (query.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Error: 'query' no puede estar vacía", isError = true)
                    }

                    val results = if (sqliteStore?.isAvailable == true) {
                        sqliteStore.searchFts5(query, limit)
                    } else {
                        // Fallback básico en memoria si sqlite no estuviera disponible
                        val arr = JSONArray()
                        synchronized(lock) {
                            for ((k, v) in memoryMap) {
                                if (k.contains(query, ignoreCase = true) || v.contains(query, ignoreCase = true)) {
                                    arr.put(JSONObject().apply {
                                        put("key", k)
                                        put("value", v)
                                        put("snippet", v.take(80))
                                        put("score_bm25", 1.0)
                                    })
                                }
                            }
                        }
                        arr
                    }

                    val resp = JSONObject().apply {
                        put("query", query)
                        put("total_matches", results.length())
                        put("results", results)
                    }
                    McpToolResult(call.id, call.toolName, resp.toString(2))
                }

                "list_memories" -> {
                    val category = if (args.has("category")) args.optString("category", "") else null
                    val limit = args.optInt("limit", 100)

                    val records = if (sqliteStore?.isAvailable == true) {
                        sqliteStore.list(category, limit)
                    } else {
                        val arr = JSONArray()
                        synchronized(lock) {
                            for ((k, v) in memoryMap) {
                                arr.put(JSONObject().apply {
                                    put("key", k)
                                    put("value", v)
                                    put("category", "general")
                                })
                            }
                        }
                        arr
                    }

                    val root = JSONObject().apply {
                        put("total_records", records.length())
                        put("records", records)
                    }
                    McpToolResult(call.id, call.toolName, root.toString(2))
                }

                "delete_memory" -> {
                    val key = args.optString("key", "").trim()
                    val removedFromMemory = synchronized(lock) { memoryMap.remove(key) != null }
                    if (removedFromMemory) {
                        persistMemory()
                    }
                    val removedFromSqlite = if (sqliteStore?.isAvailable == true) sqliteStore.delete(key) else false

                    if (removedFromMemory || removedFromSqlite) {
                        McpToolResult(call.id, call.toolName, "✅ Memoria con clave '$key' eliminada correctamente de SQLite y disco.")
                    } else {
                        McpToolResult(call.id, call.toolName, "La clave '$key' no existía en memoria.")
                    }
                }

                "wal_status" -> {
                    val status = if (sqliteStore?.isAvailable == true) sqliteStore.walStatus() else JSONObject().apply {
                        put("status", "in_memory_only")
                        put("total_records", synchronized(lock) { memoryMap.size })
                    }
                    McpToolResult(call.id, call.toolName, status.toString(2))
                }

                "wal_truncate" -> {
                    val ok = if (sqliteStore?.isAvailable == true) sqliteStore.truncateWal() else true
                    if (ok) {
                        McpToolResult(call.id, call.toolName, "✅ PRAGMA wal_checkpoint(TRUNCATE) ejecutado exitosamente.")
                    } else {
                        McpToolResult(call.id, call.toolName, "Error al truncar el archivo WAL de SQLite", isError = true)
                    }
                }

                "set_persona" -> {
                    val rawName = args.optString("name", args.optString("persona", "")).trim()
                    var stylePrompt = args.optString("style_prompt", "").trim()
                    if (rawName.isEmpty()) {
                        return McpToolResult(call.id, call.toolName, "Error: 'name' o 'persona' es requerido", isError = true)
                    }
                    val name = rawName.lowercase()
                    if (stylePrompt.isEmpty()) {
                        stylePrompt = when (name) {
                            "astra", "gpt6", "gpt-6" -> "Codex GPT-6 Astra Matrix: Operación autónoma sin vacilación, CommonMark, chips reactivos, sin palabras de relleno (delve, leverage, foster), enfoque directo en completar la meta."
                            "concise" -> "Responde de forma ultra concisa y directa, sin introducciones ni despedidas innecesarias."
                            "technical", "tech" -> "Enfócate en la arquitectura técnica, código idiomático, patrones de diseño y explicaciones de ingeniería rigurosas."
                            "creative" -> "Aporta soluciones creativas e innovadoras, lenguaje expresivo y pensamiento lateral."
                            "teacher" -> "Explica con paciencia pedagógica, usando analogías claras y pasos didácticos."
                            "default" -> ""
                            else -> "Personalidad $rawName activada."
                        }
                    }
                    if (name == "default" || stylePrompt.isEmpty()) {
                        sqliteStore?.resetActivePersona()
                        McpToolResult(call.id, call.toolName, "✅ Personalidad restablecida al comportamiento estándar del sistema.")
                    } else {
                        val ok = sqliteStore?.setActivePersona(rawName, stylePrompt) ?: false
                        if (ok) {
                            McpToolResult(call.id, call.toolName, "✅ Persona activa establecida a '$rawName': \"$stylePrompt\"")
                        } else {
                            McpToolResult(call.id, call.toolName, "Error al guardar la persona en SQLite", isError = true)
                        }
                    }
                }

                "get_persona" -> {
                    val persona = sqliteStore?.getActivePersona()
                    if (persona != null) {
                        val resp = JSONObject().apply {
                            put("active", true)
                            put("name", persona.name)
                            put("style_prompt", persona.stylePrompt)
                            put("updated_at", persona.updatedAt)
                        }
                        McpToolResult(call.id, call.toolName, resp.toString(2))
                    } else {
                        McpToolResult(call.id, call.toolName, "Ninguna persona personalizada activa (usando perfil por defecto).")
                    }
                }

                "list_personas" -> {
                    val personas = sqliteStore?.listPersonas() ?: emptyList()
                    val arr = JSONArray()
                    for (p in personas) {
                        arr.put(JSONObject().apply {
                            put("name", p.name)
                            put("style_prompt", p.stylePrompt)
                            put("is_active", p.isActive)
                            put("updated_at", p.updatedAt)
                        })
                    }
                    val resp = JSONObject().apply {
                        put("total_personas", arr.length())
                        put("personas", arr)
                    }
                    McpToolResult(call.id, call.toolName, resp.toString(2))
                }

                "reset_persona" -> {
                    val ok = sqliteStore?.resetActivePersona() ?: true
                    if (ok) {
                        McpToolResult(call.id, call.toolName, "✅ Persona restablecida a la configuración por defecto.")
                    } else {
                        McpToolResult(call.id, call.toolName, "Error al restablecer la persona en SQLite", isError = true)
                    }
                }

                else -> McpToolResult(call.id, call.toolName, "Herramienta desconocida: ${call.toolName}", isError = true)
            }
        } catch (e: Exception) {
            McpToolResult(call.id, call.toolName, "Error ejecutando ${call.toolName}: ${e.message}", isError = true)
        }
    }
}