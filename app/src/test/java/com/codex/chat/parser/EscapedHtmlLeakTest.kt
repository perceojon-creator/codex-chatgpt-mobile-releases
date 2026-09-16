package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.parser.ParsedToolCode
import com.codex.chat.core.parser.ToolCodeBlockParser
import org.junit.Assert.*
import org.junit.Test

class EscapedHtmlLeakTest {

    @Test
    fun testCompleteLifecycleWithoutLeak() {
        // Phase 1: Streaming in-flight tool call
        val phase1 = "⚙️ **[MCP Tool Call: `test_html_code`]**\n```json\n{\"html\": \"\\u003c!DOCTYPE html\\u003e\n\\u003chtml lang=\\\"es\\\"\\u003e"
        val parsed1 = ToolCodeBlockParser.parse(phase1)
        assertTrue("Phase 1: Must detect tool", parsed1.hasToolOrCode)
        assertEquals("⚙️ Herramienta: test_html_code", parsed1.tagTitle)
        assertEquals("[⚙️ Ejecutando...]", parsed1.statusBadge)
        assertFalse("Phase 1: cleanContent must NOT have raw DOCTYPE", parsed1.cleanContent.contains("DOCTYPE"))
        assertFalse("Phase 1: cleanContent must NOT leak JSON arguments", parsed1.cleanContent.contains("lang=\"es\""))
        assertEquals("Ejecutando herramienta 'test_html_code'...", parsed1.cleanContent)

        // Phase 2: Tool execution completed with result
        val phase2 = phase1 + "\n\\u003c/html\\u003e\"}\n```\n\n✅ **[Resultado MCP: `test_html_code`]**\n```json\n✅ [HTML Sandbox Verification Passed] (Carga: 291ms)\n• Estado: DOM montado correctamente.\n```"
        val parsed2 = ToolCodeBlockParser.parse(phase2)
        assertTrue("Phase 2: Must detect tool", parsed2.hasToolOrCode)
        assertEquals("[✅ Completado]", parsed2.statusBadge)
        assertTrue("Phase 2: Code must have result", parsed2.codeContent.contains("Resultado:"))
        assertFalse("Phase 2: cleanContent must NOT leak DOCTYPE", parsed2.cleanContent.contains("DOCTYPE"))
        assertEquals("Ejecución de herramienta 'test_html_code' completada.", parsed2.cleanContent)

        // Phase 3: Continuation turn arrives with assistant response and HTML game
        val phase3 = phase2 + "\n\nAquí tienes el juego completo de **Super Mario Bros** en HTML5.\n\n```html\n<!DOCTYPE html>\n<html lang=\"es\">\n<head><title>Mario</title></head>\n<body><canvas id=\"gameCanvas\"></canvas></body>\n</html>\n```\n\nPuedes guardarlo como archivo .html."
        val parsed3 = ToolCodeBlockParser.parse(phase3)
        assertTrue("Phase 3: Must detect tool", parsed3.hasToolOrCode)
        assertEquals("[✅ Completado]", parsed3.statusBadge)
        assertFalse("Phase 3: tool cleanContent must NOT have MCP Tool Call", parsed3.cleanContent.contains("MCP Tool Call"))
        assertFalse("Phase 3: tool cleanContent must NOT have Resultado MCP", parsed3.cleanContent.contains("Resultado MCP"))
        assertTrue("Phase 3: tool cleanContent must have assistant text", parsed3.cleanContent.contains("Super Mario Bros"))
        assertTrue("Phase 3: tool cleanContent must have html block", parsed3.cleanContent.contains("```html"))

        // Now run VisualMediaParser on parsed3.cleanContent
        val visual3 = VisualMediaParser.parse(parsed3.cleanContent)
        assertTrue("Phase 3: visual media must detect game", visual3.hasMedia)
        assertTrue("Phase 3: mediaSource must have gameCanvas", visual3.mediaSource.contains("gameCanvas"))
        assertFalse("Phase 3: visual cleanContent must NOT contain the html code block", visual3.cleanContent.contains("gameCanvas"))
        assertFalse("Phase 3: visual cleanContent must NOT contain DOCTYPE", visual3.cleanContent.contains("<!DOCTYPE"))
        assertTrue("Phase 3: visual cleanContent must retain explanation", visual3.cleanContent.contains("Super Mario Bros"))
        assertTrue("Phase 3: visual cleanContent must retain save instructions", visual3.cleanContent.contains("Puedes guardarlo"))
    }
}