package com.codex.chat.core.media

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.LruCache
import java.io.File

/**
 * Caché global de bitmaps decodificados desde archivos locales o data-URLs.
 *
 * Soporta tanto URIs locales "file:///..." (modo óptimo y ligero sin retener base64)
 * como data-URLs "data:image/..." (fallback retrocompatible).
 *
 * La decodificación se ejecuta fuera del hilo UI con submuestreo de memoria seguro
 * para evitar bloqueos del render thread y OOMs en el RecyclerView.
 */
object MediaBitmapCache {
    private val maxMemory = (Runtime.getRuntime().maxMemory() / 1024).toInt()
    private val cacheSize = (maxMemory / 4).coerceAtLeast(16 * 1024)

    private val lru = object : LruCache<String, Bitmap>(cacheSize) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    /** Obtiene el bitmap cacheado, o null si aún no está decodificado. */
    fun get(cacheKey: String): Bitmap? = lru.get(cacheKey)

    /**
     * Decodifica en background y entrega al callback en el hilo principal.
     */
    fun decodeAsync(cacheKey: String, source: String, onReady: (Bitmap?) -> Unit) {
        lru.get(cacheKey)?.let { cached ->
            onReady(cached)
            return
        }
        Thread {
            val bmp = decodeBlocking(source)
            if (bmp != null) lru.put(cacheKey, bmp)
            mainHandler.post { onReady(bmp) }
        }.start()
    }

    /**
     * Decodificación síncrona con submuestra controlada (máx 1600px en lado mayor).
     */
    fun decodeBlocking(source: String, maxDim: Int = 1600): Bitmap? {
        return try {
            val trimmed = source.trim()
            when {
                trimmed.startsWith("file://") -> {
                    val path = trimmed.substring(7)
                    decodeFile(path, maxDim)
                }
                trimmed.startsWith("/") -> {
                    decodeFile(trimmed, maxDim)
                }
                trimmed.startsWith("data:image/") -> {
                    val comma = trimmed.indexOf(',')
                    if (comma == -1) return null
                    val bytes = Base64.decode(trimmed.substring(comma + 1), Base64.DEFAULT)
                    decodeByteArray(bytes, maxDim)
                }
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun decodeFile(path: String, maxDim: Int): Bitmap? {
        val file = File(path)
        if (!file.exists() || file.length() == 0L) return null

        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxDim || opts.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeFile(path, decodeOpts)
    }

    private fun decodeByteArray(bytes: ByteArray, maxDim: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

        var sample = 1
        while (opts.outWidth / (sample * 2) >= maxDim || opts.outHeight / (sample * 2) >= maxDim) {
            sample *= 2
        }

        val decodeOpts = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, decodeOpts)
    }

    /** Clave de caché estable: si es ruta local, usa la ruta; si es data-URL, calcula hash. */
    fun keyFor(source: String): String {
        val trimmed = source.trim()
        if (trimmed.startsWith("file://") || trimmed.startsWith("/")) {
            return trimmed
        }
        var h = 1125899906842597L
        val step = if (trimmed.length > 40000) 97 else 1
        var i = 0
        while (i < trimmed.length) {
            h = 31 * h + trimmed[i].code
            i += step
        }
        return "img_" + (h and Long.MAX_VALUE).toString(36)
    }
}
