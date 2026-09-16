package com.codex.chat.network

import com.codex.chat.core.network.ReasoningTranslator
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue

class ReasoningTranslatorAsyncTest {

    @Test
    fun test_rapid_burst_drains_without_dropping_and_flushes_completely() {
        val collected = ConcurrentLinkedQueue<String>()
        val translator = ReasoningTranslator(
            client = okhttp3.OkHttpClient(),
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test",
            translationModel = "test",
            onTranslated = { collected.add(it) }
        )

        // Enviar ráfaga de 20 frases en español
        for (i in 1..20) {
            translator.onDelta("Esta es la frase de razonamiento número $i en español con sentido completo.\n")
        }
        translator.flush(timeoutMs = 2000L)

        assertEquals(20, collected.size)
        val list = collected.toList()
        assertTrue(list.first().contains("número 1"))
        assertTrue(list.last().contains("número 20"))
    }
}
