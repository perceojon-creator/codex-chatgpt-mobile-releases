package com.codex.chat.core.mcp.server

import android.content.Context
import com.codex.chat.core.connector.GitHubConnectorClient
import com.codex.chat.core.mcp.model.*
import org.json.JSONArray
import org.json.JSONObject

/**
 * Servidor MCP nativo full-spectrum de GitHub para Codex / ChatGPT Mobile.
 * Habilita al modelo de lenguaje para operar como un agente DevOps autónomo:
 * - Listar, buscar y crear repositorios.
 * - Leer archivos y hacer commits directos con código modificado.
 * - Crear ramas (branching) para flujos de trabajo aislados.
 * - Crear y listar issues y pull requests.
 * - Consultar perfil, límites de API y permisos del token.
 */
class GitHubMcpServer(private val context: Context? = null) : McpServer {

    override val info = McpServerInfo(
        id = "mcp-github-native",
        name = "GitHub",
        description = "Herramientas oficiales de GitHub API: repositorios, commits de archivos, ramas, issues y pull requests",
        iconEmoji = "🐙",
        type = McpServerType.NATIVE,
        isEnabled = true,
        toolsCount = 11
    )

    private val client = GitHubConnectorClient()

    private fun getToken(): String {
        val prefs = context?.getSharedPreferences("connector_prefs", Context.MODE_PRIVATE)
        return prefs?.getString("github_pat_token", "")?.trim() ?: ""
    }

    override fun getTools(): List<McpTool> = listOf(
        McpTool(
            name = "github_user_profile",
            description = "Verifica la identidad y estado de autenticación del usuario actual en GitHub.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            }
        ),
        McpTool(
            name = "github_list_repos",
            description = "Lista los repositorios del usuario autenticado (privados y públicos) ordenados por actividad reciente.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().put("type", "integer").put("description", "Número máximo de repositorios a devolver (default 10)"))
                })
            }
        ),
        McpTool(
            name = "github_create_repo",
            description = "Crea un nuevo repositorio en la cuenta de GitHub del usuario.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().put("type", "string").put("description", "Nombre del repositorio a crear"))
                    put("description", JSONObject().put("type", "string").put("description", "Descripción del proyecto"))
                    put("private", JSONObject().put("type", "boolean").put("description", "Si el repositorio debe ser privado (default false)"))
                    put("auto_init", JSONObject().put("type", "boolean").put("description", "Crear con README inicial (default true)"))
                })
                put("required", JSONArray(listOf("name")))
            }
        ),
        McpTool(
            name = "github_get_file",
            description = "Obtiene el contenido completo en texto plano de un archivo en un repositorio de GitHub.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño o usuario del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                    put("path", JSONObject().put("type", "string").put("description", "Ruta del archivo dentro del repositorio"))
                    put("branch", JSONObject().put("type", "string").put("description", "Rama git (opcional, default 'main' o 'master')"))
                })
                put("required", JSONArray(listOf("owner", "repo", "path")))
            }
        ),
        McpTool(
            name = "github_commit_file",
            description = "Crea o actualiza un archivo en un repositorio de GitHub realizando un commit directo con el mensaje especificado.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño o usuario del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                    put("path", JSONObject().put("type", "string").put("description", "Ruta del archivo a crear o modificar"))
                    put("content", JSONObject().put("type", "string").put("description", "Contenido completo en texto del archivo"))
                    put("message", JSONObject().put("type", "string").put("description", "Mensaje del commit (ej. 'feat: add login screen')"))
                    put("branch", JSONObject().put("type", "string").put("description", "Rama destino (default 'main')"))
                    put("sha", JSONObject().put("type", "string").put("description", "SHA del archivo previo si se está sobrescribiendo (opcional)"))
                })
                put("required", JSONArray(listOf("owner", "repo", "path", "content", "message")))
            }
        ),
        McpTool(
            name = "github_list_branches",
            description = "Lista todas las ramas (branches) disponibles en un repositorio.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño o usuario del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                })
                put("required", JSONArray(listOf("owner", "repo")))
            }
        ),
        McpTool(
            name = "github_create_branch",
            description = "Crea una nueva rama git a partir de una rama existente (default 'main').",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño o usuario del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                    put("new_branch", JSONObject().put("type", "string").put("description", "Nombre de la nueva rama (ej. 'feature-auth')"))
                    put("base_branch", JSONObject().put("type", "string").put("description", "Rama base de donde parte (default 'main')"))
                })
                put("required", JSONArray(listOf("owner", "repo", "new_branch")))
            }
        ),
        McpTool(
            name = "github_list_issues",
            description = "Lista los issues y Pull Requests de un repositorio según su estado (open, closed, all).",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                    put("state", JSONObject().put("type", "string").put("description", "Estado: 'open', 'closed' o 'all' (default 'open')"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Máximo a devolver (default 10)"))
                })
                put("required", JSONArray(listOf("owner", "repo")))
            }
        ),
        McpTool(
            name = "github_create_issue",
            description = "Abre un nuevo Issue en un repositorio de GitHub con título, descripción y etiquetas opcionales.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                    put("title", JSONObject().put("type", "string").put("description", "Título del issue o reporte"))
                    put("body", JSONObject().put("type", "string").put("description", "Descripción detallada del issue en markdown"))
                })
                put("required", JSONArray(listOf("owner", "repo", "title")))
            }
        ),
        McpTool(
            name = "github_create_pr",
            description = "Crea un Pull Request en un repositorio de GitHub para solicitar fusionar una rama en otra.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("owner", JSONObject().put("type", "string").put("description", "Dueño del repositorio"))
                    put("repo", JSONObject().put("type", "string").put("description", "Nombre del repositorio"))
                    put("title", JSONObject().put("type", "string").put("description", "Título del Pull Request"))
                    put("head", JSONObject().put("type", "string").put("description", "Rama con los cambios a fusionar"))
                    put("base", JSONObject().put("type", "string").put("description", "Rama destino donde se fusiona (default 'main')"))
                    put("body", JSONObject().put("type", "string").put("description", "Descripción del Pull Request en markdown"))
                })
                put("required", JSONArray(listOf("owner", "repo", "title", "head")))
            }
        ),
McpTool(
            name = "github_search_repos",
            description = "Busca repositorios públicos o privados en GitHub según palabras clave o temas.",
            serverName = info.name,
            inputSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Término de búsqueda"))
                    put("limit", JSONObject().put("type", "integer").put("description", "Máximo de resultados (default 5)"))
                })
                put("required", JSONArray(listOf("query")))
            }
        ),
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
                        McpToolResult(call.id, call.toolName, "Error al listar repositorios: ${e.message}", isError = true)
                    }
                )
            }
            "github_create_repo" -> {
                val name = args.optString("name")
                val desc = args.optString("description", "")
                val isPrivate = args.optBoolean("private", false)
                val autoInit = args.optBoolean("auto_init", true)

                if (name.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "El nombre del repositorio es obligatorio", isError = true)
                }

                client.createRepository(token, name, desc, isPrivate, autoInit).fold(
                    onSuccess = { json ->
                        McpToolResult(call.id, call.toolName, "✓ Repositorio creado exitosamente:\n" + json.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al crear repositorio '$name': ${e.message}", isError = true)
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
                        McpToolResult(call.id, call.toolName, "Error al obtener archivo $path: ${e.message}", isError = true)
                    }
                )
            }
            "github_commit_file" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                val path = args.optString("path")
                val content = args.optString("content")
                val message = args.optString("message").ifBlank { "Update $path" }
                val branch = args.optString("branch").ifBlank { "main" }
                val sha = args.optString("sha").takeIf { it.isNotBlank() }

                if (owner.isBlank() || repo.isBlank() || path.isBlank() || content.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros obligatorios: owner, repo, path, content", isError = true)
                }

                client.createOrUpdateFile(token, owner, repo, path, content, message, branch, sha).fold(
                    onSuccess = { resJson ->
                        val commitInfo = resJson.optJSONObject("commit")
                        val commitSha = commitInfo?.optString("sha")?.take(8) ?: "ok"
                        McpToolResult(call.id, call.toolName, "✓ Commit $commitSha realizado exitosamente en $branch:\n" + resJson.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al hacer commit en $path: ${e.message}", isError = true)
                    }
                )
            }
            "github_list_branches" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                if (owner.isBlank() || repo.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros obligatorios: owner, repo", isError = true)
                }
                client.listBranches(owner, repo, token).fold(
                    onSuccess = { branches ->
                        val arr = JSONArray(branches)
                        McpToolResult(call.id, call.toolName, arr.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al listar ramas: ${e.message}", isError = true)
                    }
                )
            }
            "github_create_branch" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                val newBranch = args.optString("new_branch")
                val baseBranch = args.optString("base_branch").ifBlank { "main" }

                if (owner.isBlank() || repo.isBlank() || newBranch.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros: owner, repo, new_branch", isError = true)
                }

                client.createBranch(token, owner, repo, newBranch, baseBranch).fold(
                    onSuccess = { resJson ->
                        McpToolResult(call.id, call.toolName, "✓ Rama '$newBranch' creada exitosamente a partir de '$baseBranch':\n" + resJson.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al crear rama '$newBranch': ${e.message}", isError = true)
                    }
                )
            }
            "github_list_issues" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                val state = args.optString("state", "open")
                val limit = args.optInt("limit", 10).coerceIn(1, 30)

                if (owner.isBlank() || repo.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros: owner, repo", isError = true)
                }

                client.listIssues(owner, repo, state, token, limit).fold(
                    onSuccess = { issues ->
                        val arr = JSONArray()
                        for (it in issues) {
                            arr.put(JSONObject().apply {
                                put("number", it.number)
                                put("title", it.title)
                                put("state", it.state)
                                put("author", it.author)
                                put("is_pull_request", it.isPullRequest)
                                put("url", it.htmlUrl)
                            })
                        }
                        McpToolResult(call.id, call.toolName, arr.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al listar issues: ${e.message}", isError = true)
                    }
                )
            }
            "github_create_issue" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                val title = args.optString("title")
                val body = args.optString("body", "")

                if (owner.isBlank() || repo.isBlank() || title.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros: owner, repo, title", isError = true)
                }

                client.createIssue(token, owner, repo, title, body).fold(
                    onSuccess = { resJson ->
                        McpToolResult(call.id, call.toolName, "✓ Issue creado exitosamente (#${resJson.optInt("number")}):\n" + resJson.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al crear issue: ${e.message}", isError = true)
                    }
                )
            }
            "github_create_pr" -> {
                val owner = args.optString("owner")
                val repo = args.optString("repo")
                val title = args.optString("title")
                val head = args.optString("head")
                val base = args.optString("base").ifBlank { "main" }
                val body = args.optString("body", "")

                if (owner.isBlank() || repo.isBlank() || title.isBlank() || head.isBlank()) {
                    return McpToolResult(call.id, call.toolName, "Faltan parámetros: owner, repo, title, head", isError = true)
                }

                client.createPullRequest(token, owner, repo, title, head, base, body).fold(
                    onSuccess = { resJson ->
                        McpToolResult(call.id, call.toolName, "✓ Pull Request creado exitosamente (#${resJson.optInt("number")}):\n" + resJson.toString(2), isError = false)
                    },
                    onFailure = { e ->
                        McpToolResult(call.id, call.toolName, "Error al crear Pull Request: ${e.message}", isError = true)
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
                        onSuccess = { userObj ->
                            val login = userObj.optString("login", "user")
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
                        McpToolResult(call.id, call.toolName, "Error en búsqueda de GitHub: ${e.message}", isError = true)
                    }
                )
            }
            else -> McpToolResult(call.id, call.toolName, "Herramienta GitHub desconocida: ${call.toolName}", isError = true)
        }
    }
}
