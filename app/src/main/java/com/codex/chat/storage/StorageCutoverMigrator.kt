package com.codex.chat.storage

import android.util.Log
import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.concurrent.thread

data class MigrationStats(
    val migratedCount: Int,
    val skippedCount: Int,
    val durationMs: Long
)

class StorageCutoverMigrator(
    private val rootDir: File,
    private val partitionedStorage: PartitionedChatStorage
) {
    companion object {
        private const val TAG = "StorageCutover"
        private const val LEGACY_FILE_NAME = "chatgpt_local_history.json"
        private const val BAK_FILE_NAME = "chatgpt_local_history.json.bak"
        private const val SENTINEL_FILE_NAME = "storage_cutover_v1.done"
    }

    private val legacyFile = File(rootDir, LEGACY_FILE_NAME)
    private val bakFile = File(rootDir, BAK_FILE_NAME)
    private val sentinelFile = File(rootDir, SENTINEL_FILE_NAME)

    fun isCutoverComplete(): Boolean {
        return sentinelFile.exists()
    }

    fun migrateSync(): MigrationStats {
        val start = System.currentTimeMillis()
        if (isCutoverComplete() || !legacyFile.exists()) {
            return MigrationStats(0, 0, System.currentTimeMillis() - start)
        }

        var migrated = 0
        var skipped = 0

        try {
            val content = legacyFile.readText(Charsets.UTF_8)
            val jsonArray = JSONArray(content)

            for (i in 0 until jsonArray.length()) {
                val sessionObj = jsonArray.getJSONObject(i)
                val sessionId = sessionObj.optString("id", "")
                if (sessionId.isBlank()) continue

                // Invariante de seguridad: si la sesión ya existe en el nuevo almacenamiento particionado,
                // OMITIR la sobreescritura para proteger mensajes creados en caliente durante la migración.
                if (partitionedStorage.hasSession(sessionId)) {
                    skipped++
                    continue
                }

                val title = sessionObj.optString("title", "Conversación")
                val timestamp = sessionObj.optLong("timestamp", System.currentTimeMillis())
                val session = LocalChatSession(id = sessionId, title = title, timestamp = timestamp)

                val msgList = mutableListOf<ChatMessage>()
                val msgArr = sessionObj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgArr.length()) {
                    val m = msgArr.getJSONObject(j)
                    val role = when (m.optString("role")) {
                        "user" -> MessageRole.USER
                        "tool" -> MessageRole.TOOL
                        else -> MessageRole.ASSISTANT
                    }
                    val rawContent = m.optString("content", "")
                    val cleanContent = com.codex.chat.core.media.GeneratedMediaStorage.migrateDataUrlsInContent(rawContent)
                    msgList.add(
                        ChatMessage(
                            id = m.optString("id", java.util.UUID.randomUUID().toString()),
                            role = role,
                            content = cleanContent,
                            reasoningContent = m.optString("reasoning", ""),
                            toolCallId = m.optString("tool_call_id", ""),
                            toolName = m.optString("tool_name", ""),
                            toolCallsJson = m.optString("tool_calls", ""),
                            durationMs = m.optLong("duration_ms", 0L),
                            thinkingDurationMs = m.optLong("thinking_duration_ms", 0L),
                            generationDurationMs = m.optLong("generation_duration_ms", 0L),
                            completionTokens = m.optInt("completion_tokens", 0),
                            promptTokens = m.optInt("prompt_tokens", 0),
                            totalTokens = m.optInt("total_tokens", 0),
                            tokensPerSecond = m.optDouble("tokens_per_sec", 0.0)
                        )
                    )
                }
                session.setMessages(msgList)
                partitionedStorage.saveSession(session)
                migrated++
            }

            // Preservar copia de seguridad del legado
            try {
                Files.move(
                    legacyFile.toPath(),
                    bakFile.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                if (!legacyFile.renameTo(bakFile)) {
                    Log.w(TAG, "No se pudo renombrar legado a .bak vía atomic move, manteniendo original")
                }
            }

            // Marcar centinela de finalización
            sentinelFile.writeText("{\"migrated\": $migrated, \"skipped\": $skipped, \"timestamp\": ${System.currentTimeMillis()}}")

        } catch (e: Exception) {
            Log.e(TAG, "Error durante la migración de corte", e)
        }

        val duration = System.currentTimeMillis() - start
        return MigrationStats(migrated, skipped, duration)
    }

    fun migrateAsync(onComplete: ((MigrationStats) -> Unit)? = null) {
        thread(name = "storage-cutover-worker") {
            val stats = migrateSync()
            onComplete?.invoke(stats)
        }
    }
}
