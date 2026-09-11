package com.codex.chat

import android.content.Context
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

data class LocalChatSession(
    val id: String = UUID.randomUUID().toString(),
    var title: String = "Nueva conversación",
    val timestamp: Long = System.currentTimeMillis(),
    val messages: MutableList<ChatMessage> = mutableListOf()
) {
    val formattedDate: String
        get() {
            val sdf = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault())
            return sdf.format(Date(timestamp))
        }
}

class LocalChatRepository(private val context: Context) {

    private val storageFile: File
        get() = File(context.filesDir, "chatgpt_local_history.json")

    @Synchronized
    fun getAllSessions(): List<LocalChatSession> {
        if (!storageFile.exists()) return emptyList()
        val list = mutableListOf<LocalChatSession>()
        try {
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
                    val role = if (m.optString("role") == "user") MessageRole.USER else MessageRole.ASSISTANT
                    val text = m.optString("content", "")
                    val reasoning = m.optString("reasoning", "")
                    msgList.add(ChatMessage(role = role, content = text, reasoningContent = reasoning))
                }
                list.add(LocalChatSession(id = id, title = title, timestamp = time, messages = msgList))
            }
        } catch (e: Exception) {
            // Ignore parse errors
        }
        return list.sortedByDescending { it.timestamp }
    }

    @Synchronized
    fun getSession(id: String): LocalChatSession? {
        return getAllSessions().find { it.id == id }
    }

    @Synchronized
    fun saveSession(session: LocalChatSession) {
        val all = getAllSessions().toMutableList()
        val existingIndex = all.indexOfFirst { it.id == session.id }
        if (existingIndex != -1) {
            all[existingIndex] = session
        } else {
            all.add(0, session)
        }
        persistAll(all)
    }

    @Synchronized
    fun deleteSession(id: String) {
        val all = getAllSessions().toMutableList()
        all.removeAll { it.id == id }
        persistAll(all)
    }

    private fun persistAll(sessions: List<LocalChatSession>) {
        try {
            val array = JSONArray()
            for (s in sessions) {
                val obj = JSONObject().apply {
                    put("id", s.id)
                    put("title", s.title)
                    put("timestamp", s.timestamp)

                    val msgArr = JSONArray()
                    for (m in s.messages) {
                        val mObj = JSONObject().apply {
                            put("role", if (m.role == MessageRole.USER) "user" else "assistant")
                            put("content", m.content)
                            put("reasoning", m.reasoningContent ?: "")
                        }
                        msgArr.put(mObj)
                    }
                    put("messages", msgArr)
                }
                array.put(obj)
            }
            storageFile.writeText(array.toString(2))
        } catch (e: Exception) {
            // Ignore persistence error
        }
    }
}
