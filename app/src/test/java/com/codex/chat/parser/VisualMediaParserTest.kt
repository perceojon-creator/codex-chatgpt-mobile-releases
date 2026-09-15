package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Test

class VisualMediaParserTest {

    @Test
    fun parse_raw_svg_graphic() {
        val raw = "Aquí está tu icono:\n<svg width='100' height='100'><circle cx='50' cy='50' r='40'/></svg>\nEspero que te guste."
        val parsed = VisualMediaParser.parse(raw)
        assertTrue(parsed.hasMedia)
        assertEquals(VisualMediaType.SVG, parsed.type)
        assertTrue(parsed.mediaSource.startsWith("<svg"))
        assertTrue(parsed.mediaSource.endsWith("</svg>"))
        assertTrue(parsed.cleanContent.contains("Aquí está tu icono"))
    }

    @Test
    fun parse_escaped_unicode_svg_from_json_or_proxy() {
        val raw = """xq Batería --\u003e\n      \u003crect x=\"100\" y=\"130\" width=\"45\" height=\"35\" rx=\"4\" fill=\"#10b981\" stroke=\"#059669\" stroke-width=\"2\"/\u003e\n      \u003ctext x=\"122\" y=\"152\" text-anchor=\"middle\" fill=\"#ffffff\" font-size=\"10\" font-weight=\"bold\"\u003e12V\u003c/text\u003e\n    \u003c/svg\u003e\n  \u003c/div\u003e\n\u003c/body\u003e\n\u003c/html\u003e\n"}""".trimIndent()

        val parsed = VisualMediaParser.parse(raw)
        assertTrue("Debe detectar el medio visual a pesar de escapes Unicode \\u003c", parsed.hasMedia)
        assertEquals(VisualMediaType.SVG, parsed.type)
        assertTrue("El SVG generado debe tener apertura <svg", parsed.mediaSource.contains("<svg"))
        assertTrue("El SVG generado debe tener rectangulos desescapados", parsed.mediaSource.contains("<rect x=\"100\""))
        assertTrue("El SVG debe cerrar con </svg>", parsed.mediaSource.contains("</svg>"))
    }

    @Test
    fun parse_full_html_document_with_canvas_or_svg() {
        val raw = "<!DOCTYPE html><html><head><style>canvas{width:100%}</style></head><body><canvas id='c'></canvas></body></html>"
        val parsed = VisualMediaParser.parse(raw)
        assertTrue(parsed.hasMedia)
        assertEquals(VisualMediaType.HTML_CHART, parsed.type)
        assertTrue(parsed.mediaSource.contains("<canvas id='c'>"))
    }

    @Test
    fun parse_fenced_mermaid_diagram() {
        val raw = "Diagrama de flujo:\n" +
                  "```mermaid\ngraph TD\nA[Inicio] --> B[Fin]\n```\nListo."
        val parsed = VisualMediaParser.parse(raw)
        assertTrue(parsed.hasMedia)
        assertEquals(VisualMediaType.MERMAID, parsed.type)
        assertTrue(parsed.mediaSource.contains("graph TD"))
        assertTrue(parsed.cleanContent.contains("Diagrama de flujo"))
    }

    @Test
    fun parse_markdown_image() {
        val raw = "Mira esta arquitectura:\n![Arquitectura Red](https://example.com/network.png)\nComo puedes ver..."
        val parsed = VisualMediaParser.parse(raw)
        assertTrue(parsed.hasMedia)
        assertEquals(VisualMediaType.IMAGE, parsed.type)
        assertEquals("https://example.com/network.png", parsed.mediaSource)
        assertTrue(parsed.title.contains("Arquitectura Red"))
    }

    @Test
    fun parse_base64_image() {
        val raw = "![Render](data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==)"
        val parsed = VisualMediaParser.parse(raw)
        assertTrue(parsed.hasMedia)
        assertEquals(VisualMediaType.IMAGE, parsed.type)
        assertTrue(parsed.mediaSource.startsWith("data:image/png;base64"))
    }

    @Test
    fun parse_video_url() {
        val raw = "Aquí está la demo:\nhttps://assets.example.com/demo_video.mp4\nDisfrútalo."
        val parsed = VisualMediaParser.parse(raw)
        assertTrue(parsed.hasMedia)
        assertEquals(VisualMediaType.VIDEO, parsed.type)
        assertEquals("https://assets.example.com/demo_video.mp4", parsed.mediaSource)
    }

    @Test
    fun parse_plain_text_without_media() {
        val raw = "Hola, este es un texto completamente normal sin imágenes ni gráficos."
        val parsed = VisualMediaParser.parse(raw)
        assertFalse(parsed.hasMedia)
    }
}