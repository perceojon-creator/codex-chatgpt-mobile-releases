package com.codex.chat.benchmark

import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class DataIntegrityBenchmark(private val testDir: File) {

    fun execute(writeIterations: Int = 500): BenchmarkResult {
        val logs = mutableListOf<String>()
        val start = System.currentTimeMillis()
        val errorCount = AtomicInteger(0)
        val completedWrites = AtomicInteger(0)
        val latencies = mutableListOf<Long>()

        val session = LocalChatSession(title = "Stress-Integrity-Session")
        val latch = CountDownLatch(2)

        // Hilo 1: Mutación rápida concurrente con addMessage
        val writer = Thread {
            try {
                for (i in 1..writeIterations) {
                    session.addMessage(
                        ChatMessage(
                            id = "bench-msg-$i",
                            role = MessageRole.USER,
                            content = "Benchmark test line content for iteration $i"
                        )
                    )
                    completedWrites.incrementAndGet()
                    Thread.yield()
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                logs.add("Writer error: ${t.message}")
            } finally {
                latch.countDown()
            }
        }

        // Hilo 2: Lecturas atómicas de snapshot y escrituras flash con fsync
        val flusher = Thread {
            try {
                var cycle = 0
                while (completedWrites.get() < writeIterations) {
                    cycle++
                    val t0 = System.nanoTime()
                    val snapshot = session.getMessagesSnapshot()
                    val targetFile = File(testDir, "integrity_session.json")
                    val tmpFile = File(testDir, "integrity_session.json.tmp")

                    val sb = java.lang.StringBuilder()
                    sb.append("{\"count\":").append(snapshot.size).append(",\"messages\":[")
                    for (idx in snapshot.indices) {
                        if (idx > 0) sb.append(",")
                        sb.append("{\"id\":\"").append(snapshot[idx].id).append("\"}")
                    }
                    sb.append("]}")
                    val bytes = sb.toString().toByteArray(Charsets.UTF_8)

                    FileOutputStream(tmpFile).use { fos ->
                        fos.write(bytes)
                        fos.flush()
                        fos.fd.sync()
                    }

                    try {
                        Files.move(
                            tmpFile.toPath(),
                            targetFile.toPath(),
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING
                        )
                    } catch (e: Exception) {
                        if (!tmpFile.renameTo(targetFile)) {
                            targetFile.delete()
                            tmpFile.renameTo(targetFile)
                        }
                    }

                    val elapsedNanos = System.nanoTime() - t0
                    synchronized(latencies) {
                        latencies.add(elapsedNanos / 1_000_000)
                    }
                    Thread.sleep(2)
                }
            } catch (t: Throwable) {
                errorCount.incrementAndGet()
                logs.add("Flusher error: ${t.message}")
            } finally {
                latch.countDown()
            }
        }

        writer.start()
        flusher.start()
        val finishedInTime = latch.await(10, TimeUnit.SECONDS)

        val totalDurationMs = System.currentTimeMillis() - start
        var zeroByteFiles = 0
        var totalFilesInspected = 0

        testDir.listFiles()?.forEach { file ->
            totalFilesInspected++
            if (file.isFile && file.length() == 0L) {
                zeroByteFiles++
            }
        }

        val passed = finishedInTime && (errorCount.get() == 0) && (zeroByteFiles == 0) && (session.messageCount == writeIterations)
        val p95Ms = synchronized(latencies) {
            if (latencies.isEmpty()) 0.0
            else {
                latencies.sort()
                latencies[(latencies.size * 0.95).toInt().coerceAtMost(latencies.size - 1)].toDouble()
            }
        }

        logs.add("Completed writes: ${completedWrites.get()}/$writeIterations")
        logs.add("Zero byte files: $zeroByteFiles")
        logs.add("P95 write sync latency: ${p95Ms} ms")

        return BenchmarkResult(
            name = "DATA_INTEGRITY_STRESS",
            passed = passed,
            durationMs = totalDurationMs,
            metrics = mapOf(
                "write_count" to completedWrites.get().toDouble(),
                "error_count" to errorCount.get().toDouble(),
                "zero_byte_files" to zeroByteFiles.toDouble(),
                "p95_sync_ms" to p95Ms
            ),
            logLines = logs,
            errorMessage = if (!passed) "Falló validación de integridad (errores=${errorCount.get()}, 0-byte=$zeroByteFiles, timeout=${!finishedInTime})" else null
        )
    }
}
