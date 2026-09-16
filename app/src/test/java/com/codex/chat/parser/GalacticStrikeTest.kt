package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Test

class GalacticStrikeTest {
    @Test
    fun testGalacticStrike() {
        val text = GalacticStrikeTest::class.java.getResourceAsStream("/galactic_strike.txt")?.bufferedReader()?.readText() ?: ""
        assertTrue("Resource must not be empty", text.isNotEmpty())
        val parsed = VisualMediaParser.parse(text)
        println("parsed.hasMedia: ${parsed.hasMedia}")
        println("parsed.type: ${parsed.type}")
        println("parsed.title: ${parsed.title}")
        println("parsed.mediaSource.length: ${parsed.mediaSource.length}")
        assertTrue("Debe tener hasMedia = true", parsed.hasMedia)
    }
}
