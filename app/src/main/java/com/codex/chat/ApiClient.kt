package com.codex.chat

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

class ApiClient(private val settings: SettingsManager) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    interface StreamCallback {
        fun onDelta(chunk: String)
        fun onComplete(fullText: String)
        fun onError(errorMessage: String)
    }

    fun sendMessageStream(
        history: List<ChatMessage>,
        callback: StreamCallback
    ) {
        val baseUrl = settings.baseUrl.trimEnd('/')
        val url = "$baseUrl/chat/completions"
        val apiKey = settings.apiKey
        val model = settings.selectedModel

        // Construct JSON Payload
        val jsonPayload = JSONObject()
        jsonPayload.put("model", model)
        jsonPayload.put("stream", true)

        val messagesArray = JSONArray()
        for (msg in history) {
            val mObj = JSONObject()
            mObj.put("role", if (msg.role == MessageRole.USER) "user" else "assistant")
            mObj.put("content", msg.content)
            messagesArray.put(mObj)
        }
        jsonPayload.put("messages", messagesArray)

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = jsonPayload.toString().toRequestBody(mediaType)

        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "text/event-stream")
            .post(requestBody)
            .build()

        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: java.io.IOException) {
                callback.onError("Error de conexión con el proxy: ${e.localizedMessage}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    callback.onError("HTTP ${response.code}: $errBody")
                    return
                }

                val source = response.body?.byteStream()
                if (source == null) {
                    callback.onError("Respuesta vacía del servidor")
                    return
                }

                val reader = BufferedReader(InputStreamReader(source))
                val fullAccumulator = StringBuilder()

                try {
                    var line: String? = reader.readLine()
                    while (line != null) {
                        val trimmed = line.trim()
                        if (trimmed.startsWith("data:")) {
                            val dataStr = trimmed.substring(5).trim()
                            if (dataStr == "[DONE]") {
                                break
                            }
                            try {
                                val chunkJson = JSONObject(dataStr)
                                val choices = chunkJson.optJSONArray("choices")
                                if (choices != null && choices.length() > 0) {
                                    val delta = choices.getJSONObject(0).optJSONObject("delta")
                                    val content = delta?.optString("content", "") ?: ""
                                    if (content.isNotEmpty()) {
                                        fullAccumulator.append(content)
                                        callback.onDelta(content)
                                    }
                                }
                            } catch (e: Exception) {
                                // Ignore non-JSON ping/keepalive events
                            }
                        }
                        line = reader.readLine()
                    }
                    callback.onComplete(fullAccumulator.toString())
                } catch (e: Exception) {
                    callback.onError("Error leyendo stream: ${e.localizedMessage}")
                } finally {
                    try { reader.close() } catch (ignored: Exception) {}
                }
            }
        })
    }
}
