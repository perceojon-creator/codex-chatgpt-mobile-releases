package com.codex.chat.core.network

import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.model.SkillInfo
import com.codex.chat.core.model.SubagentInfo
import com.codex.chat.core.parser.SseStreamParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class CodexApiClient(
    private val client: OkHttpClient = defaultClient()
) {

    companion object {
        fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        }
    }

    interface StreamCallback {
        fun onReasoningDelta(delta: String)
        fun onContentDelta(delta: String)
        fun onComplete(fullContent: String, fullReasoning: String)
        fun onError(error: Throwable)
    }

    fun executeStream(
        baseUrl: String,
        apiKey: String,
        model: ModelInfo,
        effort: ReasoningEffort,
        messages: List<ChatMessage>,
        activeSubagent: SubagentInfo? = null,
        activeSkill: SkillInfo? = null,
        webGrounding: String = "",
        mcpRegistry: com.codex.chat.core.mcp.McpRegistry? = null,
        callback: StreamCallback
    ): Call {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val url = "$cleanBaseUrl/chat/completions"

        val jsonPayload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model,
            effort = effort,
            messages = messages,
            activeSubagent = activeSubagent,
            activeSkill = activeSkill,
            webGrounding = webGrounding,
            stream = true,
            mcpRegistry = mcpRegistry
        )

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val body = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(body)
            .build()

        val call = client.newCall(request)

        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                if (!call.isCanceled()) {
                    callback.onError(e)
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        val errBody = resp.body?.string() ?: ""
                        callback.onError(RuntimeException("HTTP ${resp.code}: $errBody"))
                        return
                    }

                    val source = resp.body?.byteStream()
                    if (source == null) {
                        callback.onError(RuntimeException("Cuerpo de respuesta vacío del servidor"))
                        return
                    }

                    val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
                        override fun onReasoningDelta(delta: String) {
                            callback.onReasoningDelta(delta)
                        }

                        override fun onContentDelta(delta: String) {
                            callback.onContentDelta(delta)
                        }

                        override fun onComplete(fullContent: String, fullReasoning: String) {
                            callback.onComplete(fullContent, fullReasoning)
                        }

                        override fun onError(error: Throwable) {
                            callback.onError(error)
                        }
                    })

                    try {
                        val reader = BufferedReader(InputStreamReader(source, Charsets.UTF_8))
                        val buffer = CharArray(2048)
                        var read = reader.read(buffer)
                        while (read != -1) {
                            if (call.isCanceled()) break
                            val chunk = String(buffer, 0, read)
                            parser.feedChunk(chunk)
                            read = reader.read(buffer)
                        }
                        parser.close()
                    } catch (e: Exception) {
                        if (!call.isCanceled()) {
                            callback.onError(e)
                        }
                    }
                }
            }
        })

        return call
    }
}
