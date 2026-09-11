package com.codex.chat.network

import com.codex.chat.core.network.MobileWebSearchClient
import com.codex.chat.core.network.WebSearchResult
import org.junit.Assert.*
import org.junit.Test

class MobileWebSearchClientTest {

    @Test
    fun testFormatGroundingContextWithResults() {
        val client = MobileWebSearchClient()
        val results = listOf(
            WebSearchResult(
                title = "Últimas Noticias de IA",
                snippet = "Los modelos de lenguaje ahora ejecutan código de forma autónoma.",
                url = "https://example.com/noticia-ia"
            ),
            WebSearchResult(
                title = "Python en la nube con MicroVMs",
                snippet = "E2B proporciona entornos sandbox efímeros para agentes de IA.",
                url = "https://e2b.dev"
            )
        )

        val context = client.formatGroundingContext("inteligencia artificial 2025", results)

        assertTrue("Debe contener encabezado de resultados web", context.contains("RESULTADOS DE BÚSQUEDA WEB"))
        assertTrue("Debe contener la query", context.contains("inteligencia artificial 2025"))
        assertTrue("Debe incluir el primer resultado", context.contains("Últimas Noticias de IA"))
        assertTrue("Debe incluir el segundo resultado", context.contains("Python en la nube con MicroVMs"))
        assertTrue("Debe incluir URLs", context.contains("https://e2b.dev"))
        assertTrue("Debe contener delimitador final", context.contains("FIN DATOS DE INTERNET"))
    }

    @Test
    fun testFormatGroundingContextWithEmptyResults() {
        val client = MobileWebSearchClient()
        val context = client.formatGroundingContext("query vacia", emptyList())
        assertEquals("Debe retornar string vacio si no hay resultados", "", context)
    }

    @Test
    fun testEmptyQueryReturnsEmptyList() {
        val client = MobileWebSearchClient()
        val results = client.search("   ")
        assertTrue("Query vacía o con espacios no debe realizar llamadas", results.isEmpty())
    }
}
