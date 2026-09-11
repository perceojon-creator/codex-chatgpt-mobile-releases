package com.codex.chat.core.network

import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

data class WebSearchResult(
    val title: String,
    val snippet: String,
    val url: String
)

class MobileWebSearchClient(
    private val client: OkHttpClient = defaultHttpClient()
) {

    companion object {
        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .followRedirects(true)
                .build()
        }

        fun isTemporalDateQuery(query: String): Boolean {
            val q = query.lowercase().trim()
            val triggers = listOf(
                "qué día es hoy", "que dia es hoy", "qué día es", "que dia es",
                "hoy qué día es", "hoy que dia es", "qué fecha es hoy", "que fecha es hoy",
                "fecha de hoy", "día de hoy", "dia de hoy", "qué día estamos", "que dia estamos",
                "a qué estamos hoy", "a que estamos hoy", "qué hora es", "que hora es",
                "hora actual", "qué fecha tenemos", "que fecha tenemos"
            )
            return triggers.any { q.contains(it) }
        }
    }

    fun search(query: String, maxResults: Int = 4): List<WebSearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val results = mutableListOf<WebSearchResult>()

        // 1. Primary engine: DuckDuckGo direct from mobile
        try {
            val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
            val url = "https://html.duckduckgo.com/html/?q=$encoded"

            val request = Request.Builder()
                .url(url)
                .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36")
                .addHeader("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                .addHeader("Accept-Language", "es-ES,es;q=0.9,en;q=0.8")
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val html = response.body?.string() ?: ""
                val snippetPattern = Pattern.compile("<a class=\"result__snippet[^>]*>([\\s\\S]*?)</a>")
                val titlePattern = Pattern.compile("<h2 class=\"result__title\">[\\s\\S]*?<a[^>]*>([\\s\\S]*?)</a>")

                val snippetMatcher = snippetPattern.matcher(html)
                val titleMatcher = titlePattern.matcher(html)

                val snippets = mutableListOf<String>()
                val titles = mutableListOf<String>()

                while (snippetMatcher.find() && snippets.size < maxResults) {
                    val raw = snippetMatcher.group(1) ?: ""
                    val clean = raw.replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotEmpty()) {
                        snippets.add(clean)
                    }
                }

                while (titleMatcher.find() && titles.size < maxResults) {
                    val raw = titleMatcher.group(1) ?: ""
                    val clean = raw.replace(Regex("<[^>]+>"), "").trim()
                    if (clean.isNotEmpty()) {
                        titles.add(clean)
                    }
                }

                for (i in 0 until minOf(snippets.size, maxResults)) {
                    val title = if (i < titles.size) titles[i] else "Resultado de búsqueda"
                    val snippet = snippets[i]
                    results.add(WebSearchResult(title = title, snippet = snippet, url = ""))
                }
            }
        } catch (e: Exception) {
            // DuckDuckGo failed, proceed to fallback
        }

        // 2. Direct fallback: Wikipedia API directly from mobile
        if (results.isEmpty()) {
            try {
                val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
                val wikiUrl = "https://es.wikipedia.org/w/api.php?action=query&list=search&format=json&srsearch=$encoded"

                val request = Request.Builder()
                    .url(wikiUrl)
                    .addHeader("User-Agent", "CodexChatGPT/1.1 (Android Mobile)")
                    .get()
                    .build()

                val response = client.newCall(request).execute()
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val items = json.optJSONObject("query")?.optJSONArray("search")
                    if (items != null) {
                        for (i in 0 until minOf(items.length(), maxResults)) {
                            val obj = items.getJSONObject(i)
                            val title = obj.optString("title", "")
                            val rawSnippet = obj.optString("snippet", "")
                            val cleanSnippet = rawSnippet.replace(Regex("<[^>]+>"), "").trim()
                            val pageUrl = "https://es.wikipedia.org/wiki/" + URLEncoder.encode(title, "UTF-8")
                            results.add(WebSearchResult(title = title, snippet = cleanSnippet, url = pageUrl))
                        }
                    }
                }
            } catch (e: Exception) {
                // Wikipedia fallback error
            }
        }

        return results
    }

    fun formatGroundingContext(query: String, results: List<WebSearchResult>): String {
        if (results.isEmpty()) return ""

        val sb = StringBuilder()
        sb.append("=== RESULTADOS DE BÚSQUEDA WEB: '").append(query).append("' ===\n")
        for (i in results.indices) {
            val r = results[i]
            sb.append("[").append(i + 1).append("] Title: ").append(r.title).append("\n")
            if (r.url.isNotEmpty()) {
                sb.append("    URL: ").append(r.url).append("\n")
            }
            sb.append("    Snippet: ").append(r.snippet).append("\n")
        }
        sb.append("=== FIN DATOS DE INTERNET ===\n")
        return sb.toString()
    }
}
