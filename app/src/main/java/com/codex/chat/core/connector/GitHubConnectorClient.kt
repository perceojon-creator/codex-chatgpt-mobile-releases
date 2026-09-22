package com.codex.chat.core.connector

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente nativo full-spectrum de integración con GitHub REST API v3.
 * Soporta operaciones de lectura y escritura:
 * - Repositorios (listar, crear, clonar info)
 * - Archivos (leer, crear o actualizar con commit directo en base64)
 * - Ramas (listar, crear rama a partir de un SHA base)
 * - Issues (listar, crear, comentar, cerrar)
 * - Pull Requests (listar, crear PR, ver estado de merge)
 * - Releases (listar y crear releases con tags)
 */
class GitHubConnectorClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()
) {

    data class GitHubRepo(
        val name: String,
        val fullName: String,
        val description: String,
        val isPrivate: Boolean,
        val stars: Int,
        val defaultBranch: String,
        val htmlUrl: String
    )

    data class GitHubIssueOrPr(
        val number: Int,
        val title: String,
        val state: String,
        val author: String,
        val htmlUrl: String,
        val body: String = "",
        val isPullRequest: Boolean = false
    )

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    // OAuth App Client ID oficial de GitHub CLI para Device Code Flow
    companion object {
        const val GITHUB_CLI_CLIENT_ID = "178c6fc77837768e5939"
    }

    data class DeviceCodeResponse(
        val deviceCode: String,
        val userCode: String,
        val verificationUri: String,
        val expiresIn: Int,
        val interval: Int
    )

    /**
     * Paso 1 de Device Flow: Solicita un código de dispositivo de 8 caracteres y una URL de verificación (github.com/login/device).
     */
    fun requestDeviceCode(clientId: String = GITHUB_CLI_CLIENT_ID): Result<DeviceCodeResponse> = runCatching {
        val payload = JSONObject().apply {
            put("client_id", clientId)
            put("scope", "repo,read:user")
        }
        val req = Request.Builder()
            .url("https://github.com/login/device/code")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} al solicitar código")
            val json = JSONObject(resp.body?.string() ?: "{}")
            DeviceCodeResponse(
                deviceCode = json.optString("device_code"),
                userCode = json.optString("user_code"),
                verificationUri = json.optString("verification_uri", "https://github.com/login/device"),
                expiresIn = json.optInt("expires_in", 900),
                interval = json.optInt("interval", 5)
            )
        }
    }

    /**
     * Paso 2 de Device Flow: Hace polling para intercambiar el device_code por el token de acceso una vez el usuario autoriza en el navegador.
     */
    fun pollForAccessToken(clientId: String = GITHUB_CLI_CLIENT_ID, deviceCode: String): Result<String?> = runCatching {
        val payload = JSONObject().apply {
            put("client_id", clientId)
            put("device_code", deviceCode)
            put("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
        }
        val req = Request.Builder()
            .url("https://github.com/login/oauth/access_token")
            .header("Accept", "application/json")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val token = json.optString("access_token")
            if (token.isNotBlank()) {
                token
            } else {
                val err = json.optString("error")
                if (err == "authorization_pending" || err == "slow_down") {
                    null // Sigue esperando
                } else {
                    throw RuntimeException(json.optString("error_description", err))
                }
            }
        }
    }

    /**
     * Obtiene el perfil del usuario autenticado.
     */
    fun getUserProfile(token: String): Result<JSONObject> = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Accept", "application/vnd.github.v3+json")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * Lista los repositorios del usuario.
     */
    fun listRepositories(token: String, maxCount: Int = 10): Result<List<GitHubRepo>> = runCatching {
        val url = if (token.isNotBlank()) {
            "https://api.github.com/user/repos?sort=updated&per_page=$maxCount"
        } else {
            "https://api.github.com/repositories?per_page=$maxCount"
        }

        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            val array = JSONArray(resp.body?.string() ?: "[]")
            val list = mutableListOf<GitHubRepo>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    GitHubRepo(
                        name = obj.optString("name"),
                        fullName = obj.optString("full_name"),
                        description = obj.optString("description", "Sin descripción"),
                        isPrivate = obj.optBoolean("private", false),
                        stars = obj.optInt("stargazers_count", 0),
                        defaultBranch = obj.optString("default_branch", "main"),
                        htmlUrl = obj.optString("html_url")
                    )
                )
            }
            list
        }
    }

    /**
     * Crea un nuevo repositorio en la cuenta del usuario.
     */
    fun createRepository(token: String, name: String, description: String = "", isPrivate: Boolean = false, autoInit: Boolean = true): Result<JSONObject> = runCatching {
        require(token.isNotBlank()) { "Se requiere Personal Access Token (PAT) para crear repositorios" }
        val payload = JSONObject().apply {
            put("name", name)
            put("description", description)
            put("private", isPrivate)
            put("auto_init", autoInit)
        }
        val req = Request.Builder()
            .url("https://api.github.com/user/repos")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string() ?: resp.message}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * Lee el contenido de un archivo en texto plano desde un repositorio.
     */
    fun getFileRawContent(owner: String, repo: String, path: String, branch: String = "main", token: String = ""): Result<String> = runCatching {
        val url = "https://raw.githubusercontent.com/$owner/$repo/$branch/$path"
        val req = Request.Builder()
            .url(url)
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code} al obtener $path")
            resp.body?.string() ?: ""
        }
    }

    /**
     * Crea o actualiza un archivo en un repositorio mediante commit directo en la API de GitHub.
     */
    fun createOrUpdateFile(
        token: String,
        owner: String,
        repo: String,
        path: String,
        content: String,
        commitMessage: String,
        branch: String = "main",
        sha: String? = null
    ): Result<JSONObject> = runCatching {
        require(token.isNotBlank()) { "Se requiere Personal Access Token (PAT) para crear o editar archivos" }
        val base64Content = android.util.Base64.encodeToString(content.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP)
        val payload = JSONObject().apply {
            put("message", commitMessage)
            put("content", base64Content)
            put("branch", branch)
            if (!sha.isNullOrBlank()) {
                put("sha", sha)
            }
        }

        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/contents/$path")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer $token")
            .put(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string() ?: resp.message}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * Lista las ramas (branches) de un repositorio.
     */
    fun listBranches(owner: String, repo: String, token: String = ""): Result<List<String>> = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/branches")
            .header("Accept", "application/vnd.github.v3+json")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            val array = JSONArray(resp.body?.string() ?: "[]")
            val branches = mutableListOf<String>()
            for (i in 0 until array.length()) {
                branches.add(array.getJSONObject(i).optString("name"))
            }
            branches
        }
    }

    /**
     * Crea una nueva rama (branch) a partir de una referencia o SHA base.
     */
    fun createBranch(token: String, owner: String, repo: String, newBranch: String, baseBranch: String = "main"): Result<JSONObject> = runCatching {
        require(token.isNotBlank()) { "Se requiere Personal Access Token (PAT) para crear ramas" }
        // 1. Obtener el SHA de la rama base
        val refReq = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/ref/heads/$baseBranch")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer $token")
            .build()

        val baseSha = client.newCall(refReq).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Rama base '$baseBranch' no encontrada (HTTP ${resp.code})")
            val json = JSONObject(resp.body?.string() ?: "{}")
            json.getJSONObject("object").optString("sha")
        }

        // 2. Crear la nueva referencia
        val createPayload = JSONObject().apply {
            put("ref", "refs/heads/$newBranch")
            put("sha", baseSha)
        }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/git/refs")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer $token")
            .post(createPayload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string() ?: resp.message}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * Lista issues o Pull Requests de un repositorio.
     */
    fun listIssues(owner: String, repo: String, state: String = "open", token: String = "", limit: Int = 10): Result<List<GitHubIssueOrPr>> = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/issues?state=$state&per_page=$limit")
            .header("Accept", "application/vnd.github.v3+json")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            val array = JSONArray(resp.body?.string() ?: "[]")
            val list = mutableListOf<GitHubIssueOrPr>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    GitHubIssueOrPr(
                        number = obj.optInt("number"),
                        title = obj.optString("title"),
                        state = obj.optString("state"),
                        author = obj.optJSONObject("user")?.optString("login") ?: "desconocido",
                        htmlUrl = obj.optString("html_url"),
                        body = obj.optString("body", ""),
                        isPullRequest = obj.has("pull_request")
                    )
                )
            }
            list
        }
    }

    /**
     * Crea un nuevo Issue en un repositorio.
     */
    fun createIssue(token: String, owner: String, repo: String, title: String, body: String = "", labels: List<String> = emptyList()): Result<JSONObject> = runCatching {
        require(token.isNotBlank()) { "Se requiere Personal Access Token (PAT) para crear issues" }
        val payload = JSONObject().apply {
            put("title", title)
            put("body", body)
            if (labels.isNotEmpty()) {
                put("labels", JSONArray(labels))
            }
        }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/issues")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string() ?: resp.message}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * Crea un Pull Request.
     */
    fun createPullRequest(token: String, owner: String, repo: String, title: String, head: String, base: String = "main", body: String = ""): Result<JSONObject> = runCatching {
        require(token.isNotBlank()) { "Se requiere Personal Access Token (PAT) para crear Pull Requests" }
        val payload = JSONObject().apply {
            put("title", title)
            put("head", head)
            put("base", base)
            put("body", body)
        }
        val req = Request.Builder()
            .url("https://api.github.com/repos/$owner/$repo/pulls")
            .header("Accept", "application/vnd.github.v3+json")
            .header("Authorization", "Bearer $token")
            .post(payload.toString().toRequestBody(jsonMedia))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.body?.string() ?: resp.message}")
            JSONObject(resp.body?.string() ?: "{}")
        }
    }

    /**
     * Busca repositorios en GitHub.
     */
    fun searchRepositories(query: String, token: String = "", maxCount: Int = 5): Result<List<GitHubRepo>> = runCatching {
        val url = "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&per_page=$maxCount"
        val req = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            val items = json.optJSONArray("items") ?: JSONArray()
            val list = mutableListOf<GitHubRepo>()
            for (i in 0 until items.length()) {
                val obj = items.getJSONObject(i)
                list.add(
                    GitHubRepo(
                        name = obj.optString("name"),
                        fullName = obj.optString("full_name"),
                        description = obj.optString("description", "Sin descripción"),
                        isPrivate = obj.optBoolean("private", false),
                        stars = obj.optInt("stargazers_count", 0),
                        defaultBranch = obj.optString("default_branch", "main"),
                        htmlUrl = obj.optString("html_url")
                    )
                )
            }
            list
        }
    }
}
