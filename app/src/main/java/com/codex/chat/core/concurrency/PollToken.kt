package com.codex.chat.core.concurrency

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Thread-safe token / epoch manager to cancel previous active pollers when new sessions
 * or modes switch.
 *
 * Each background poller or streaming task captures its own instance of [PollToken].
 * When a new poll or session is started, cancelling the previous token immediately halts
 * the legacy loop without interfering with the newly spawned poller.
 */
class PollToken(
    val epoch: Long = 0L
) {
    @Volatile
    var cancelado: Boolean = false

    val isCancelled: Boolean
        get() = cancelado

    fun cancel() {
        cancelado = true
    }
}

/**
 * Thread-safe manager ensuring only one poller remains active at any given time.
 * Automatically invalidates and cancels obsolete pollers when a new epoch begins.
 */
class PollEpochManager {
    private val activeToken = AtomicReference<PollToken?>(null)
    private val epochSequence = AtomicLong(0L)

    /**
     * Atomically cancels the previously active token (if any) and issues a new active [PollToken].
     */
    fun newToken(): PollToken {
        val nextEpoch = epochSequence.incrementAndGet()
        val token = PollToken(nextEpoch)
        val previous = activeToken.getAndSet(token)
        previous?.cancel()
        return token
    }

    /**
     * Cancels the currently active token and resets the reference.
     */
    fun cancelActive() {
        val current = activeToken.getAndSet(null)
        current?.cancel()
    }

    /**
     * Verifies if the supplied token matches the current epoch and is still active.
     */
    fun isCurrent(token: PollToken?): Boolean {
        if (token == null || token.cancelado) return false
        return activeToken.get() === token
    }

    /**
     * Returns the currently active token, if any.
     */
    fun current(): PollToken? = activeToken.get()

    /**
     * Returns the current epoch counter value.
     */
    fun currentEpoch(): Long = epochSequence.get()
}
