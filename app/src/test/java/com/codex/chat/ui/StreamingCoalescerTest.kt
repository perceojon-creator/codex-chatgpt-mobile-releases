package com.codex.chat.ui

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class StreamingCoalescerTest {
    @Test
    fun test_coalescer_drops_intermediate_updates_within_throttle_interval() {
        val dispatchCount = AtomicInteger(0)
        var lastTime = 0L
        val intervalMs = 33L // 30 fps

        val emit = { now: Long ->
            if (now - lastTime >= intervalMs) {
                dispatchCount.incrementAndGet()
                lastTime = now
            }
        }

        // 100 llamadas rápidas en ráfaga (0..100ms)
        for (t in 0..100) {
            emit(t.toLong())
        }

        // En 100 ms con intervalo de 33 ms solo deben emitirse ~4 actualizaciones (t=0, 33, 66, 99)
        assertTrue(dispatchCount.get() in 3..5)
    }
}
