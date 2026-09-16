package com.codex.chat.benchmark

import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.storage.PartitionedChatStorage
import java.io.File

class KillAndCorruptionChaosBenchmark(private val testDir: File) {

    fun execute(rounds: Int = 100): BenchmarkResult {
        val logs = mutableListOf<String>()
        val start = System.currentTimeMillis()

        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()

        val storage = PartitionedChatStorage(testDir)
        val sessionsDir = File(testDir, "sessions")
        var zeroByteFilesDetected = 0

        for (i in 1..rounds) {
            val session = LocalChatSession(
                id = "chaos_session_$i",
                title = "Chaos Title $i",
                timestamp = System.currentTimeMillis()
            )
            for (m in 1..5) {
                session.addMessage(
                    ChatMessage(
                        id = "msg_${i}_$m",
                        role = if (m % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                        content = "Contenido de estrés de caos $m sesión $i",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            val t = Thread {
                storage.saveSession(session)
            }
            t.start()
            if (i % 2 == 0) {
                t.interrupt()
            }
            t.join(50)
        }

        val allFiles = sessionsDir.listFiles() ?: emptyArray()
        for (f in allFiles) {
            if (f.extension == "json" && f.length() == 0L) {
                zeroByteFilesDetected++
            }
        }

        val recovered = try {
            storage.loadSessionsIndex()
        } catch (e: Exception) {
            emptyList<com.codex.chat.storage.SessionIndexEntry>()
        }

        logs.add("Caos completado: $rounds rondas de interrupción abrupta")
        logs.add("Archivos con 0 bytes detectados: $zeroByteFilesDetected (debe ser 0)")
        logs.add("Sesiones válidas recuperadas en índice: ${recovered.size}")

        val duration = System.currentTimeMillis() - start
        val passed = (zeroByteFilesDetected == 0) && recovered.isNotEmpty()

        return BenchmarkResult(
            name = "CHAOS_KILL_AND_CORRUPTION",
            passed = passed,
            durationMs = duration,
            metrics = mapOf(
                "zero_byte_files" to zeroByteFilesDetected.toDouble(),
                "recovered_sessions" to recovered.size.toDouble(),
                "chaos_rounds" to rounds.toDouble()
            ),
            logLines = logs,
            errorMessage = if (!passed) "Fallo en prueba de caos (0-byte files=$zeroByteFilesDetected, recuperadas=${recovered.size})" else null
        )
    }
}
