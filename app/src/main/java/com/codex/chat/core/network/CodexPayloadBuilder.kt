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

        // 1. Injected System Prompt if subagent is active
        if (activeSubagent != null && activeSubagent.systemPrompt.isNotBlank()) {
            val systemObj = JSONObject()
            systemObj.put("role", "system")
            systemObj.put("content", activeSubagent.systemPrompt)
            jsonMessages.put(systemObj)
        }

        // 2. Chat history messages
        for (msg in messages) {
            val msgObj = JSONObject()
            msgObj.put("role", msg.role.value)

            val hasImageAttachments = msg.attachments.any { it.isImage && !it.base64Data.isNullOrBlank() }

            if (!hasImageAttachments) {
                var textContent = msg.content

                val nonImageAttachments = msg.attachments.filter { !it.isImage && !it.base64Data.isNullOrBlank() }
                if (nonImageAttachments.isNotEmpty()) {
                    val sb = StringBuilder(textContent)
                    for (doc in nonImageAttachments) {
                        sb.append("\n\n--- [Adjunto: ").append(doc.fileName).append(" (").append(doc.mimeType).append(")] ---\n")
                        if (doc.isTextDocument) {
                            try {
                                val decodedBytes = java.util.Base64.getDecoder().decode(doc.base64Data)
                                val text = String(decodedBytes, Charsets.UTF_8)
                                sb.append(text)
                            } catch (e: Exception) {
                                sb.append("[Error decodificando texto: ").append(e.message).append("]")
                            }
                        } else {
                            // Binary/PDF file: provide metadata and raw data reference to avoid UTF-8 replacement character corruption
                            sb.append("[Archivo binario de ").append(doc.sizeBytes / 1024).append(" KB codificado en Base64 seguro]\n")
                            sb.append("data:").append(doc.mimeType).append(";base64,").append(doc.base64Data)
                        }
                        sb.append("\n--- [Fin de ").append(doc.fileName).append("] ---\n")
                    }
                    textContent = sb.toString()
                }

                msgObj.put("content", textContent)
            } else {
                // OpenAI multimodal content array
                val contentArray = JSONArray()

                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", if (msg.content.isEmpty()) "Analiza los elementos adjuntos." else msg.content)
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
