package com.codex.chat.storage

import android.util.Log
import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

class PartitionedChatStorage(private val rootDir: File) {

    private val sessionsDir = File(rootDir, "sessions").apply { if (!exists()) mkdirs() }
    private val indexFile = File(rootDir, "sessions_index.json")
    private val indexLock = Any()

    fun getSessionFile(id: String): File = File(sessionsDir, "$id.json")

    fun hasSession(id: String): Boolean = getSessionFile(id).exists()

    fun saveSession(session: LocalChatSession) {
        val targetFile = getSessionFile(session.id)
        val tempFile = File(sessionsDir, "${session.id}.json.tmp")

        // 1. Serialización atómica de mensajes de la sesión exclusiva
        val snapshot = session.getMessagesSnapshot()
        val sessionObj = JSONObject().apply {
            put("id", session.id)
            put("title", session.title)
            put("timestamp", session.timestamp)
            val msgArr = JSONArray()
            for (m in snapshot) {
                val mObj = JSONObject().apply {
                    put("id", m.id)
                    put("role", when (m.role) {
                        MessageRole.USER -> "user"
                        MessageRole.TOOL -> "tool"
                        else -> "assistant"
                    })
                    put("content", m.content)
                    put("reasoning", m.reasoningContent ?: "")
                    if (m.toolCallId.isNotBlank()) put("tool_call_id", m.toolCallId)
                    if (m.toolName.isNotBlank()) put("tool_name", m.toolName)
                    if (m.toolCallsJson.isNotBlank()) put("tool_calls", m.toolCallsJson)
                    if (m.durationMs > 0L) put("duration_ms", m.durationMs)
                    if (m.thinkingDurationMs > 0L) put("thinking_duration_ms", m.thinkingDurationMs)
                    if (m.generationDurationMs > 0L) put("generation_duration_ms", m.generationDurationMs)
                    if (m.completionTokens > 0) put("completion_tokens", m.completionTokens)
                    if (m.promptTokens > 0) put("prompt_tokens", m.promptTokens)
                    if (m.totalTokens > 0) put("total_tokens", m.totalTokens)
                    if (m.tokensPerSecond > 0.0) put("tokens_per_sec", m.tokensPerSecond)
                }
                msgArr.put(mObj)
            }
            put("messages", msgArr)
        }

        val bytes = sessionObj.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tempFile).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync() // DURABILIDAD FÍSICA ESTRICTA
        }

        try {
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (!tempFile.renameTo(targetFile)) {
                Log.e("PartitionedStorage", "CRITICAL: Fallo atomic rename de ${tempFile.absolutePath} a ${targetFile.absolutePath}", e)
                throw IOException("Atomic move failed for session ${session.id}", e)
            }
        }

        // 2. Actualizar índice de sesiones
        updateIndexEntry(
            SessionIndexEntry(
                id = session.id,
                title = session.title,
                timestamp = session.timestamp,
                messageCount = snapshot.size,
                lastModified = System.currentTimeMillis()
            )
        )
    }

    fun loadSession(id: String): LocalChatSession? {
        val file = getSessionFile(id)
        if (!file.exists()) return null

        return try {
            val content = file.readText(Charsets.UTF_8)
            val obj = JSONObject(content)
            val sessionId = obj.optString("id", id)
            val title = obj.optString("title", "Conversación")
            val timestamp = obj.optLong("timestamp", System.currentTimeMillis())

            val msgList = mutableListOf<ChatMessage>()
            val msgArr = obj.optJSONArray("messages") ?: JSONArray()
            for (i in 0 until msgArr.length()) {
                val m = msgArr.getJSONObject(i)
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
            val session = LocalChatSession(id = sessionId, title = title, timestamp = timestamp)
            session.setMessages(msgList)
            session
        } catch (e: Exception) {
            Log.e("PartitionedStorage", "Error cargando sesión particionada $id", e)
            null
        }
    }

    fun deleteSession(id: String) {
        val file = getSessionFile(id)
        if (file.exists()) {
            file.delete()
        }
        synchronized(indexLock) {
            val entries = loadSessionsIndexInternal().toMutableList()
            entries.removeAll { it.id == id }
            writeIndexInternal(entries)
        }
    }

    fun loadSessionsIndex(): List<SessionIndexEntry> {
        synchronized(indexLock) {
            return loadSessionsIndexInternal()
        }
    }

    private fun updateIndexEntry(entry: SessionIndexEntry) {
        synchronized(indexLock) {
            val entries = loadSessionsIndexInternal().toMutableList()
            val existingIdx = entries.indexOfFirst { it.id == entry.id }
            if (existingIdx != -1) {
                entries[existingIdx] = entry
            } else {
                entries.add(0, entry)
            }
            writeIndexInternal(entries)
        }
    }

    private fun loadSessionsIndexInternal(): List<SessionIndexEntry> {
        if (!indexFile.exists()) return emptyList()
        val list = mutableListOf<SessionIndexEntry>()
        try {
            val arr = JSONArray(indexFile.readText(Charsets.UTF_8))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                list.add(
                    SessionIndexEntry(
                        id = o.optString("id"),
                        title = o.optString("title", "Conversación"),
                        timestamp = o.optLong("timestamp", 0L),
                        messageCount = o.optInt("messageCount", 0),
                        lastModified = o.optLong("lastModified", 0L)
                    )
                )
            }
        } catch (e: Exception) {
            Log.e("PartitionedStorage", "Error leyendo sessions_index.json", e)
        }
        return list.sortedByDescending { it.timestamp }
    }

    private fun writeIndexInternal(entries: List<SessionIndexEntry>) {
        val arr = JSONArray()
        for (e in entries) {
            val o = JSONObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("timestamp", e.timestamp)
                put("messageCount", e.messageCount)
                put("lastModified", e.lastModified)
            }
            arr.put(o)
        }
        val tmp = File(rootDir, "sessions_index.json.tmp")
        val bytes = arr.toString().toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            fos.fd.sync()
        }
        try {
            Files.move(
                tmp.toPath(),
                indexFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (e: Exception) {
            if (!tmp.renameTo(indexFile)) {
                Log.e("PartitionedStorage", "Error renombrando indexFile", e)
            }
        }
    }
}
