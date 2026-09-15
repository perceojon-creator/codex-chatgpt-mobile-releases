package com.codex.chat.investigacion

import com.codex.chat.core.media.VisualMediaParser
import org.junit.Test

class BenchLocalPipeline {
    @Test
    fun pipeline_coste_real() {
        val base64 = "/9j/4AAQSkZJRgABAQ".repeat(15000)
        val conMarcador = "Claro: \n\n![imagen-generada](data:image/jpeg;base64,$base64)\n\nListo."
        val sinDisparador = "Respuesta de texto normal sin medios visuales ni base64 " + "x".repeat(840000)

        // (1) parse() con marcador presente — caso REAL durante streaming y al reabrir
        var t0 = System.nanoTime(); repeat(5) { VisualMediaParser.parse(conMarcador) };
        val msCon = (System.nanoTime() - t0) / 1_000_000 / 5
        println("BENCH parse-con-marcador: $msCon ms/llamada")

        // (2) parse() sin disparador — gate activo
        t0 = System.nanoTime(); repeat(20) { VisualMediaParser.parse(sinDisparador) };
        val msGate = (System.nanoTime() - t0) / 1_000_000 / 20
        println("BENCH parse-gate-salto: $msGate ms/llamada")

        // (3) regex de línea 268 sola sobre 850KB
        val rx = Regex("\\\\\"\\s*\\}\\s*\$")
        t0 = System.nanoTime(); repeat(5) { rx.replace(conMarcador, "") };
        val msRx = (System.nanoTime() - t0) / 1_000_000 / 5
        println("BENCH regex-l268-sola: $msRx ms/llamada")

        // (4) replace del marcador completo
        val marker = "![imagen-generada](data:image/jpeg;base64,$base64)"
        t0 = System.nanoTime(); repeat(5) { conMarcador.replace(marker, "") };
        val msRep = (System.nanoTime() - t0) / 1_000_000 / 5
        println("BENCH replace-marcador: $msRep ms/llamada")

        // (5) JSONObject del chunk SSE de 852KB (lo que hace SseStreamParser.feedChunk)
        val chunk = "{\"id\":\"x\",\"choices\":[{\"delta\":{\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,$base64\"}}]}}]}"
        t0 = System.nanoTime(); repeat(3) { org.json.JSONObject(chunk) };
        val msJson = (System.nanoTime() - t0) / 1_000_000 / 3
        println("BENCH JSONObject-chunk: $msJson ms/llamada")
    }
}