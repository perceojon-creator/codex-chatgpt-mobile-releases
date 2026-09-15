package com.codex.chat.investigacion

import com.codex.chat.core.media.VisualMediaHtmlBuilder
import com.codex.chat.core.media.VisualMediaParser
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import java.util.Locale

/**
 * INVESTIGACIÓN B — SOLO BENCHMARK (no modifica código de producción).
 *
 * Reproduce el coste de REABRIR una conversación cuyas imágenes generadas se
 * persisten como base64 dentro del content del mensaje, en el ÚNICO archivo
 * filesDir/chatgpt_local_history.json (LocalChatRepository).
 *
 * Cada imagen llega como UN delta SSE (~852 KB) y el content persistido
 * conserva el marcador completo: ![imagen-generada](data:image/jpeg;base64,...)
 */
class InvestigacionBReloadBench {

    private fun ms(nanos: Long): Double = nanos / 1_000_000.0
    private fun f(v: Double): String = String.format(Locale.US, "%.2f", v)

    @Test
    fun bench_reabrir_conversacion_con_imagenes_base64() {
        // Imagen sintética: 55000 × "/9j/4AAQSkZJRgABAQ" (18 chars) ≈ 970 KB por content.
        // Replica el delta SSE real de ~852 KB que llega de gemini-3.1-flash-image.
        val b64 = "/9j/4AAQSkZJRgABAQ".repeat(55000)
        val contentImagen = "Claro: \n\n![imagen-generada](data:image/jpeg;base64,${b64})\n\nListo."

        // ---------- Construcción del historial (réplica de persistAll, LocalChatRepository.kt:97-126) ----------
        fun buildHistorial(sesiones: Int, mensajesPorSesion: Int): JSONArray {
            val historial = JSONArray()
            repeat(sesiones) { s ->
                val msgArr = JSONArray()
                repeat(mensajesPorSesion) { m ->
                    msgArr.put(
                        JSONObject()
                            .put("role", if (m == 0) "user" else "assistant")
                            .put("content", contentImagen)
                            .put("reasoning", "")
                    )
                }
                historial.put(
                    JSONObject()
                        .put("id", "session-${s}")
                        .put("title", "Sesión de prueba ${s}")
                        .put("timestamp", 1_700_000_000_000L + s)
                        .put("messages", msgArr)
                )
            }
            return historial
        }

        val historialText = buildHistorial(5, 2).toString()      // archivo multi-MB en disco
        val unaSesionText = buildHistorial(1, 1).toString()      // archivo mínimo con 1 imagen

        println("BENCH-B bytes content con imagen: ${contentImagen.length} chars (${contentImagen.length / 1024} KB)")
        println("BENCH-B bytes historial 5 sesiones × 2 imgs: ${historialText.length} chars (${historialText.length / 1024} KB)")
        println("BENCH-B bytes 1 sesión × 1 img: ${unaSesionText.length} chars (${unaSesionText.length / 1024} KB)")

        // ---------- (a) Parseo del historial grande: JSONArray(texto) — 3 iteraciones ----------
        // Esto es EXACTAMENTE LocalChatRepository.getAllSessions() línea 36 (JSONArray(content)),
        // que corre en el hilo UI desde loadDrawerHistory (MainActivity.kt:322) y loadLocalSession (341).
        JSONArray(historialText) // warm-up JIT
        val tA = mutableListOf<Double>()
        repeat(3) {
            val t0 = System.nanoTime()
            JSONArray(historialText)
            tA.add(ms(System.nanoTime() - t0))
        }
        println("BENCH-B (a) JSONArray(historial 5×2 imgs, ${historialText.length / 1024} KB) ms/iter: [${tA.joinToString(", ") { f(it) }}] media=${f(tA.average())} ms")

        // ---------- (b) Parseo de 1 sesión con 1 imagen — 5 iteraciones ----------
        JSONArray(unaSesionText) // warm-up
        val tB = mutableListOf<Double>()
        repeat(5) {
            val t0 = System.nanoTime()
            JSONArray(unaSesionText)
            tB.add(ms(System.nanoTime() - t0))
        }
        println("BENCH-B (b) JSONArray(1 sesión × 1 img, ${unaSesionText.length / 1024} KB) ms/iter: [${tB.joinToString(", ") { f(it) }}] media=${f(tB.average())} ms")

        // ---------- (c) persistAll simulado: JSONArray.toString(2) del historial grande — 3 iteraciones ----------
        // LocalChatRepository.kt:126 — storageFile.writeText(array.toString(2)) reescribe TODO el historial.
        val parsed = JSONArray(historialText)
        parsed.toString(2) // warm-up
        val tC = mutableListOf<Double>()
        repeat(3) {
            val t0 = System.nanoTime()
            parsed.toString(2)
            tC.add(ms(System.nanoTime() - t0))
        }
        println("BENCH-B (c) persistAll toString(2) historial 5×2 imgs ms/iter: [${tC.joinToString(", ") { f(it) }}] media=${f(tC.average())} ms")

        // ---------- (d) areContentsTheSame: o.content == n.content sobre strings de ~770KB — 10 repeticiones ----------
        // ChatAdapter.kt:109 — DiffUtil.areContentsTheSame tras recargar del disco (referencias DISTINTAS).
        val contentA = contentImagen
        val contentB = StringBuilder(contentImagen).toString() // misma data, referencia distinta (como tras re-parse)
        check(contentA.length == contentB.length)
        val tD = mutableListOf<Double>()
        repeat(10) {
            val t0 = System.nanoTime()
            val eq = contentA == contentB
            check(eq)
            tD.add(ms(System.nanoTime() - t0))
        }
        println("BENCH-B (d) areContentsTheSame content==content (${contentA.length / 1024} KB) ×10: total=${f(tD.sum())} ms media=${f(tD.average())} ms min=${f(tD.min())} ms max=${f(tD.max())} ms")

        // ---------- (e) VisualMediaParser.parse() sobre el content persistido — 3 llamadas ----------
        // ChatAdapter.kt:214 — corre en cada onBindViewHolder de cada mensaje con imagen.
        VisualMediaParser.parse(contentImagen) // warm-up
        val tE = mutableListOf<Double>()
        var visualSource = ""
        repeat(3) {
            val t0 = System.nanoTime()
            val v = VisualMediaParser.parse(contentImagen)
            tE.add(ms(System.nanoTime() - t0))
            visualSource = v.mediaSource
        }
        println("BENCH-B (e) VisualMediaParser.parse(content ${contentImagen.length / 1024} KB) ms/llamada: [${tE.joinToString(", ") { f(it) }}] media=${f(tE.average())} ms")

        // ---------- (f) EXTRA: VisualMediaHtmlBuilder.buildHtml + tamaño del HTML que va al WebView ----------
        // ChatAdapter.kt:232-233 — webViewMedia.loadDataWithBaseURL(html) por imagen renderizada.
        val tipoImagen = com.codex.chat.core.media.VisualMediaType.IMAGE
        VisualMediaHtmlBuilder.buildHtml(tipoImagen, visualSource) // warm-up
        val tF = mutableListOf<Double>()
        var htmlLen = 0
        repeat(3) {
            val t0 = System.nanoTime()
            val html = VisualMediaHtmlBuilder.buildHtml(tipoImagen, visualSource)
            tF.add(ms(System.nanoTime() - t0))
            htmlLen = html.length
        }
        println("BENCH-B (f) EXTRA buildHtml(IMAGE) ms/llamada: [${tF.joinToString(", ") { f(it) }}] media=${f(tF.average())} ms; HTML=${htmlLen / 1024} KB por imagen")

        // ---------- RESUMEN: coste derivado por acción ----------
        val msPorImagenEnParse = (tA.average() - tB.average()) / 9.0 // 10 imgs vs 1 img
        println("BENCH-B RESUMEN ms-por-imagen-extra dentro del parse global: ~${f(msPorImagenEnParse)} ms")
        println("BENCH-B RESUMEN abrir drawer (1 parse hilo UI, 10 imgs): ~${f(tA.average())} ms")
        println("BENCH-B RESUMEN abrir conversación (1 parse hilo UI + 10×(e)): ~${f(tA.average() + 10 * tE.average())} ms")
        println("BENCH-B RESUMEN enviar 1 mensaje (3 parses: 2 bg + 1 UI + toString): ~${f(3 * tA.average() + tC.average())} ms")
    }
}
