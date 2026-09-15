package com.codex.chat.parser

import com.codex.chat.core.parser.SseStreamParser

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

/**
 * Regresión del bug: gemini-3.1-flash-image genera imágenes que llegan en
 * delta.images (extensión del proxy CLIProxyAPI) pero la APK las descartaba.
 * Formato real verificado contra el proxy vivo:
 *   {"choices":[{"delta":{"images":[{"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}]}}]}
 */
class DeltaImagesParsingTest {

    private fun buildChunk(b64: String): String {
        val delta = JSONObject().put(
            "images", org.json.JSONArray().put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", "data:image/jpeg;base64," + b64))
            )
        )
        val chunk = JSONObject()
            .put("id", "img_test")
            .put("object", "chat.completion.chunk")
            .put("model", "gemini-3.1-flash-image")
            .put("choices", org.json.JSONArray().put(JSONObject().put("index", 0).put("delta", delta)))
        return chunk.toString()
    }

    @Test
    fun delta_images_se_convierte_en_marker_markdown_y_se_renderiza_como_imagen() {
        val listener = RecordingListener()
        val parser = SseStreamParser(listener)
        val b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" + "A".repeat(200)
        parser.feedChunk("data: " + buildChunk(b64) + "\n\n")
        parser.feedChunk("data: [DONE]\n\n")
        parser.close()

        val content = listener.finalContent
        assertTrue("El contenido final debe contener el marker markdown de imagen", content.contains("![imagen-generada](") && (content.contains("file://") || content.contains("data:image/")))
        assertFalse("No debe quedar JSON crudo del delta en el contenido", content.contains("\"images\""))

        // El VisualMediaParser debe reconocer el marker como media IMAGE y limpiar el bubble
        val parsed = VisualMediaParser.parse(content)
        assertTrue("VisualMediaParser debe detectar la imagen", parsed.hasMedia)
        assertEquals(VisualMediaType.IMAGE, parsed.type)
        assertTrue("mediaSource debe ser la URI o data-URL de imagen", parsed.mediaSource.startsWith("file://") || parsed.mediaSource.startsWith("data:image/"))
        assertFalse("El bubble no debe mostrar base64 crudo", parsed.cleanContent.contains("base64"))
    }

    @Test
    fun b64_json_plano_se_normaliza_a_data_url() {
        val listener = RecordingListener()
        val parser = SseStreamParser(listener)
        val delta = JSONObject().put("images", org.json.JSONArray().put(JSONObject().put("b64_json", "QUJDREVG")))
        val chunk = JSONObject()
            .put("choices", org.json.JSONArray().put(JSONObject().put("index", 0).put("delta", delta)))
        parser.feedChunk("data: " + chunk.toString() + "\n\n")
        parser.feedChunk("data: [DONE]\n\n")
        parser.close()
        val c = listener.finalContent
        assertTrue(c.contains("![imagen-generada](") && (c.contains("file://") || c.contains("QUJDREVG")))
    }

    @Test
    fun chunk_con_imagen_repetida_por_el_proxy_se_deduplica_correctamente() {
        val listener = RecordingListener()
        val parser = SseStreamParser(listener)
        val b64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQ" + "B".repeat(150)
        val chunkStr = buildChunk(b64)

        // Simular Chunk 1 (generación inicial)
        parser.feedChunk("data: " + chunkStr + "\n\n")

        // Simular Chunk 2 (cierre del proxy repitiendo la misma imagen)
        parser.feedChunk("data: " + chunkStr + "\n\n")

        parser.feedChunk("data: [DONE]\n\n")
        parser.close()

        val content = listener.finalContent

        // Contar ocurrencias de "![imagen-generada]("
        val firstIdx = content.indexOf("![imagen-generada](")
        val secondIdx = content.indexOf("![imagen-generada](", firstIdx + 1)
        assertTrue("Debe existir al menos un marcador de imagen", firstIdx != -1)
        assertEquals("NO debe existir un segundo marcador para la misma imagen idéntica", -1, secondIdx)

        // VisualMediaParser debe detectar exactamente 1 sola imagen
        val parsed = VisualMediaParser.parse(content)
        assertTrue(parsed.hasMedia)
        assertEquals("Debe tener exactamente 1 imagen, no 2 imágenes idénticas", 1, parsed.imageSources.size)
        assertFalse("El título no debe decir 2 imágenes", parsed.title.contains("2 imágenes"))
    }

    private class RecordingListener : SseStreamParser.SseEventListener {
        val deltas = StringBuilder()
        var finalContent: String = ""
        override fun onContentDelta(delta: String) { deltas.append(delta) }
        override fun onReasoningDelta(delta: String) {}
        override fun onComplete(fullContent: String, fullReasoning: String) { finalContent = fullContent }
        override fun onToolCallsReceived(toolCalls: List<com.codex.chat.core.parser.SseStreamParser.CompletedToolCall>) {}
        override fun onError(error: Throwable) { fail("No se esperaba error: " + error.message) }
    }
}