package com.codex.chat.core.media

import org.junit.Assert.*
import org.junit.Test

class VisualMediaParserClinitTest {

    @Test
    fun test_visual_media_parser_class_loads_without_exception() {
        val res = VisualMediaParser.parse("Hola mundo")
        assertFalse(res.hasMedia)
        assertEquals("Hola mundo", res.cleanContent)
    }

    @Test
    fun test_clean_raw_path_json_arg_suffix_removal() {
        val input = "file:///storage/emulated/0/Download/image.png\"}"
        val parsed = VisualMediaParser.parse(input)
        assertNotNull(parsed)
    }
}
