package com.codex.chat.investigacion

import com.codex.chat.core.media.VisualMediaParser
import org.json.JSONObject
import org.junit.Test

/**
 * INVESTIGACIÓN (solo lectura + benchmarks): por qué la app se congela
 * cuando llega el delta con la imagen generada (data-URL base64 ~852KB).
 *
 * Simula exactamente la cadena de render real:
 *  SseStreamParser (JSONObject + marker) -> MainActivity.onContentDelta
 *  -> ChatAdapter.updateLastMessage -> bindVisualMediaAndContent
 *  -> VisualMediaParser.parse sobre el buffer ACUMULADO en cada delta.
 */
class InvestigacionAGenerateBench {

    private fun bench(label: String, iterations: Int, warmup: Int = 1, block: () -> Unit): Long {
        repeat(warmup) { block() }
        val t0 = System.nanoTime()
        repeat(iterations) { block() }
        val totalMs = (System.nanoTime() - t0) / 1_000_000
        val perCallMs = totalMs.toDouble() / iterations
        println(String.format("BENCH %s: %d ms total / %.2f ms-por-llamada (%d iteraciones)", label, totalMs, perCallMs, iterations))
        return totalMs
    }

    @Test
    fun costo_cadena_render_imagen_generada() {
        // ~850KB de base64 (fragmento JPEG real repetido), como el data-URL del proxy
        val base64 = "/9j/4AAQSkZJRgABAQ".repeat(12000)
        val dataUrl = "data:image/jpeg;base64,$base64"
        val marker = "![imagen-generada]($dataUrl)"
        val content = "texto $marker fin"

        println("=== TAMAÑOS ===")
        println("content con marcador: ${content.length} chars (~${content.length / 1024} KB)")
        println("base64 puro: ${base64.length} chars (~${base64.length / 1024} KB)")

        // ------------------------------------------------------------------
        // (a) VisualMediaParser.parse() con el marcador de imagen PRESENTE.
        //     En streaming, bindVisualMediaAndContent (ChatAdapter.kt:213)
        //     ejecuta parse() sobre el buffer acumulado en CADA delta.
        //     El gate de VisualMediaParser.kt:83-102 NO salta nada aquí:
        //     "data:image/" y "![" están presentes -> pipeline regex completo.
        // ------------------------------------------------------------------
        val a = bench("A parse-con-imagen", iterations = 20) {
            VisualMediaParser.parse(content)
        }

        // ------------------------------------------------------------------
        // (b) Mismo tamaño de contenido pero SIN ningún disparador de medios:
        //     el gate (VisualMediaParser.kt:83-102) hace return inmediato.
        // ------------------------------------------------------------------
        val prefix = "Respuesta de texto normal sin medios "
        val sinDisparador = prefix + "x".repeat(content.length - prefix.length)
        val b = bench("B parse-gate-salto (sin disparador)", iterations = 20) {
            VisualMediaParser.parse(sinDisparador)
        }

        // ------------------------------------------------------------------
        // (c) JSONObject(chunk) del chunk SSE de 852KB — lo que ejecuta
        //     SseStreamParser.processLine (línea 116) por cada chunk de red.
        // ------------------------------------------------------------------
        val chunkSse = "{\"id\":\"chatcmpl-x\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\",\"images\":[{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/jpeg;base64,$base64\"}}]}}]}"
        println("chunk SSE: ${chunkSse.length} chars (~${chunkSse.length / 1024} KB)")
        val c = bench("C JSONObject-chunk-SSE", iterations = 5) {
            JSONObject(chunkSse)
        }

        // ------------------------------------------------------------------
        // (d) content.replace(marker, "") — el costo de la copia que hace
        //     VisualMediaParser.parse (línea 220: normalized.replace(...))
        //     al construir cleanContent sobre 850KB.
        // ------------------------------------------------------------------
        val d = bench("D replace-marcador-850KB", iterations = 10) {
            content.replace(marker, "")
        }

        // ------------------------------------------------------------------
        // (e) RAZONAMIENTO POR CÓDIGO: ¿cuántas loadDataWithBaseURL ocurrirían
        //     si el base64 llegara fragmentado en 20 deltas?
        //     MARKDOWN_IMAGE_REGEX (línea 46-48) exige ")" de cierre; mientras
        //     el data-URL está incompleto NO coincide -> hasMedia=false ->
        //     lastLoadedMediaSource=null (ChatAdapter.kt:238) y tvContent
        //     recibe el CONTENIDO CRUDO (bindToolAndContent, tvContent.text=rawText
        //     línea 284): 850KB de base64 al TextView. Solo el delta final que
        //     cierra ")" hace coincidir la regex -> exactamente 1 loadData.
        //     Pero como el proxy entrega la imagen EN UN SOLO chunk, la primera
        //     llamada a parse() YA coincide y dispara el WebView con HTML de 852KB.
        // ------------------------------------------------------------------
        println("=== E loadDataWithBaseURL bajo fragmentación (razonado por código) ===")
        var deltasSinMedia = 0
        var cargasWebView = 0
        val step = base64.length / 20
        val acumulado = StringBuilder("texto ![imagen-generada](data:image/jpeg;base64,")
        for (i in 1..20) {
            acumulado.append(base64.substring((i - 1) * step, i * step))
            if (i < 20) acumulado.append(")") else acumulado.append(" fin")
            val v = VisualMediaParser.parse(acumulado.toString())
            if (v.hasMedia) cargasWebView++ else deltasSinMedia++
            if (i < 20) acumulado.setLength(acumulado.length - 1) // revertir ")" de prueba
        }
        println("BENCH E fragmentacion-20-deltas: deltas SIN media (regex incompleta, tvContent=base64 CRUDO via ChatAdapter.kt:284): $deltasSinMedia; deltas que disparan WebView loadDataWithBaseURL: $cargasWebView")
        println("BENCH E conclusion: solo el delta que cierra ')' coincide -> 1 loadDataWithBaseURL; con el proxy actual (UN chunk de 852KB) la PRIMERA parse() ya coincide -> 1 loadData con HTML ~852KB (VisualMediaHtmlBuilder.buildImageBody:135-137).")

        // ------------------------------------------------------------------
        // RESUMEN
        // ------------------------------------------------------------------
        println("=== RESUMEN BENCH A-GENERATE (ms) ===")
        println("parse 850KB con imagen (20 deltas): TOTAL=$a ms, por-delta=${a / 20} ms")
        println("parse 850KB sin disparador (gate): TOTAL=$b ms, por-delta=${b / 20} ms")
        println("JSONObject chunk 852KB: TOTAL=$c ms, por-chunk=${c / 5} ms")
        println("replace marcador 850KB: TOTAL=$d ms, por-llamada=${d / 10} ms")
    }
}
