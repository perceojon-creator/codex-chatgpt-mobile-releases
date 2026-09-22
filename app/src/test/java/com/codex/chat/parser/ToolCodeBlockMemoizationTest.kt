package com.codex.chat.parser

import com.codex.chat.core.parser.ToolCodeBlockParser
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCodeBlockMemoizationTest {

    @Test
    fun testRepeatedParseReturnsMemoizedInstance() {
        val sample = "⚙️ **[MCP Tool Call: `read_file`]**\n```json\n{\"path\": \"test.txt\"}\n```"
        val first = ToolCodeBlockParser.parse(sample)
        val second = ToolCodeBlockParser.parse(sample)

        assertTrue(first.hasToolOrCode)
        assertSame("Al parsear el mismo texto de entrada repetidas veces debe devolver la instancia memoizada de la caché LRU", first, second)
    }
}
