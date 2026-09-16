package com.codex.chat.core.concurrency

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * Thread-safe accumulator for reasoning and content chunks.
 *
 * Prevents interleaving, partial character drops, and concurrent modification exceptions
 * when streaming deltas and MCP tool execution results arrive simultaneously from
 * background worker threads and the main thread.
 */
class StreamBuffer(
    initialContent: String = "",
    initialReasoning: String = ""
) {
    private val lock = ReentrantReadWriteLock()
    private val contentBuffer = StringBuilder(initialContent)
    private val reasoningBuffer = StringBuilder(initialReasoning)
    private var version: Long = 0L

    data class Snapshot(
        val content: String,
        val reasoning: String
    )

    data class StreamSnapshot(
        val content: String,
        val reasoning: String,
        val version: Long
    )

    fun appendContent(chunk: String): StreamBuffer {
        if (chunk.isEmpty()) return this
        lock.write {
            contentBuffer.append(chunk)
            version++
        }
        return this
    }

    fun appendReasoning(chunk: String): StreamBuffer {
        if (chunk.isEmpty()) return this
        lock.write {
            reasoningBuffer.append(chunk)
            version++
        }
        return this
    }

    /** Appends content chunk (convenience overload). */
    fun append(chunk: String): StreamBuffer = appendContent(chunk)

    fun setContent(content: String): StreamBuffer {
        lock.write {
            contentBuffer.setLength(0)
            contentBuffer.append(content)
        }
        return this
    }

    fun setReasoning(reasoning: String): StreamBuffer {
        lock.write {
            reasoningBuffer.setLength(0)
            reasoningBuffer.append(reasoning)
        }
        return this
    }

    fun getContent(): String = lock.read {
        contentBuffer.toString()
    }

    fun getReasoning(): String = lock.read {
        reasoningBuffer.toString()
    }

    val contentLength: Int
        get() = lock.read { contentBuffer.length }

    val reasoningLength: Int
        get() = lock.read { reasoningBuffer.length }

    val isContentEmpty: Boolean
        get() = lock.read { contentBuffer.isEmpty() }

    val isReasoningEmpty: Boolean
        get() = lock.read { reasoningBuffer.isEmpty() }

    val isEmpty: Boolean
        get() = lock.read { contentBuffer.isEmpty() && reasoningBuffer.isEmpty() }

    val length: Int
        get() = contentLength

    fun snapshot(): Snapshot = lock.read {
        Snapshot(
            content = contentBuffer.toString(),
            reasoning = reasoningBuffer.toString()
        )
    }

    /** Retorna un snapshot atómico consistente temporalmente de content, reasoning y version (fixes C5) */
    fun getSnapshot(): StreamSnapshot = lock.read {
        StreamSnapshot(
            content = contentBuffer.toString(),
            reasoning = reasoningBuffer.toString(),
            version = version
        )
    }

    fun clearContent(): StreamBuffer {
        lock.write {
            contentBuffer.setLength(0)
        }
        return this
    }

    fun clearReasoning(): StreamBuffer {
        lock.write {
            reasoningBuffer.setLength(0)
        }
        return this
    }

    fun clear(): StreamBuffer {
        lock.write {
            contentBuffer.setLength(0)
            reasoningBuffer.setLength(0)
        }
        return this
    }

    override fun toString(): String = getContent()
}
