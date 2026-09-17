package com.codex.chat.core.media

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VisualMediaParserAndroidTest {

    @Test
    fun test_visual_media_parser_loads_on_android_runtime() {
        val result = VisualMediaParser.parse("Mensaje de prueba en Android ART")
        assertNotNull(result)
        assertFalse(result.hasMedia)
    }

    @Test
    fun test_unescape_and_normalize_with_json_suffix() {
        val normalized = VisualMediaParser.unescapeAndNormalize("path/to/file.png\"}\n")
        assertFalse(normalized.endsWith("}"))
    }
}
