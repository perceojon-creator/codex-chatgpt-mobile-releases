package com.codex.chat.network

import com.codex.chat.core.parser.SseStreamParser
import org.junit.Assert.*
import org.junit.Test

class SseStreamParserPerformanceTest {
    @Test
    fun test_parse_delta_extracts_content_without_full_json_tree() {
        val sseChunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Hola mundo\"}}]}"
        val extracted = SseStreamParser.extractDeltaFast(sseChunk)
        assertEquals("Hola mundo", extracted)
    }

    @Test
    fun test_parse_delta_extracts_escaped_newlines_and_quotes() {
        val sseChunk = "data: {\"choices\":[{\"delta\":{\"content\":\"Línea 1\\n\\\"Citado\\\"\\nLínea 2\"}}]}"
        val extracted = SseStreamParser.extractDeltaFast(sseChunk)
        assertEquals("Línea 1\n\"Citado\"\nLínea 2", extracted)
    }

    @Test
    fun test_extract_delta_fast_returns_null_on_non_content_chunks() {
        val sseChunk = "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Pensando...\"}}]}"
        val extracted = SseStreamParser.extractDeltaFast(sseChunk)
        assertNull(extracted)
    }
}
