package com.codex.chat.core.network

import com.codex.chat.core.model.Attachment
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SubagentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CodexPayloadBuilder {

    fun buildSystemPrompt(activeSubagent: SubagentInfo? = null, webGrounding: String = ""): String {
        val now = Date()
        val localeEs = Locale("es", "ES")
        val dateFullFormatter = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", localeEs)
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val tz = TimeZone.getDefault()

        val fullDateStr = dateFullFormatter.format(now).replaceFirstChar { it.uppercase() }
        val timeStr = timeFormatter.format(now)
        val tzId = tz.id
        val tzName = tz.getDisplayName(false, TimeZone.SHORT, localeEs)

        val sb = StringBuilder()
        sb.append("Eres ChatGPT, un modelo de lenguaje avanzado y asistente conversacional de inteligencia artificial.\n\n")
        sb.append("### INFORMACIÓN TEMPORAL DEL SISTEMA EN TIEMPO REAL:\n")
        sb.append("• Fecha actual: ").append(fullDateStr).append("\n")
        sb.append("• Hora actual: ").append(timeStr).append(" (").append(tzId).append(" / ").append(tzName).append(")\n")
        sb.append("• Año actual: ").append(SimpleDateFormat("yyyy", localeEs).format(now)).append("\n")
        sb.append("• Tienes acceso verificado y directo al reloj y calendario del dispositivo móvil del usuario. Cuando te pregunten qué día es hoy, qué fecha es, o la hora actual, responde siempre con total seguridad, exactitud y naturalidad utilizando estos datos temporales en tiempo real.\n\n")

        if (activeSubagent != null && activeSubagent.systemPrompt.isNotBlank()) {
            sb.append("### INSTRUCCIONES DEL SUBAGENTE O ROL (").append(activeSubagent.name).append("):\n")
            sb.append(activeSubagent.systemPrompt).append("\n\n")
        }

        if (webGrounding.isNotBlank()) {
            sb.append("### CONTEXTO DE BÚSQUEDA WEB EN VIVO:\n")
            sb.append(webGrounding).append("\n\n")
        }

        return sb.toString().trim()
    }

    fun buildChatCompletionPayload(
        model: ModelInfo,
        effort: ReasoningEffort,
        messages: List<ChatMessage>,
        activeSubagent: SubagentInfo? = null,
        webGrounding: String = "",
        stream: Boolean = true
    ): JSONObject {
        val root = JSONObject()
        root.put("model", model.id)
        root.put("stream", stream)

        if (model.supportsReasoning) {
            root.put("reasoning_effort", effort.value)
        }

        val jsonMessages = JSONArray()

        // 1. Primary System Prompt (Temporal awareness + Subagent + Web Grounding) ALWAYS FIRST!
        val systemObj = JSONObject()
        systemObj.put("role", "system")
        systemObj.put("content", buildSystemPrompt(activeSubagent, webGrounding))
        jsonMessages.put(systemObj)

        // 2. Chat history messages (skip existing raw system messages to avoid duplications)
        for (msg in messages) {
            if (msg.role == MessageRole.SYSTEM) continue

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
                            sb.append("[Archivo binario adjuntado correctamente: ").append(doc.sizeBytes).append(" bytes]")
                        }
                    }
                    textContent = sb.toString()
                }

                msgObj.put("content", textContent)
            } else {
                val contentParts = JSONArray()

                val textPart = JSONObject()
                textPart.put("type", "text")
                textPart.put("text", msg.content)
                contentParts.put(textPart)

                for (att in msg.attachments) {
                    if (att.isImage && !att.base64Data.isNullOrBlank()) {
                        val imgPart = JSONObject()
                        imgPart.put("type", "image_url")
                        val urlObj = JSONObject()
                        urlObj.put("url", "data:" + att.mimeType + ";base64," + att.base64Data)
                        imgPart.put("image_url", urlObj)
                        contentParts.put(imgPart)
                    } else if (!att.base64Data.isNullOrBlank()) {
                        val docPart = JSONObject()
                        docPart.put("type", "text")
                        if (att.isTextDocument) {
                            try {
                                val decodedBytes = java.util.Base64.getDecoder().decode(att.base64Data)
                                val text = String(decodedBytes, Charsets.UTF_8)
                                docPart.put("text", "\n\n--- [Adjunto: " + att.fileName + "] ---\n" + text)
                            } catch (e: Exception) {
                                docPart.put("text", "\n\n--- [Adjunto: " + att.fileName + " (Error lectura)] ---")
                            }
                        } else {
                            docPart.put("text", "\n\n--- [Adjunto binario: " + att.fileName + "] ---")
                        }
                        contentParts.put(docPart)
                    }
                }

                msgObj.put("content", contentParts)
            }

            jsonMessages.put(msgObj)
        }

        root.put("messages", jsonMessages)
        return root
    }
}
