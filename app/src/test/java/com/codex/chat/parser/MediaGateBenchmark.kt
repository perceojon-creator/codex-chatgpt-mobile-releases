package com.codex.chat.parser

import com.codex.chat.core.media.VisualMediaParser
import org.junit.Test

class MediaGateBenchmark {

    @Test
    fun gate_performance_on_image_heavy_streaming() {
        // Simula el peor caso real medido en la conversación con gemini-3.1-flash-image:
        // un delta de ~852 KB de base64 (data:image/jpeg) acumulado token a token.
        val chunk = "![imagen-generada](data:image/jpeg;base64," + "/9j/4AAQSkZJRgABAQ".repeat(60000) + ")"
        val deltas = 60 // tokens de stream simulados
        val sb = StringBuilder()
        val t0 = System.nanoTime()
        repeat(deltas) {
            sb.append(chunk.substring((it * 2000), (it * 2000) + 2000))
            VisualMediaParser.parse(sb.toString()) // lo que hace el adapter por delta
        }
        val elapsedMs = (System.nanoTime() - t0) / 1_000_000
        // Sin el gate, cada parse() ejecutaba 8+ regex sobre hasta 850 KB → congelamiento (ANR).
        // Con el gate, el contenido SIN disparadores se descarta con indexOf en ~1-3 ms por delta.
        println("BENCH gate: " + deltas + " deltas de stream parseados en " + elapsedMs + " ms (" + (elapsedMs / deltas) + " ms/delta)")
        org.junit.Assert.assertTrue("El parseo por delta debe ser < 50 ms para no congelar la UI", elapsedMs / deltas < 50)
    }

    @Test
    fun gate_no_rompe_deteccion_cuando_hay_disparador() {
        val content = "Mira: ![imagen-generada](data:image/jpeg;base64,/9j/4AAQSkZJRg==) y texto."
        val parsed = VisualMediaParser.parse(content)
        org.junit.Assert.assertTrue(parsed.hasMedia)
        org.junit.Assert.assertEquals("data:image/jpeg;base64,/9j/4AAQSkZJRg==", parsed.mediaSource)
    }
}