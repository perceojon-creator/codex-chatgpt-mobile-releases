package com.codex.chat.core.network

import com.codex.chat.core.model.Attachment
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SubagentInfo
import org.json.JSONArray
import org.json.JSONObject

object CodexPayloadBuilder {

    fun buildChatCompletionPayload(
        model: ModelInfo,
        effort: ReasoningEffort,
        messages: List<ChatMessage>,
        activeSubagent: SubagentInfo? = null,
        stream: Boolean = true
    ): JSONObject {
        val root = JSONObject()
        root.put("model", model.id)
        root.put("stream", stream)

        if (model.supportsReasoning) {
            root.put("reasoning_effort", effort.value)
        }

        val jsonMessages = JSONArray()

        if (activeSubagent != null && activeSubagent.systemPrompt.isNotBlank()) {
            val systemObj = JSONObject()
            systemObj.put("role", "system")
            systemObj.put("content", activeSubagent.systemPrompt)
            jsonMessages.put(systemObj)
        }

        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role.value)

            val hasImageAttachments = msg.attachments.any { it.isImage && !it.base64Data.isNullOrBlank() }

            if (!hasImageAttachments) {
                var textContent = msg.content

                val docAttachments = msg.attachments.filter { !it.isImage && !it.base64Data.isNullOrBlank() }
                if (docAttachments.isNotEmpty()) {
                    val sb = StringBuilder(textContent)
                    for (doc in docAttachments) {
                        sb.append("\n\n--- [Documento Adjunto: ").append(doc.fileName).append(" (").append(doc.mimeType).append(")] ---\n")
                        try {
                            val decoded = String(java.util.Base64.getDecoder().decode(doc.base64Data))
                            sb.append(decoded)
                        } catch (e: Exception) {
                            sb.append("[Error decodificando adjunto base64]")
                        }
                        sb.append("\n--- [Fin de ").append(doc.fileName).append("] ---\n")
                    }
                    textContent = sb.toString()
                }

                msgObj.put("content", textContent)
            } else {
                val contentArray = JSONArray()

                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", if (msg.content.isEmpty()) "Analiza la imagen adjunta." else msg.content)
                contentArray.put(textPart)

                for (att in msg.attachments) {
                    if (att.isImage && !att.base64Data.isNullOrBlank()) {
                        val imgPart = JSONObject()
                        imgPart.put("type", "image_url")
                        val imgUrlObj = JSONObject()
                        imgUrlObj.put("url", "data:" + att.mimeType + ";base64," + att.base64Data)
                        imgPart.put("image_url", imgUrlObj)
                        contentArray.put(imgPart)
                    }
                }

                msgObj.put("content", contentArray)
            }

            jsonMessages.put(msgObj)
        }

        root.put("messages", jsonMessages)
        return root
    }
}
