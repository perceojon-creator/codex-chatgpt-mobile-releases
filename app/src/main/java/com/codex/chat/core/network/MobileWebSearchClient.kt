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
        sb.append("=== RESULTADOS DE BÚSQUEDA WEB EN TIEMPO REAL DIRECTO DESDE EL MÓVIL PARA: '").append(query).append("' ===\n")
        for (i in results.indices) {
            val r = results[i]
            sb.append("[").append(i + 1).append("] ").append(r.title).append("\n")
            if (r.url.isNotEmpty()) {
                sb.append("    URL: ").append(r.url).append("\n")
            }
            sb.append("    Resumen: ").append(r.snippet).append("\n")
        }
        sb.append("=== FIN DATOS DE INTERNET (Usa esta información real y actualizada para responder la pregunta citando fuentes) ===\n")
        return sb.toString()
    }
}
