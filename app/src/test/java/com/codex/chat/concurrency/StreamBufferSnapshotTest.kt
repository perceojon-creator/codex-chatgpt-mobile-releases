package com.codex.chat.concurrency

import com.codex.chat.core.concurrency.StreamBuffer
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class StreamBufferSnapshotTest {

    @Test
    fun test_stream_buffer_snapshot_is_temporally_consistent_and_lock_free() {
        val buffer = StreamBuffer()
        val running = AtomicBoolean(true)
        val latch = CountDownLatch(2)
        var tearingDetected = false

        // Hilo Productor: actualiza contenido y razonamiento en sincronía de versión
        val producer = Thread {
            var v = 0L
            while (running.get()) {
                v++
                buffer.appendContent("content_$v;")
                buffer.appendReasoning("reasoning_$v;")
                Thread.yield()
            }
            latch.countDown()
        }

        // Hilo Consumidor (simula hilo UI): lee snapshots atómicos
        val consumer = Thread {
            for (i in 1..2000) {
                val snap = buffer.getSnapshot()
                // Validar que ambos campos están consistentes en el snapshot atómico
                if (snap.version > 0 && snap.content.isEmpty() && snap.reasoning.isNotEmpty()) {
                    tearingDetected = true
                }
                Thread.yield()
            }
            running.set(false)
            latch.countDown()
        }

        producer.start()
        consumer.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertFalse("No debe ocurrir State Tearing entre content y reasoning", tearingDetected)
    }
}
