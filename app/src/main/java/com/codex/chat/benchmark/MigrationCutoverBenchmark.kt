package com.codex.chat.benchmark

import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.storage.PartitionedChatStorage
import com.codex.chat.storage.StorageCutoverMigrator
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MigrationCutoverBenchmark(private val testDir: File) {

    fun execute(legacySessionCount: Int = 100): BenchmarkResult {
        val logs = mutableListOf<String>()
        val start = System.currentTimeMillis()

        // 1. Crear directorio limpio de prueba
        if (testDir.exists()) testDir.deleteRecursively()
        testDir.mkdirs()

        val legacyFile = File(testDir, "chatgpt_local_history.json")
        val legacyArray = JSONArray()

        for (i in 1..legacySessionCount) {
            val sessionObj = JSONObject().apply {
                put("id", "legacy-session-$i")
                put("title", "Conversación Histórica $i")
                put("timestamp", System.currentTimeMillis() - (legacySessionCount - i) * 1000L)
                val msgArr = JSONArray()
                msgArr.put(JSONObject().apply {
                    put("id", "legacy-m1-$i")
                    put("role", "user")
                    put("content", "Pregunta de prueba $i para migración")
                })
                msgArr.put(JSONObject().apply {
                    put("id", "legacy-m2-$i")
                    put("role", "assistant")
                    put("content", "Respuesta generada para sesión $i")
                })
                put("messages", msgArr)
            }
            legacyArray.put(sessionObj)
        }
        legacyFile.writeText(legacyArray.toString(2), Charsets.UTF_8)
        logs.add("Generadas $legacySessionCount sesiones legadas (${legacyFile.length() / 1024} KB)")

        val partitionedStorage = PartitionedChatStorage(testDir)

        // 2. Simular sesión creada en caliente durante el corte
        val hotSession = LocalChatSession(id = "legacy-session-1", title = "Sesión Activa Modificada")
        hotSession.addMessage(ChatMessage(id = "hot-m1", role = MessageRole.USER, content = "Mensaje Caliente"))
        partitionedStorage.saveSession(hotSession)

        // 3. Ejecutar migración de corte
        val migrator = StorageCutoverMigrator(testDir, partitionedStorage)
        val stats = migrator.migrateSync()
        val durationMs = System.currentTimeMillis() - start

        logs.add("Migración finalizada: ${stats.migratedCount} migradas, ${stats.skippedCount} omitidas en ${stats.durationMs} ms")

        // 4. Verificaciones de invariantes
        val isCutoverDone = migrator.isCutoverComplete()
        val bakExists = File(testDir, "chatgpt_local_history.json.bak").exists()
        val hotSessionPreserved = partitionedStorage.loadSession("legacy-session-1")?.getMessagesSnapshot()?.firstOrNull()?.content == "Mensaje Caliente"
        val index = partitionedStorage.loadSessionsIndex()

        val passed = isCutoverDone &&
                bakExists &&
                hotSessionPreserved &&
                (stats.migratedCount == legacySessionCount - 1) &&
                (stats.skippedCount == 1) &&
                (index.size == legacySessionCount)

        val sessionsPerSec = if (stats.durationMs > 0) (stats.migratedCount.toDouble() / stats.durationMs) * 1000.0 else 0.0

        logs.add("Throughput: ${String.format("%.1f", sessionsPerSec)} sesiones/seg")
        logs.add("Integridad preservada: $hotSessionPreserved, Archivo .bak: $bakExists")

        return BenchmarkResult(
            name = "STORAGE_CUTOVER_MIGRATION",
            passed = passed,
            durationMs = durationMs,
            metrics = mapOf(
                "migrated_count" to stats.migratedCount.toDouble(),
                "skipped_count" to stats.skippedCount.toDouble(),
                "sessions_per_sec" to sessionsPerSec,
                "cutover_done" to (if (isCutoverDone) 1.0 else 0.0)
            ),
            logLines = logs,
            errorMessage = if (!passed) "Invariantes de corte violados (cutoverDone=$isCutoverDone, bak=$bakExists, hotPreserved=$hotSessionPreserved)" else null
        )
    }
}
