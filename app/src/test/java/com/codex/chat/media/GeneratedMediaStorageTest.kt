package com.codex.chat.media

import com.codex.chat.core.media.GeneratedMediaStorage
import com.codex.chat.core.media.MediaBitmapCache
import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

class GeneratedMediaStorageTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "test_media_" + System.currentTimeMillis())
        tempDir.mkdirs()
        GeneratedMediaStorage.initForTesting(tempDir)
    }

    @Test
    fun test_save_base64_and_parse() {
        // Base64 sintético de 100KB
        val b64 = "/9j/4AAQSkZJRgABAQ".repeat(6000)
        val dataUrl = "data:image/jpeg;base64,$b64"

        // 1. Guardar a archivo local
        val fileUri = GeneratedMediaStorage.saveBase64Image(dataUrl)
        assertTrue(fileUri.startsWith("file://"))
        val localPath = fileUri.substring(7)
        val file = File(localPath)
        assertTrue(file.exists())
        assertTrue(file.length() > 0)

        // 2. Comprobar que el marcador markdown con file:// es ligero y rápido
        val marker = "\n\n![imagen-generada]($fileUri)\n\n"
        assertTrue(marker.length < 200)

        val t0 = System.nanoTime()
        val visual = VisualMediaParser.parse(marker)
        val elapsedUs = (System.nanoTime() - t0) / 1000

        assertTrue(visual.hasMedia)
        assertEquals(VisualMediaType.IMAGE, visual.type)
        assertEquals(fileUri, visual.mediaSource)
        println("Parse file:// marker latency: ${elapsedUs} µs (microseconds!)")

        // 3. Comprobar clave de caché estable para URI de archivo
        val cacheKey = MediaBitmapCache.keyFor(fileUri)
        assertEquals(fileUri, cacheKey)

        // 4. Probar migración automática de contenido con data:image/ antiguo
        val oldContent = "Aquí está tu foto:\n\n![foto]($dataUrl)\n\nDisfrútala."
        val migrated = GeneratedMediaStorage.migrateDataUrlsInContent(oldContent)
        assertFalse(migrated.contains("data:image/"))
        assertTrue(migrated.contains("file://"))
        assertTrue(migrated.length < 300)
    }
}
