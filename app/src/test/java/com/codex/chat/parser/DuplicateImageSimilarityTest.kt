package com.codex.chat.parser

import com.codex.chat.core.parser.SseStreamParser
import com.codex.chat.core.media.VisualMediaParser
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/**
 * Regresión del bug REAL del usuario: pide 1 imagen de "un perro cantando",
 * el proxy la envía DOS VECES con codificaciones JPEG ligeramente distintas
 * (re-compresión), por lo que el SHA-256 no coincide y la app muestra
 * "1 de 2" con la misma imagen repetida.
 */
class DuplicateImageSimilarityTest {

    private fun buildChunk(b64: String): String {
        val delta = JSONObject().put(
            "images", org.json.JSONArray().put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64))
            )
        )
        return JSONObject()
            .put("id", "img_dup")
            .put("object", "chat.completion.chunk")
            .put("model", "gemini-3.1-flash-image")
            .put("choices", org.json.JSONArray().put(JSONObject().put("index", 0).put("delta", delta)))
            .toString()
    }

    @Test
    fun imagen_recomprimida_por_el_proxy_no_debe_duplicarse() {
        val listener = RecordingListener()
        val parser = SseStreamParser(listener)

        // Misma imagen lógica, pero el proxy re-comprime el JPEG → bytes distintos
        val b64v1 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" + "A".repeat(400)
        val b64v2 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" + "A".repeat(400) + "BBBB"  // relleno extra

        parser.feedChunk("data: " + buildChunk(b64v1) + "\n\n")
        parser.feedChunk("data: " + buildChunk(b64v2) + "\n\n")
        parser.feedChunk("data: [DONE]\n\n")
        parser.close()

        val content = listener.finalContent
        val parsed = VisualMediaParser.parse(content)

        println("Markers en contenido: " + Regex("!\\\\").findAll(content).count())
        println("imageSources detectadas: " + parsed.imageSources.size)

        // CON SIMILITUD VISUAL (dimensión + píxeles muestreados): debe quedar 1
        assertEquals("Debe mostrar exactamente 1 imagen aunque el proxy la re-comprima", 1, parsed.imageSources.size)
    }

    @Test
    fun imagenes_realmente_distintas_no_se_colapsan() {
        val listener = RecordingListener()
        val parser = SseStreamParser(listener)

        // Imagen A: patrón de bytes alfa
        val b64imgA = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" + "A".repeat(400)
        // Imagen B: patrón de bytes completamente diferente (otra imagen real)
        val b64imgB = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" + "Z".repeat(300)

        parser.feedChunk("data: " + buildChunk(b64imgA) + "\n\n")
        parser.feedChunk("data: " + buildChunk(b64imgB) + "\n\n")
        parser.feedChunk("data: [DONE]\n\n")
        parser.close()

        val parsed = VisualMediaParser.parse(listener.finalContent)
        assertEquals("Dos imágenes diferentes deben mostrarse como 2", 2, parsed.imageSources.size)
    }

    private class RecordingListener : SseStreamParser.SseEventListener {
        var finalContent: String = ""
        override fun onContentDelta(delta: String) {}
        override fun onReasoningDelta(delta: String) {}
        override fun onComplete(fullContent: String, fullReasoning: String) { finalContent = fullContent }
        override fun onToolCallsReceived(toolCalls: List<com.codex.chat.core.parser.SseStreamParser.CompletedToolCall>) {}
        override fun onError(error: Throwable) { fail("No se esperaba error: " + error.message) }
    }
}
