package com.codex.chat.concurrency

import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class SessionConcurrencyRegressionTest {

    @Test
    fun test_concurrent_mutations_and_snapshot_reads_never_throw() {
        val session = LocalChatSession(title = "Concurrencia")
        val iterations = 1000
        val latch = CountDownLatch(2)
        val readCount = AtomicInteger(0)
        val writeCount = AtomicInteger(0)
        var thrownException: Throwable? = null

        // Hilo Escritor (Simula el hilo de UI / streaming añadiendo mensajes)
        val writer = Thread {
            try {
                for (i in 1..iterations) {
                    session.addMessage(ChatMessage(id = "msg-$i", role = MessageRole.USER, content = "Msg $i"))
                    writeCount.incrementAndGet()
                    Thread.yield()
                }
            } catch (t: Throwable) {
                thrownException = t
            } finally {
                latch.countDown()
            }
        }

        // Hilo Lector (Simula el repositorio serializando para disco)
        val reader = Thread {
            try {
                for (i in 1..iterations) {
                    val snapshot = session.getMessagesSnapshot()
                    readCount.incrementAndGet()
                    var length = 0
                    for (m in snapshot) {
                        length += m.content.length
                    }
                    Thread.yield()
                }
            } catch (t: Throwable) {
                thrownException = t
            } finally {
                latch.countDown()
            }
        }

        writer.start()
        reader.start()
        assertTrue("Los hilos deben completar en menos de 5 segundos", latch.await(5, TimeUnit.SECONDS))
        assertNull("No debe arrojarse ConcurrentModificationException ni ninguna excepción", thrownException)
        assertEquals(iterations, writeCount.get())
        assertEquals(iterations, readCount.get())
    }
}
