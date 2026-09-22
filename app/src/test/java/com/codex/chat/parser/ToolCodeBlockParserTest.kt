package com.codex.chat.parser

import com.codex.chat.core.parser.ToolCodeBlockParser
import org.junit.Assert.*
import org.junit.Test

class ToolCodeBlockParserTest {

    @Test
    fun parse_mcp_tool_result_correctly() {
        val raw = "Consultando batería...\n\n✅ **[Resultado MCP: `get_battery_status`]**\n" +
                  "```json\n{\"level\": 85, \"isCharging\": false}\n```\n\nLa batería está al 85%."

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("get_battery_status"))
        assertEquals("[✅ Completado]", parsed.statusBadge)
        assertTrue(parsed.codeContent.contains("\"level\": 85"))
        assertTrue(parsed.cleanContent.contains("La batería está al 85%"))
    }

    @Test
    fun parse_mcp_tool_call_and_result_both_present() {
        val raw = "Aquí tienes el diseño del superdeportivo.\n\n" +
                  "⚙️ **[MCP Tool Call: `write_file`]**\n" +
                  "{\"content\": \"<!DOCTYPE html><html></html>\", \"file_path\": \"Download/carro.html\"}\n\n" +
                  "✅ **[Resultado MCP: `write_file`]**\n" +
                  "```json\n{\"success\": true, \"message\": \"Guardado\"}\n```\n\n" +
                  "He guardado el archivo en Download/carro.html."

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("write_file"))
        assertEquals("[✅ Completado]", parsed.statusBadge)
        assertTrue(parsed.codeContent.contains("// Argumentos:"))
        assertTrue(parsed.codeContent.contains("// Resultado:"))
        assertFalse("No debe filtrar JSON crudo en el texto limpio", parsed.cleanContent.contains("\"file_path\""))
        assertTrue(parsed.cleanContent.contains("He guardado el archivo en Download/carro.html"))
    }

    @Test
    fun parse_user_exact_escaped_tool_call_without_emojis() {
        val raw = """
**[MCP Tool Call: `web_search`]**
```json
{"query": "noticias tecnologia hoy inteligencia artificial gadgets ciberseguridad","max_results": 6}
```

✅ **[Resultado MCP: `web_search`]**
```json
{"results": ["noticia 1", "noticia 2"]}
```

Aquí tienes las noticias de hoy:
1. Noticia 1
2. Noticia 2
""".trimIndent()

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("web_search"))
        assertEquals("[✅ Completado]", parsed.statusBadge)
        assertTrue(parsed.codeContent.contains("noticias tecnologia hoy"))
        assertTrue(parsed.codeContent.contains("noticia 1"))
        assertFalse(parsed.cleanContent.contains("MCP Tool Call"))
        assertFalse(parsed.cleanContent.contains("Resultado MCP"))
        assertFalse(parsed.cleanContent.contains("web_search"))
        assertTrue(parsed.cleanContent.contains("Aquí tienes las noticias de hoy:"))
        assertTrue(parsed.cleanContent.contains("1. Noticia 1"))
    }

    @Test
    fun parse_namespaced_tool_and_missing_icons() {
        val raw = """
**[Tool Call: `github:search_repos`]**
```json
{"q": "chatgpt"}
```

**[Tool Result: `github:search_repos`]**
```json
{"count": 10}
```

Encontré 10 repositorios.
""".trimIndent()

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("github:search_repos"))
        assertEquals("[✅ Completado]", parsed.statusBadge)
        assertTrue(parsed.cleanContent.contains("Encontré 10 repositorios."))
    }

    @Test
    fun parse_multiple_tools_aggregated() {
        val raw = """
⚙️ **[MCP Tool Call: `web_search`]**
```json
{"q": "kotlin"}
```
✅ **[Resultado MCP: `web_search`]**
```json
{"found": true}
```
⚙️ **[MCP Tool Call: `write_file`]**
```json
{"path": "test.kt"}
```
✅ **[Resultado MCP: `write_file`]**
```json
{"saved": true}
```

Búsqueda y guardado completados con éxito.
""".trimIndent()

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("Herramientas (2)"))
        assertTrue(parsed.tagTitle.contains("web_search"))
        assertTrue(parsed.tagTitle.contains("write_file"))
        assertTrue(parsed.cleanContent.contains("Búsqueda y guardado completados con éxito."))
    }

    @Test
    fun test_has_tool_markers_fast_detector() {
        assertTrue(ToolCodeBlockParser.hasToolMarkers("**[MCP Tool Call: `web_search`]**"))
        assertTrue(ToolCodeBlockParser.hasToolMarkers("✅ **[Resultado MCP: `web_search`]**"))
        assertTrue(ToolCodeBlockParser.hasToolMarkers("⚙️ Ejecutando"))
        assertTrue(ToolCodeBlockParser.hasToolMarkers("Herramienta: test"))
        assertFalse(ToolCodeBlockParser.hasToolMarkers("Hola, ¿cómo estás?"))
        assertFalse(ToolCodeBlockParser.hasToolMarkers("El análisis fue completado sin novedades."))
    }

    @Test
    fun parse_mcp_tool_call_alone_in_progress() {
        val raw = "⚙️ **[MCP Tool Call: `write_file`]**\n" +
                  "```json\n{\"file_path\": \"Download/carro.html\"}\n```"

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("write_file"))
        assertEquals("[⚙️ Ejecutando...]", parsed.statusBadge)
        assertFalse("No debe filtrar JSON crudo en cleanContent", parsed.cleanContent.contains("\"file_path\""))
    }

    @Test
    fun parse_e2b_python_output_correctly() {
        val raw = "### 🐍 Salida E2B Cloud (MicroVM en la nube)\n" +
                  "```text\n144.0\n```\n*Sandbox ID:* `sbx_12345`"

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("E2B Cloud"))
        assertEquals("144.0", parsed.codeContent)
    }

    @Test
    fun parse_long_code_block() {
        val raw = "Aquí tienes tu script en python:\n" +
                  "```python\nimport os\nimport sys\nprint(\"hello world\")\n```\nListo para usar."

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue(parsed.hasToolOrCode)
        assertTrue(parsed.tagTitle.contains("python"))
        assertTrue(parsed.codeContent.contains("import os"))
    }

    @Test
    fun parse_plain_text_without_code() {
        val raw = "Hola, ¿en qué puedo ayudarte hoy?"
        val parsed = ToolCodeBlockParser.parse(raw)
        assertFalse(parsed.hasToolOrCode)
        assertEquals(raw, parsed.cleanContent)
    }

    @Test
    fun parse_multiple_mcp_executions_and_synthesis_into_single_collapsible_tool_block() {
        val raw = """
⚡ *Sintetizando respuesta con los datos obtenidos…*

⚙️ *Ejecutando 1 herramienta(s) MCP:* `termux_execute_bash`…
✓ **[Resultado: `termux_execute_bash`]**
```json
{"output": "Linux localhost 4.19"}
```

⚡ *Sintetizando respuesta con los datos obtenidos…*

⚙️ *Ejecutando 1 herramienta(s) MCP:* `get_storage_info`…
✓ **[Resultado: `get_storage_info`]**
```json
{"available_gb": 45}
```

⚡ *Sintetizando respuesta con los datos obtenidos…*

Aquí tienes el informe del sistema: el kernel es 4.19 y tienes 45 GB libres.
""".trimIndent()

        val parsed = ToolCodeBlockParser.parse(raw)
        assertTrue("Debe detectar que contiene herramientas", parsed.hasToolOrCode)
        assertFalse("cleanContent no debe contener 'Sintetizando respuesta'", parsed.cleanContent.contains("Sintetizando respuesta"))
        assertFalse("cleanContent no debe contener 'Ejecutando 1 herramienta'", parsed.cleanContent.contains("Ejecutando 1 herramienta"))
        assertFalse("cleanContent no debe contener el JSON crudo de resultado", parsed.cleanContent.contains("available_gb"))
        assertTrue("cleanContent debe contener la respuesta final limpia", parsed.cleanContent.contains("Aquí tienes el informe del sistema"))
        assertTrue("codeContent debe contener los pasos y resultados", parsed.codeContent.contains("termux_execute_bash") && parsed.codeContent.contains("available_gb"))
    }
}
