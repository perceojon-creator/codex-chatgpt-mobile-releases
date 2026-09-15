package com.codex.chat.investigacion

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

class BenchLocalReload {
    @Test
    fun reload_coste_con_n_imagenes() {
        val base64 = "/9j/4AAQSkZJRgABAQ".repeat(15000)
        fun msgWithImage(n: Int) = JSONObject().put("role", "assistant")
            .put("content", "![imagen-generada](data:image/jpeg;base64,$base64)")
            .put("reasoning", "razonamiento corto $n")

        // Historial: 5 sesiones × 2 mensajes con imagen = 10 imágenes ≈ 8.5MB
        val big = JSONArray()
        for (s in 0 until 5) {
            val msgs = JSONArray().put(msgWithImage(s)).put(msgWithImage(s + 100))
            big.put(JSONObject().put("id", "s$s").put("title", "sesion $s").put("timestamp", 1_000_000L + s).put("messages", msgs))
        }
        val bigText = big.toString()
        println("BENCH tamaño historial 10 imágenes: ${bigText.length / 1024} KB")

        // (1) getAllSessions completo: readText + JSONArray(content)
        var t0 = System.nanoTime(); repeat(3) { JSONArray(bigText) };
        println("BENCH JSONArray-parse 10 imágenes: ${(System.nanoTime() - t0) / 1_000_000 / 3} ms/llamada")

        // (2) persistAll: array.toString(2)
        t0 = System.nanoTime(); repeat(3) { big.toString(2) };
        println("BENCH persistAll toString(2) 10 imágenes: ${(System.nanoTime() - t0) / 1_000_000 / 3} ms/llamada")

        // (3) DiffUtil areContentsTheSame: equals de contents 850KB
        val c1 = "![imagen-generada](data:image/jpeg;base64,$base64)"
        val c2 = String(c1.toCharArray())
        t0 = System.nanoTime(); repeat(10) { c1 == c2 };
        println("BENCH equals-850KB × 10: ${(System.nanoTime() - t0) / 1_000_000} ms total")

        // (4) JSON single (1 imagen)
        val one = JSONObject().put("id", "x").put("title", "t").put("timestamp", 1L)
            .put("messages", JSONArray().put(msgWithImage(1)))
        val oneText = one.toString()
        t0 = System.nanoTime(); repeat(5) { JSONObject(oneText) };
        println("BENCH JSONArray-parse 1 imagen: ${(System.nanoTime() - t0) / 1_000_000 / 5} ms/llamada")
    }
}