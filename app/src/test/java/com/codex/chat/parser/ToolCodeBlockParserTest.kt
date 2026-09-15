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
}