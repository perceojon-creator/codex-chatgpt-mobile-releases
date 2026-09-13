package com.codex.chat.concurrency

import com.codex.chat.core.concurrency.StreamBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Demuestra el bug de concurrencia en StringBuilder compartido y valida
 * la proteccion multihilo implementada en StreamBuffer y confinamiento de hilos.
 */
class StreamBufferTest {

    @Test
    fun escribir_un_StringBuilder_desde_dos_hilos_corrompe() {
        // Documenta POR QUE hace falta la sincronizacion en StringBuilder.
        // Se sincroniza el inicio de ambos hilos con CountDownLatch para forzar
        // contencion real y verificar que StringBuilder sufre perdidas o corrupcion.
        var corrupciones = 0
        val iteracionesPorHilo = 3000
        val esperado = iteracionesPorHilo * 2

        for (r in 0 until 50) {
            val startLatch = CountDownLatch(1)
            val sb = StringBuilder()
            var excepcionLanzada = false

            val a = Thread {
                try {
                    startLatch.await()
                    repeat(iteracionesPorHilo) { sb.append("A") }
                } catch (e: Throwable) {
                    excepcionLanzada = true
                }
            }
            val b = Thread {
                try {
                    startLatch.await()
                    repeat(iteracionesPorHilo) { sb.append("B") }
                } catch (e: Throwable) {
                    excepcionLanzada = true
                }
            }

            a.start()
            b.start()
            startLatch.countDown()
            a.join()
            b.join()

            if (excepcionLanzada || sb.length != esperado) {
                corrupciones++
            }
        }
        assertTrue(
            "Se esperaba observar corrupcion sin sincronizacion (corrupciones: $corrupciones)",
            corrupciones > 0
        )
    }

    @Test
    fun confinar_las_escrituras_a_un_solo_hilo_preserva_todo() {
        val sb = StringBuilder()
        val cola = LinkedBlockingQueue<String>()
        repeat(500) {
            cola.put("A")
            cola.put("B")
        }

        val consumidor = Thread {
            repeat(1000) {
                sb.append(cola.take())
            }
        }
        consumidor.start()
        consumidor.join(5000)

        assertEquals("Con un solo escritor no se pierde nada", 1000, sb.length)
        assertEquals(500, sb.count { it == 'A' })
        assertEquals(500, sb.count { it == 'B' })
    }

    @Test
    fun streamBuffer_append_concurrente_multiples_hilos_sin_perdida_ni_corrupcion() {
        val buffer = StreamBuffer()
        val threadCount = 10
        val operationsPerThread = 200
        val latch = CountDownLatch(threadCount * 2)

        // 10 threads writing to content
        for (t in 0 until threadCount) {
            Thread {
                try {
                    for (i in 0 until operationsPerThread) {
                        buffer.appendContent("C")
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        // 10 threads writing to reasoning simultaneously
        for (t in 0 until threadCount) {
            Thread {
                try {
                    for (i in 0 until operationsPerThread) {
                        buffer.appendReasoning("R")
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue("Todos los hilos deben terminar", latch.await(10, TimeUnit.SECONDS))

        val expectedChars = threadCount * operationsPerThread
        assertEquals("Longitud exacta en contentBuffer sin perdida", expectedChars, buffer.contentLength)
        assertEquals("Longitud exacta en reasoningBuffer sin perdida", expectedChars, buffer.reasoningLength)
        assertEquals("Contenido compuesto exclusivamente por caracteres C", expectedChars, buffer.getContent().count { it == 'C' })
        assertEquals("Razonamiento compuesto exclusivamente por caracteres R", expectedChars, buffer.getReasoning().count { it == 'R' })
    }

    @Test
    fun streamBuffer_separacion_de_content_y_reasoning() {
        val buffer = StreamBuffer()
        buffer.appendContent("Texto final del asistente")
        buffer.appendReasoning("Pensamiento interno del modelo")

        assertEquals("Texto final del asistente", buffer.getContent())
        assertEquals("Pensamiento interno del modelo", buffer.getReasoning())
        assertFalse(buffer.isContentEmpty)
        assertFalse(buffer.isReasoningEmpty)
        assertFalse(buffer.isEmpty)
        assertEquals("Texto final del asistente", buffer.toString())
    }

    @Test
    fun streamBuffer_snapshot_es_consistente_e_inmutable() {
        val buffer = StreamBuffer(initialContent = "Inicio", initialReasoning = "Base")
        val snap1 = buffer.snapshot()

        assertEquals("Inicio", snap1.content)
        assertEquals("Base", snap1.reasoning)

        buffer.appendContent(" Modificado")
        buffer.appendReasoning(" Modificado")

        // Snapshot original remains unchanged
        assertEquals("Inicio", snap1.content)
        assertEquals("Base", snap1.reasoning)

        val snap2 = buffer.snapshot()
        assertEquals("Inicio Modificado", snap2.content)
        assertEquals("Base Modificado", snap2.reasoning)
    }

    @Test
    fun streamBuffer_clear_resetea_correctamente() {
        val buffer = StreamBuffer()
        buffer.appendContent("Mensaje")
        buffer.appendReasoning("Razonamiento")

        buffer.clearContent()
        assertTrue(buffer.isContentEmpty)
        assertFalse(buffer.isReasoningEmpty)
        assertEquals("", buffer.getContent())
        assertEquals("Razonamiento", buffer.getReasoning())

        buffer.clear()
        assertTrue(buffer.isEmpty)
        assertTrue(buffer.isContentEmpty)
        assertTrue(buffer.isReasoningEmpty)
        assertEquals(0, buffer.contentLength)
        assertEquals(0, buffer.reasoningLength)
    }

    @Test
    fun streamBuffer_setters_reemplazan_contenido() {
        val buffer = StreamBuffer()
        buffer.appendContent("Antiguo contenido")
        buffer.setContent("Nuevo contenido")
        assertEquals("Nuevo contenido", buffer.getContent())

        buffer.appendReasoning("Antiguo razonamiento")
        buffer.setReasoning("Nuevo razonamiento")
        assertEquals("Nuevo razonamiento", buffer.getReasoning())
    }
}
