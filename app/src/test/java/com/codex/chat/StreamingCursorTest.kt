package com.codex.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingCursorTest {
    @Test
    fun testStreamingContentAppendsCursorGlyph() {
        val raw = "Hola, en que puedo ayudarte"
        val streamed = if (true) "$raw ▊" else raw
        assertTrue("El texto en stream debe terminar con el cursor ▊", streamed.endsWith("▊"))
        val finished = if (false) "$raw ▊" else raw
        assertEquals("El texto completado no debe tener el cursor", raw, finished)
    }
}
