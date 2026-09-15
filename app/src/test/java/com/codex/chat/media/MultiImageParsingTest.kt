package com.codex.chat.media

import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Test

class MultiImageParsingTest {

    @Test
    fun multi_imagen_se_extraen_todas_y_el_texto_queda_limpio() {
        // Simula una respuesta real del modelo con 2 imágenes generadas (caso del usuario)
        val content = "Aquí tienes las dos imágenes:\n\n" +
            "![imagen-generada](file:///data/user/0/com.codex.chat/files/generated_media/img_1789374737370_12e8e301.jpg)\n\n" +
            "Y la segunda variante:\n\n" +
            "![imagen-generada](file:///data/user/0/com.codex.chat/files/generated_media/img_1789374739847_184c44a6.jpg)\n\n" +
            "¿Cuál prefieres?"

        val t0 = System.nanoTime()
        val visual = VisualMediaParser.parse(content)
        val elapsedUs = (System.nanoTime() - t0) / 1000

        println("Parse multi-imagen latency: ${elapsedUs} µs")

        // TODAS las imágenes detectadas
        assertTrue(visual.hasMedia)
        assertEquals(VisualMediaType.IMAGE, visual.type)
        assertEquals(2, visual.imageSources.size)
        assertTrue(visual.imageSources[0].contains("img_1789374737370"))
        assertTrue(visual.imageSources[1].contains("img_1789374739847"))

        // El texto limpio NO debe contener ningún marcador markdown crudo
        assertFalse("El bubble NO debe mostrar el marcador file:// crudo", visual.cleanContent.contains("![imagen-generada]("))
        assertFalse("El bubble NO debe mostrar rutas file://", visual.cleanContent.contains("file://"))
        assertTrue("El bubble debe conservar el texto real", visual.cleanContent.contains("Aquí tienes"))
        assertTrue("El bubble debe conservar el texto real", visual.cleanContent.contains("¿Cuál prefieres?"))
        assertTrue("Debe indicar 2 imágenes en el título", visual.title.contains("2"))
    }

    @Test
    fun imagen_unica_mantiene_comportamiento_retrocompatible() {
        val content = "Mira: \n\n![foto](file:///storage/emulated/0/x.jpg)\n\nGracias"
        val visual = VisualMediaParser.parse(content)
        assertTrue(visual.hasMedia)
        assertEquals(1, visual.imageSources.size)
        assertEquals(visual.mediaSource, visual.imageSources[0])
        assertFalse(visual.cleanContent.contains("!["))
        assertTrue(visual.cleanContent.contains("Gracias"))
    }

    @Test
    fun tres_imagenes_en_stream_fragmentado() {
        val content = "![a](file:///a.jpg)![b](file:///b.jpg)![c](file:///c.jpg)"
        val visual = VisualMediaParser.parse(content)
        assertEquals(3, visual.imageSources.size)
        // Sin texto entre imágenes → cleanContent debe estar completamente vacío (sin placeholder sucio)
        assertFalse(visual.cleanContent.contains("!["))
        assertEquals("", visual.cleanContent)
    }

    @Test
    fun solo_imagenes_genera_clean_content_vacio_para_no_manchar_el_chat() {
        // Cuando el modelo solo responde con imágenes, NO debe aparecer ningún texto
        // como "Imagen(es) visualizada(s):" que manche la burbuja de chat.
        val content = "![imagen-generada](file:///data/user/0/com.codex.chat/files/generated_media/img_1.jpg)\n" +
            "![imagen-generada](file:///data/user/0/com.codex.chat/files/generated_media/img_2.jpg)"

        val visual = VisualMediaParser.parse(content)

        assertTrue(visual.hasMedia)
        assertEquals(2, visual.imageSources.size)
        // cleanContent DEBE ser vacío para que el ChatAdapter oculte tvAssistantContent completamente
        assertEquals("", visual.cleanContent)
        assertFalse("NO debe manchar el chat con texto de placeholder", visual.cleanContent.contains("visualizada"))
    }

    @Test
    fun archivos_con_mismo_tamano_en_disco_se_deduplican_automaticamente() {
        // Simula la sesión histórica donde se guardaron dos archivos distintos en disco
        // con UUIDs diferentes pero que contienen la misma imagen (e.g. ~800KB cada uno).
        val tmpDir = java.io.File(System.getProperty("java.io.tmpdir"), "dedup_test").apply { mkdirs() }
        val f1 = java.io.File(tmpDir, "img_1789374737370_12e8e301.jpg").apply { writeBytes(ByteArray(2048) { 42 }) }
        val f2 = java.io.File(tmpDir, "img_1789374739847_184c44a6.jpg").apply { writeBytes(ByteArray(2048) { 42 }) }

        val content = "![imagen-generada](file://${f1.absolutePath})\n![imagen-generada](file://${f2.absolutePath})"
        val visual = VisualMediaParser.parse(content)

        assertEquals("Debe detectar que ambos archivos son idénticos y mostrar solo 1 imagen", 1, visual.imageSources.size)
        assertEquals("file://${f1.absolutePath}", visual.imageSources[0])
        assertFalse("El título no debe decir 2 imágenes", visual.title.contains("2 imágenes"))

        f1.delete()
        f2.delete()
        tmpDir.delete()
    }
}
