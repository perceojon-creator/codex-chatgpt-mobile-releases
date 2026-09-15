package com.codex.chat.core.network

import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Traductor de razonamiento en tiempo real.
 *
 * Cuando el modelo piensa en su cadena de razonamiento en un idioma distinto al español,
 * este componente lo traduce AL VUELTA (streaming) para que el usuario SIEMPRE lea el
 * pensamiento en español, frase a frase, con la mínima latencia perceptible.
 *
 * Estrategia profesional de baja latencia:
 *  1. Segmentación por frases (no por tokens): se traduce SOLO texto con sentido completo.
 *  2. Detección barata de español: si la frase ya es español, pasa directa (0 ms, 0 coste).
 *  3. Lotes encadenados: mientras llega la frase N+1, ya se está pidiendo la N.
 *  4. Orden garantizado: los resultados se reinyectan en el orden exacto de llegada.
 */
class ReasoningTranslator(
    private val client: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String,
    private val translationModel: String = "gemini-3.5-flash-lite",
    private val onTranslated: (spanishText: String) -> Unit
) {

    private val pendingSegments = ConcurrentLinkedQueue<String>()
    private val working = AtomicBoolean(false)
    private val partialBuffer = StringBuilder()
    private val lock = Any()

    companion object {
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        /** Cliente dedicado de baja latencia: el razonamiento traducido debe sentirse instantáneo. */
        fun fastClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
        private val ES_STOPWORDS = setOf(
            "el", "la", "los", "las", "un", "una", "y", "o", "de", "que", "en", "a",
            "es", "por", "con", "para", "como", "pero", "más", "muy", "su", "sus", "se",
            "no", "si", "cuando", "este", "esta", "eso", "esa", "del", "al", "lo",
            "sin", "son", "fue", "hace", "sobre", "entre", "hay", "ya", "me", "te", "le"
        )
        // Frases frontera típicas del razonamiento de modelos: cortas y estables entre proveedores
        private val PHRASE_MARKERS = listOf("Okay", "Okay,", "Let me", "I need", "I'll", "I will", "First", "Now", "So", "Wait")
    }

    /** Recibe un delta del razonamiento. Emite (en orden) las frases ya traducidas al español. */
    fun onDelta(delta: String) {
        if (delta.isEmpty()) return
        synchronized(lock) {
            partialBuffer.append(delta)
            // Extraer frases completas: terminan en . ! ? o salto de línea (o marcadores de frase)
            while (true) {
                val buf = partialBuffer.toString()
                val cut = findSentenceEnd(buf) ?: break
                val sentence = buf.substring(0, cut).trim()
                partialBuffer.setLength(0)
                partialBuffer.append(buf.substring(cut))
                if (sentence.isNotEmpty()) pendingSegments.add(sentence)
            }
        }
        drainAsync()
    }

    /** Al cerrar el stream: traduce el remanente parcial. */
    fun flush() {
        synchronized(lock) {
            val rest = partialBuffer.toString().trim()
            partialBuffer.setLength(0)
            if (rest.isNotEmpty()) pendingSegments.add(rest)
        }
        drainAsync()
    }

    private fun findSentenceEnd(buf: String): Int? {
        var best: Int? = null
        for (i in buf.indices) {
            val c = buf[i]
            if (c == '.' || c == '!' || c == '?' || c == '\n') {
                // Evitar partir decimales (3.14) o abreviaturas simples: exigir longitud mínima de frase
                if (i >= 8) return i + 1
            }
        }
        return best
    }

    /** Heurística: ¿es esta frase ya español? Ratio de stopwords castellanos. */
    private fun isSpanish(text: String): Boolean {
        val words = text.lowercase().split(Regex("[^a-záéíóúñü]+")).filter { it.length > 1 }
        if (words.isEmpty()) return true
        val hits = words.count { it in ES_STOPWORDS }
        return hits >= 2 || (words.size <= 3 && hits >= 1)
    }

    private fun drainAsync() {
        if (!working.compareAndSet(false, true)) return
        thread(name = "reasoning-translate") {
            try {
                while (true) {
                    val seg = pendingSegments.poll() ?: break
                    // Degradación elegante por segmento: si la traducción falla (proxy caído,
                    // timeout, sin red), ese fragmento se muestra en el idioma original — nunca se pierde.
                    val translated = try {
                        if (isSpanish(seg)) seg else translateBlocking(seg)
                    } catch (e: Exception) {
                        Log.e("ReasoningTranslator", "Fallo traduciendo segmento, se muestra original", e)
                        seg
                    }
                    if (translated.isNotBlank()) onTranslated(translated)
                }
            } finally {
                working.set(false)
                // Si llegaron frases mientras trabajábamos, reintentar el drenaje
                if (pendingSegments.isNotEmpty()) drainAsync()
            }
        }
    }

    private fun translateBlocking(text: String): String {
        val payload = JSONObject()
            .put("model", translationModel)
            .put("stream", false)
            .put("temperature", 0.1)
            .put("messages", JSONArray()
                .put(JSONObject()
                    .put("role", "system")
                    .put("content", "Eres un traductor automático. Traduce el texto del usuario al español nativo. " +
                        "Devuelve EXCLUSIVAMENTE la traducción, sin explicaciones, sin comillas, sin prefijos. " +
                        "Conserva el tono de razonamiento interno."))
                .put(JSONObject()
                    .put("role", "user")
                    .put("content", text))
            )

        val req = Request.Builder()
            .url(baseUrl.trimEnd('/') + "/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()

        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return text
            val body = resp.body?.string() ?: return text
            val json = JSONObject(body)
            val choice = json.optJSONArray("choices")?.optJSONObject(0) ?: return text
            return choice.optJSONObject("message")?.optString("content")?.trim() ?: text
        }
    }
}