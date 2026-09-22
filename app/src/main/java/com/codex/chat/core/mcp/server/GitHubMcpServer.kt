package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.connector.GitHubConnectorClient
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject

class GitHubMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-github-native",
        name = "GitHub",
        description = "Integración nativa oficial con GitHub API (repositorios, código fuente y ramas)",
        iconEmoji = "🐙",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 4
    )

    private val client = GitHubConnectorClient()

    private fun getToken(): String {
        val prefs = context?.getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        return prefs?.getString("github_pat_token", "")?.trim() ?: ""
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "github_list_repos",
            description = "Lista los repositorios del usuario de GitHub autenticado (o públicos si no hay token). Devuelve nombre, descripción, estrellas y enlace.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().put("type", "integer").put("description", "Número máximo de repositorios a devolver (default 10)"))
                })
            }
        ),
        McpTool(
            name = "github_get_file",
            description = "Obtiene el contenido completo en texto plano de un archivo en un repositorio de GitHub (ej. README.md, build.gradle, main.py).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño o usuario del repositorio (ej. 'perceojon-creator')"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio (ej. 'codex-chatgpt-mobile')"))
                    put("path", JSONObject().put("type", "string").put("description", "Ruta del archivo dentro del repositorio (ej. 'README.md' o 'src/main.rs')"))
                    put("branch", JSONObject().put("type", "string").put("description", "Rama git (opcional, default 'main' o 'master')"))
                })
                put("required", JSONArray(listOf("owner", "repo", "path")))
            }
        ),
        McpTool(
            name = "github_search_repos",
            description = "Busca repositorios públicos o privados en GitHub según palabras clave o temas.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Término de búsqueda (ej. 'android mcp server', 'trading bot')"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Máximo de resultados (default 5)"))
                })
                put("required", JSONArray(listOf("query")))
            }
        ),
        McpTool(
            name = "github_user_profile",
            description = "Verifica la identidad y estado de autenticación del usuario actual en GitHub.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        )
    )

    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        val token = getToken()
        val args = try { JSONObject(call.argumentsJson) } catch (_: Exception) { JSONObject() }

        return when (call.toolName) {
            "github_list_repos" -> {
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                client.listRepositories(token, limit).fold(
                    onSuccess = { repos ->
                        val arr = JSONArray()
                        for (r in repos) {
                            arr.put(JSONObject().apply {
                                put("name", r.name)
                                put("full_name", r.fullName)
                                put("description", r.description)
                                put("private", r.isPrivate)
                                put("stars", r.stars)
                                put("url", r.htmlUrl)
                            })
                        }
                        McpToolResult(call.id, call.toolName, arr.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al listar repositorios de GitHub: " + e.message, isError = true)
                    }
                )
            }
            "github_get_file" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                val path = args.optString("path")
                val branch = args.optString("branch").ifBlank { "main" }

                if (owner.isBlank() || repo.isBlank() || path.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros obligatorios: owner, repo, path", isError = true)
                }

                var fileRes = client.getFileRawContent(owner, repo, path, branch, token)
                if (fileRes.isFailure && branch == "main") {
                    fileRes = client.getFileRawContent(owner, repo, path, "master", token)
                }

                fileRes.fold(
                    onSuccess = { content ->
                        McpToolResult(call.id, call.toolName, content, isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al obtener archivo " + path + ": " + e.message, isError = true)
                    }
                )
            }
            "github_search_repos" -> {
                val q = args.optString("query")
                val limit = args.optInt("limit", 5).coerceIn(1, 20)
                if (q.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "El parámetro query no puede estar vacío", isError = true)
                }
                client.searchRepositories(q, token, limit).fold(
                    onSuccess = { repos ->
                        val arr = JSONArray()
                        for (r in repos) {
                            arr.put(JSONObject().apply {
                                put("name", r.fullName)
                                put("stars", r.stars)
                                put("description", r.description)
                                put("url", r.htmlUrl)
                            })
                        }
                        McpToolResult(call.id, call.toolName, arr.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error en búsqueda de GitHub: " + e.message, isError = true)
                    }
                )
            }
            "github_user_profile" -> {
                if (token.isBlank()) {
                    val anonJson = JSONObject().apply {
                        put("authenticated", false)
                        put("user", "anonymous")
                        put("status", "Acceso Anónimo / Solo Lectura Pública (Configura tu token en Conectores para repositorios privados)")
                    }
                    McpToolResult(call.id, call.toolName, anonJson.toString(2), isError = false)
                } else {
                    client.getUserProfile(token).fold(
                        onSuccess = { login ->
                            val resJson = JSONObject().apply {
                                put("authenticated", true)
                                put("user", login)
                                put("status", "Conectado vía Token Personal (PAT)")
                            }
                            McpToolResult(call.id, call.toolName, resJson.toString(2), isError = false)
                        },
                        onFailure = { e ->
                            McpToolResult(call.id, call.toolName, "Error al consultar usuario de GitHub: " + e.message, isError = true)
                        }
                    )
                }
            }
            else -> McpToolResult(call.id, call.toolName, "Herramienta GitHub desconocida: " + call.toolName, isError = true)
        }
    }
}
