package com.codex.chat.core.connector

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Cliente nativo de integración con GitHub REST API v3.
 * Proporciona acceso nativo para consultar repositorios, pull requests, issues y contenido de archivos.
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
        val isPullRequest: Boolean
    )

    /**
     * Obtiene el perfil del usuario autenticado o público.
     */
    fun getUserProfile(token: String): Result<String> = runCatching {
        val req = Request.Builder()
            .url("https://api.github.com/user")
            .header("Accept", "application/vnd.github.v3+json")
            .apply {
                if (token.isNotBlank()) header("Authorization", "Bearer $token")
            }
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}: ${resp.message}")
            val json = JSONObject(resp.body?.string() ?: "{}")
            json.optString("login", "Desconocido")
        }
    }

    /**
     * Lista los repositorios del usuario (ordenados por actualización reciente).
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
     * Busca repositorios o código en GitHub.
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
