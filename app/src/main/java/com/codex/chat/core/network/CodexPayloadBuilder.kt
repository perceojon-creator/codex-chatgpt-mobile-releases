package com.codex.chat.core.network

import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.model.Attachment
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SkillInfo
import com.codex.chat.core.model.SubagentInfo
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object CodexPayloadBuilder {

    fun buildSystemPrompt(
        activeSubagent: SubagentInfo? = null,
        webGrounding: String = ""
    ): String = buildSystemPrompt(activeSubagent, null, webGrounding, null)

    fun buildSystemPrompt(
        activeSubagent: SubagentInfo? = null,
        activeSkill: SkillInfo? = null,
        webGrounding: String = "",
        mcpRegistry: McpRegistry? = null
    ): String {
        val now = Date()
        val localeEs = Locale("es", "ES")
        val dateFullFormatter = SimpleDateFormat("EEEE, d 'de' MMMM 'de' yyyy", localeEs)
        val timeFormatter = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        val tz = TimeZone.getDefault()

        val fullDateStr = dateFullFormatter.format(now).lowercase(localeEs)
        val timeStr = timeFormatter.format(now)
        val tzId = tz.id

        val sb = StringBuilder()
        sb.append("You are ChatGPT, a large language model trained by OpenAI.\n")
        sb.append("Current date: ").append(fullDateStr).append(".\n")
        sb.append("Current time: ").append(timeStr).append(" (").append(tzId).append(").\n\n")
        sb.append("Instructions:\n")
        sb.append("- Always respond in Spanish clearly, naturally and authoritatively unless requested otherwise.\n")
        sb.append("- Today's date is strictly ").append(fullDateStr).append(".\n")
        sb.append("- When asked what day it is, what date it is, or what time it is, answer directly with this date and time without any disclaimers about lacking real-time access.\n\n")

        val effectiveSkill = activeSkill ?: activeSubagent?.toSkill()
        if (effectiveSkill != null && effectiveSkill.systemPrompt.isNotBlank()) {
            sb.append("### Active Native Skill (").append(effectiveSkill.name).append(" - ").append(effectiveSkill.author).append("):\n")
            sb.append(effectiveSkill.systemPrompt).append("\n\n")
        }

        if (webGrounding.isNotBlank()) {
            sb.append("### Context from live web search:\n")
            sb.append(webGrounding).append("\n\n")
            sb.append("Instructions for search context:\n")
            sb.append("- Synthesize the provided search results directly and authoritatively to answer the user's query, citing sources naturally without disclaimers about internet connectivity.\n\n")
        }

        if (mcpRegistry != null) {
            val mcpSummary = mcpRegistry.buildMcpSystemPromptSummary()
            if (mcpSummary.isNotBlank()) {
                sb.append(mcpSummary)
            }
        }

        return sb.toString().trim()
    }

    fun buildChatCompletionPayload(
        model: ModelInfo,
        effort: ReasoningEffort,
        messages: List<ChatMessage>,
        activeSubagent: SubagentInfo? = null,
        activeSkill: SkillInfo? = null,
        webGrounding: String = "",
        stream: Boolean = true,
        mcpRegistry: McpRegistry? = null
    ): JSONObject {
        val root = JSONObject()
        root.put("model", model.id)
        root.put("stream", stream)

        if (model.supportsReasoning) {
            root.put("reasoning_effort", effort.value)
        }

        // Add native MCP tools to OpenAI function calling schema
        if (mcpRegistry != null) {
            val activeTools = mcpRegistry.getAllActiveTools()
            if (activeTools.isNotEmpty()) {
                val toolsArray = JSONArray()
                for (t in activeTools) {
                    toolsArray.put(t.toOpenAiToolSchema())
                }
                root.put("tools", toolsArray)
            }
        }

        val jsonMessages = JSONArray()

        // 1. Primary System Prompt (Temporal awareness + Subagent + Web Grounding + MCP) ALWAYS FIRST!
        val systemObj = JSONObject()
        systemObj.put("role", "system")
        systemObj.put("content", buildSystemPrompt(activeSubagent, activeSkill, webGrounding, mcpRegistry))
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