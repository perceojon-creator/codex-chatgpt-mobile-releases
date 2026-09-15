package com.codex.chat.network

import com.codex.chat.core.network.ReasoningTranslator
import org.junit.Assert.*
import org.junit.Test

class ReasoningTranslatorTest {

    private fun makeTranslator(collected: MutableList<String>): ReasoningTranslator {
        return ReasoningTranslator(
            client = okhttp3.OkHttpClient(),
            baseUrl = "http://127.0.0.1:1",
            apiKey = "test",
            translationModel = "unused",
            onTranslated = { collected.add(it) }
        )
    }

    @Test
    fun frase_en_espanol_pasa_directa_sin_traducir() {
        val out = mutableListOf<String>()
        val tr = makeTranslator(out)
        tr.onDelta("Vamos a analizar el problema del usuario con cuidado.")
        tr.flush()
        Thread.sleep(200)
        assertTrue(out.contains("Vamos a analizar el problema del usuario con cuidado."))
    }

    @Test
    fun segmentacion_por_frases_con_multiples_deltas() {
        val out = mutableListOf<String>()
        val tr = makeTranslator(out)
        tr.onDelta("Okay, the user wants an image. ")
        tr.onDelta("I will generate it now with the tool.")
        tr.flush()
        Thread.sleep(400)
        val joined = out.joinToString(" ")
        assertTrue(joined.contains("the user wants an image"))
        assertTrue(joined.contains("generate it now"))
    }

    @Test
    fun flush_traduce_el_remanente_parcial() {
        val out = mutableListOf<String>()
        val tr = makeTranslator(out)
        tr.onDelta("frase incompleta sin cierre")
        tr.flush()
        Thread.sleep(300)
        assertEquals(1, out.size)
    }
}