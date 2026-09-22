package com.codex.chat.core.compaction

import android.util.Log
import com.codex.chat.core.metrics.ContextMetricsCalculator
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CompactionResult(
    val success: Boolean,
    val compactedMessages: List<ChatMessage>,
    val tokensSaved: Int = 0,
    val previousTokens: Int = 0,
    val newTokens: Int = 0,
    val summaryText: String = "",
    val checkpointMessage: ChatMessage? = null,
    val error: String? = null
)

class CompactionEngine(
    private val client: OkHttpClient = defaultClient(),
    private val summarizerCallOverride: ((messages: List<ChatMessage>, instruction: String, modelId: String) -> String)? = null
) {

    companion object {
        private const val TAG = "CompactionEngine"
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    fun calculateTotalTokens(messages: List<ChatMessage>, model: ModelInfo): Int {
        var total = 0
        for (msg in messages) {
            total += CompactionRegionSelector.estimateMessageTokens(msg)
        }
        return total
    }

    fun compact(
        messages: List<ChatMessage>,
        model: ModelInfo,
        baseUrl: String = "",
        apiKey: String = "",
        force: Boolean = false,
        config: CompactionConfig = CompactionConfig(),
        preferredSummarizerModel: String? = null
    ): CompactionResult {
        val contextWindow = ContextMetricsCalculator.resolveContextWindow(model)
        val initialTokens = calculateTotalTokens(messages, model)

        if (!force) {
            val decision = CompactionPolicy.evaluate(
                currentTokens = initialTokens,
                contextWindow = contextWindow,
                messageCount = messages.size,
                config = config
            )
            if (!decision.shouldCompact) {
                return CompactionResult(
                    success = false,
                    compactedMessages = messages,
                    previousTokens = initialTokens,
                    newTokens = initialTokens,
                    error = decision.reason
                )
            }
        }

        val region = CompactionRegionSelector.selectRegion(
            messages = messages,
            contextWindow = contextWindow,
            retainRatio = config.retainRatio,
            minRetainMessages = config.minRetainMessages,
            force = force
        ) ?: return CompactionResult(
            success = false,
            compactedMessages = messages,
            previousTokens = initialTokens,
            newTokens = initialTokens,
            error = "Conversación insuficiente o imposible de dividir respetando herramientas"
        )

        val chosenSummarizer = preferredSummarizerModel ?: model.id

        val rawSummary = try {
            if (summarizerCallOverride != null) {
                summarizerCallOverride.invoke(region.spanToCompact, CompactionConstants.COMPACTION_INSTRUCTION, chosenSummarizer)
            } else {
                callSummarizerApi(region.spanToCompact, CompactionConstants.COMPACTION_INSTRUCTION, chosenSummarizer, baseUrl, apiKey)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error durante la llamada de compactación LLM", e)
            return CompactionResult(
                success = false,
                compactedMessages = messages,
                previousTokens = initialTokens,
                newTokens = initialTokens,
                error = "Fallo en llamada de sumarización: " + (e.message ?: "desconocido")
            )
        }

        if (rawSummary.isBlank()) {
            return CompactionResult(
                success = false,
                compactedMessages = messages,
                previousTokens = initialTokens,
                newTokens = initialTokens,
                error = "El modelo de sumarización devolvió un sumario vacío"
            )
        }

        val framed = CompactionCheckpointFormatter.frameSummary(rawSummary)
        val checkpointMsg = ChatMessage(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = framed,
            timestamp = System.currentTimeMillis()
        )

        val compacted = mutableListOf<ChatMessage>()
        if (region.retainedHead.isNotEmpty()) {
            compacted.addAll(region.retainedHead)
        }
        compacted.add(checkpointMsg)
        compacted.addAll(region.retainedTail)

        val newTokens = calculateTotalTokens(compacted, model)
        val saved = (initialTokens - newTokens).coerceAtLeast(0)

        Log.i(TAG, "Compactación completada con éxito: $initialTokens -> $newTokens tokens (Ahorrados: $saved tokens, Span: ${region.spanToCompact.size}, Tail: ${region.retainedTail.size})")

        return CompactionResult(
            success = true,
            compactedMessages = compacted,
            tokensSaved = saved,
            previousTokens = initialTokens,
            newTokens = newTokens,
            summaryText = rawSummary,
            checkpointMessage = checkpointMsg
        )
    }

    private fun callSummarizerApi(
        spanToCompact: List<ChatMessage>,
        instruction: String,
        modelId: String,
        baseUrl: String,
        apiKey: String
    ): String {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = "$cleanBaseUrl/chat/completions"

        val jsonMessages = JSONArray()
        for (msg in spanToCompact) {
            if (msg.role == MessageRole.SYSTEM) continue
            val obj = JSONObject()
            if (msg.role == MessageRole.TOOL) {
                obj.put("role", "tool")
                obj.put("tool_call_id", msg.toolCallId.ifBlank { "call-" + UUID.randomUUID().toString().take(8) })
                obj.put("content", msg.content)
            } else if (msg.role == MessageRole.ASSISTANT && msg.toolCallsJson.isNotBlank()) {
                obj.put("role", "assistant")
                try {
                    obj.put("tool_calls", JSONArray(msg.toolCallsJson))
                } catch (e: Exception) {
                    Log.w(TAG, "Aviso: no se pudo serializar tool_calls para compactación: ${e.message}")
                }
            } else {
                obj.put("role", msg.role.value)
                obj.put("content", msg.content)
            }
            jsonMessages.put(obj)
        }

        val instructionObj = JSONObject()
        instructionObj.put("role", "user")
        instructionObj.put("content", instruction)
        jsonMessages.put(instructionObj)

        val payload = JSONObject().apply {
            put("model", modelId)
            put("stream", false)
            put("temperature", 0.2)
            put("messages", jsonMessages)
        }

        val body = payload.toString().toRequestBody(JSON_MEDIA)
        val req = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(body)
            .build()

        client.newCall(req).execute().use { response ->
            if (!response.isSuccessful) {
                throw RuntimeException("HTTP ${response.code}: ${response.message}")
            }
            val bodyStr = response.body?.string() ?: ""
            val json = JSONObject(bodyStr)
            val choices = json.optJSONArray("choices")
            if (choices == null || choices.length() == 0) {
                throw RuntimeException("Respuesta sin choices: $bodyStr")
            }
            val firstChoice = choices.getJSONObject(0)
            val messageObj = firstChoice.optJSONObject("message")
            return messageObj?.optString("content", "") ?: ""
        }
    }
}