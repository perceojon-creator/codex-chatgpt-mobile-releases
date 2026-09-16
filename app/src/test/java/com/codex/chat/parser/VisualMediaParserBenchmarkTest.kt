package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import org.junit.Assert.*
import org.junit.Test

class VisualMediaParserBenchmarkTest {
    @Test
    fun test_unescape_and_normalize_no_redundant_allocations() {
        val input = "![Image](https://example.com/test.png\\n\\r\\t)"
        val normalized = VisualMediaParser.unescapeAndNormalize(input)
        assertFalse(normalized.contains("\\n"))
    }

    @Test
    fun test_unescape_and_normalize_handles_html_entities_and_unicode() {
        val input = "&lt;svg width=\"100\"&gt;&lt;/svg&gt;\\n"
        val normalized = VisualMediaParser.unescapeAndNormalize(input)
        assertTrue(normalized.contains("<svg width=\"100\">"))
        assertTrue(normalized.contains("</svg>"))
    }
}
