package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Test

class FencedHtmlDocumentTest {

    @Test
    fun testFencedHtmlDocDoesNotLeaveEmptyCodeFences() {
        val raw = "Aquí está:\n```html\n<!DOCTYPE html><html><body><h1>Hola</h1></body></html>\n```\nListo."
        val parsed = VisualMediaParser.parse(raw)
        assertTrue("Debe tener media", parsed.hasMedia)
        assertEquals(VisualMediaType.HTML_CHART, parsed.type)
        assertFalse("cleanContent NO debe tener backticks vacíos", parsed.cleanContent.contains("```"))
        assertTrue("cleanContent debe tener texto previo", parsed.cleanContent.contains("Aquí está"))
        assertTrue("cleanContent debe tener texto posterior", parsed.cleanContent.contains("Listo"))
    }

    @Test
    fun testFencedCanvasGameDoesNotLeaveEmptyCodeFences() {
        val raw = "Disfruta el juego:\n```html\n<!DOCTYPE html>\n<html>\n<head><title>Space</title></head>\n<body><canvas id=\"g\"></canvas></body>\n</html>\n```\n¡Buena suerte!"
        val parsed = VisualMediaParser.parse(raw)
        assertTrue("Debe tener media", parsed.hasMedia)
        assertEquals(VisualMediaType.HTML_CHART, parsed.type)
        assertTrue("mediaSource debe tener canvas", parsed.mediaSource.contains("<canvas id=\"g\">"))
        assertFalse("cleanContent NO debe tener backticks vacíos", parsed.cleanContent.contains("```"))
        assertTrue("cleanContent debe tener texto previo", parsed.cleanContent.contains("Disfruta el juego"))
        assertTrue("cleanContent debe tener texto posterior", parsed.cleanContent.contains("Buena suerte"))
    }
}