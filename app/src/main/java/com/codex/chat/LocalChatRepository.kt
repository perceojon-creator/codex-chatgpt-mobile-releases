package com.codex.chat

import android.content.Context
import android.util.Log
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.concurrent.thread

data class LocalChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Nueva conversación",
    val timestamp: Long = System.currentTimeMillis(),
    private val _messages: MutableList<ChatMessage> = mutableListOf()
) {
    val messagesLock = Any()

    fun getMessagesSnapshot(): List<ChatMessage> = synchronized(messagesLock) {
        ArrayList(_messages)
    }

    fun setMessages(newMessages: List<ChatMessage>) = synchronized(messagesLock) {
        _messages.clear()
        _messages.addAll(newMessages)
    }

    fun addMessage(message: ChatMessage) = synchronized(messagesLock) {
        _messages.add(message)
    }

    val messages: List<ChatMessage>
        get() = getMessagesSnapshot()

    val messageCount: Int
        get() = synchronized(messagesLock) { _messages.size }

    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

/**
 * Repositorio de historial local.
 *
 * CORRECCIÓN CRÍTICA (conversación congelada al reabrir): antes, CADA llamada a
 * getAllSessions()/getSession()/saveSession() re-leía y re-parseaba el JSON completo del disco
 * (con N imágenes de ~850KB el archivo pesa N×850KB; medido: 451 ms POR parseo con 10 imágenes).
 * El flujo "enviar 1 mensaje" lo parseaba 2 veces (getSession + loadDrawerHistory tras guardar),
 * y "abrir conversación" 1 vez — TODO en el hilo UI → congelamiento de segundos.
 *
 * Ahora: cache en memoria con carga lazy (UN parseo del disco por vida del proceso),
 * y persistencia en hilo background. El hilo UI NUNCA toca el JSON multi-MB.
 */
class LocalChatRepository(private val context: Context) {

    private val storageFile: File
        get() = File(context.filesDir, "chatgpt_local_history.json")

    @Volatile
    private var cachedSessions: MutableList<LocalChatSession>? = null
    private val cacheLock = Any()

    private fun sessionsLocked(): MutableList<LocalChatSession> {
        cachedSessions?.let { return it }
        val loaded = readFromDiskLocked()
        cachedSessions = loaded
        return loaded
    }

    private fun readFromDiskLocked(): MutableList<LocalChatSession> {
        val list = mutableListOf<LocalChatSession>()
        try {
            if (!storageFile.exists()) return list
            val content = storageFile.readText()
            val array = JSONArray(content)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.optString("id", UUID.randomUUID().toString())
                val title = obj.optString("title", "Conversación")
                val time = obj.optLong("timestamp", System.currentTimeMillis())

                val msgList = mutableListOf<ChatMessage>()
                val msgArray = obj.optJSONArray("messages") ?: JSONArray()
                for (j in 0 until msgArray.length()) {
                    val m = msgArray.getJSONObject(j)
                    val role = when (m.optString("role")) {
                        "user" -> MessageRole.USER
                        "tool" -> MessageRole.TOOL
                        else -> MessageRole.ASSISTANT
                    }
                    val rawContent = m.optString("content", "")
                    // MIGRACIÓN AUTOMÁTICA: Si una sesión antigua aún tiene base64 gigante embebido,
                    // lo extraemos a filesDir/generated_media/ una sola vez y guardamos la URI file://.
                    val cleanContent = com.codex.chat.core.media.GeneratedMediaStorage.migrateDataUrlsInContent(rawContent)
                    val msgId = m.optString("id", UUID.randomUUID().toString())
                    msgList.add(
                        ChatMessage(
                            id = msgId,
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
                val session = LocalChatSession(id = id, title = title, timestamp = time)
                session.setMessages(msgList)
                list.add(session)
            }
        } catch (e: Exception) {
            Log.e("LocalChatRepo", "Error leyendo historial local", e)
        }
        return list
    }

    /** Copias superficiales: los mensajes se comparten (inmutables en la práctica tras bind). */
    fun getAllSessions(): List<LocalChatSession> {
        synchronized(cacheLock) {
            return sessionsLocked().sortedByDescending { it.timestamp }
        }
    }

    fun getSession(id: String): LocalChatSession? {
        synchronized(cacheLock) {
            return sessionsLocked().find { it.id == id }
        }
    }

    /**
     * Ejecuta el recolector anti-amnesia sobre la sesión si supera el umbral de tokens,
     * persistiendo hechos en SQLite FTS4 y actualizando el almacenamiento atómico.
     */
    fun compactSessionAntiAmnesia(
        sessionId: String,
        thresholdTokens: Int = com.codex.chat.core.harvest.AntiAmnesiaHarvester.DEFAULT_THRESHOLD_TOKENS,
        targetTokens: Int = com.codex.chat.core.harvest.AntiAmnesiaHarvester.DEFAULT_TARGET_TOKENS
    ): com.codex.chat.core.harvest.HarvestResult? {
        val session = synchronized(cacheLock) {
            sessionsLocked().find { it.id == sessionId }
        } ?: return null

        val harvester = com.codex.chat.core.harvest.AntiAmnesiaHarvester(context)
        val result = harvester.harvestAndCompact(session, thresholdTokens, targetTokens)
        if (result.prunedMessageCount > 0) {
            saveSession(session)
        }
        return result
    }

    fun saveSession(session: LocalChatSession) {
        val snapshot: List<LocalChatSession>;
        synchronized(cacheLock) {
            val all = sessionsLocked()
            val existingIndex = all.indexOfFirst { it.id == session.id }
            if (existingIndex != -1) {
                all[existingIndex] = session
            } else {
                all.add(0, session)
            }
            snapshot = all.toList()
        }
        persistAsync(snapshot)
    }

    fun deleteSession(id: String) {
        val snapshot: List<LocalChatSession>;
        synchronized(cacheLock) {
            val all = sessionsLocked()
            all.removeAll { it.id == id }
            snapshot = all.toList()
        }
        persistAsync(snapshot)
    }

    /**
     * Persistencia SIEMPRE en background: serializar N×850KB (medido: 442 ms con 10 imágenes)
     * jamás en el hilo UI. Serial-order garantizado con un job coalescido.
     */
    @Volatile
    private var persistPending = false

    private fun persistAsync(sessions: List<LocalChatSession>) {
        val pending = synchronized(cacheLock) {
            val alreadyQueued = persistPending
            persistPending = true
            alreadyQueued
        }
        if (pending) return // ya hay un worker: tomará esta versión al despertar
        thread(name = "local-chat-persist") {
            try {
                while (true) {
                    val snap: List<LocalChatSession> = synchronized(cacheLock) {
                        persistPending = false;
                        sessionsLocked().toList()
                    }
                    writeToDisk(snap)
                    val more = synchronized(cacheLock) { persistPending }
                    if (!more) break
                }
            } catch (e: Exception) {
                Log.e("LocalChatRepo", "Error persistiendo historial", e)
            }
        }
    }

    private fun writeToDisk(sessions: List<LocalChatSession>) {
        try {
            val array = JSONArray()
            for (s in sessions) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("timestamp", s.timestamp)
                    val msgArr = JSONArray()
                    val msgList = s.getMessagesSnapshot()
                    for (m in msgList) {
                        val mObj = JSONObject().apply {
                            put("id", m.id) // PERSISTIR ID ESTABLE (evita que DiffUtil recree vistas innecesariamente)
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
                array.put(obj)
            }
            // Escritura atómica con durabilidad física estricta (fsync)
            val jsonBytes = array.toString().toByteArray(Charsets.UTF_8)
            val tmp = File(context.filesDir, "chatgpt_local_history.json.tmp")
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(jsonBytes)
                fos.flush()
                fos.fd.sync() // DURABILIDAD FÍSICA: Sincroniza bloques sucios en chip flash
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    storageFile.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING
                )
            } catch (e: Exception) {
                if (!tmp.renameTo(storageFile)) {
                    Log.e("LocalChatRepo", "CRITICAL: Fallo atomic rename de ${tmp.absolutePath} a ${storageFile.absolutePath}. Target preservado.", e)
                    throw java.io.IOException("Atomic rename failed on storageFile", e)
                }
            }
        } catch (e: Exception) {
            Log.e("LocalChatRepo", "Error escribiendo historial", e)
        }
    }
}