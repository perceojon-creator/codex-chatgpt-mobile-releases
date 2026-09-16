package com.codex.chat.ui

import com.codex.chat.StreamingTextPayload
import org.junit.Assert.*
import org.junit.Test

class StreamingPayloadIsolationTest {
    @Test
    fun test_payload_model_integrity() {
        val payload = StreamingTextPayload("Respuesta parcial", "Pensando...")
        assertEquals("Respuesta parcial", payload.text)
        assertEquals("Pensando...", payload.reasoningText)
    }
}
