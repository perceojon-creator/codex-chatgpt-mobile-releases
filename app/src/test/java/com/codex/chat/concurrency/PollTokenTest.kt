package com.codex.chat.concurrency

import com.codex.chat.core.concurrency.PollEpochManager
import com.codex.chat.core.concurrency.PollToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PollTokenTest {

    @Test
    fun un_token_nace_sin_cancelar() {
        val token = PollToken()
        assertFalse(token.cancelado)
        assertFalse(token.isCancelled)
    }

    @Test
    fun cancelar_el_anterior_no_cancela_el_nuevo() {
        val viejo = PollToken()
        val nuevo = PollToken()
        viejo.cancelado = true
        assertTrue(viejo.cancelado)
        assertTrue(viejo.isCancelled)
        assertFalse("REGRESION: el nuevo poller nace cancelado", nuevo.cancelado)
        assertFalse(nuevo.isCancelled)
    }

    @Test
    fun el_bucle_del_poller_para_al_cancelar_su_token() {
        val token = PollToken()
        val vueltas = AtomicInteger(0)
        val hilo = Thread {
            while (!token.cancelado && vueltas.get() < 10_000) {
                vueltas.incrementAndGet()
                Thread.sleep(1)
            }
        }
        hilo.start()
        Thread.sleep(80)
        token.cancelado = true
        hilo.join(2000)
        assertFalse("El hilo debe haber terminado", hilo.isAlive)
        assertTrue(vueltas.get() < 10_000)
    }

    @Test
    fun simulacion_de_reinicio_rapido_deja_un_solo_poller_vivo() {
        val vivos = AtomicInteger(0)
        var tokenActivo: PollToken? = null

        repeat(5) {
            tokenActivo?.cancelado = true
            val t = PollToken()
            tokenActivo = t
            Thread {
                vivos.incrementAndGet()
                while (!t.cancelado) {
                    Thread.sleep(2)
                }
                vivos.decrementAndGet()
            }.start()
            Thread.sleep(30)
        }
        Thread.sleep(150)
        assertEquals("Solo puede quedar un poller vivo", 1, vivos.get())
        tokenActivo?.cancelado = true
        Thread.sleep(100)
        assertEquals(0, vivos.get())
    }

    @Test
    fun epoch_manager_cancela_el_anterior_al_crear_nuevo() {
        val manager = PollEpochManager()
        val token1 = manager.newToken()
        assertFalse(token1.isCancelled)
        assertEquals(1L, token1.epoch)
        assertTrue(manager.isCurrent(token1))

        val token2 = manager.newToken()
        assertTrue("El token anterior debe estar cancelado", token1.isCancelled)
        assertFalse("El nuevo token debe estar activo", token2.isCancelled)
        assertEquals(2L, token2.epoch)
        assertFalse(manager.isCurrent(token1))
        assertTrue(manager.isCurrent(token2))
    }

    @Test
    fun epoch_manager_cancelActive_cancela_el_activo() {
        val manager = PollEpochManager()
        val token = manager.newToken()
        assertFalse(token.isCancelled)
        assertTrue(manager.isCurrent(token))

        manager.cancelActive()
        assertTrue(token.isCancelled)
        assertFalse(manager.isCurrent(token))
        assertEquals(null, manager.current())
    }

    @Test
    fun epoch_manager_concurrencia_alta_deja_exactamente_uno_activo() {
        val manager = PollEpochManager()
        val threadCount = 10
        val tokensPerThread = 20
        val latch = CountDownLatch(threadCount)
        val generatedTokens = ConcurrentHashMap.newKeySet<PollToken>()

        for (i in 0 until threadCount) {
            Thread {
                try {
                    for (j in 0 until tokensPerThread) {
                        val token = manager.newToken()
                        generatedTokens.add(token)
                        Thread.sleep(1)
                    }
                } finally {
                    latch.countDown()
                }
            }.start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val current = manager.current()
        assertNotNull(current)
        assertTrue(manager.isCurrent(current))
        assertFalse(current!!.isCancelled)

        // All other generated tokens must be cancelled except the current one
        val uncancelledTokens = generatedTokens.filter { !it.isCancelled }
        assertEquals("Exactamente un token debe permanecer sin cancelar", 1, uncancelledTokens.size)
        assertEquals(current, uncancelledTokens.first())
    }
}
