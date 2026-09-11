package com.codex.chat.core.network

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

data class E2BExecutionResult(
    val success: Boolean,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null,
    val sandboxId: String = ""
)

class DirectE2BClient(
    private val client: OkHttpClient = defaultHttpClient()
) {

    companion object {
        const val DEFAULT_API_KEY = "e2b_1084ac21c94441ec1fe7f15d06d5953c2568b6ee"
        const val E2B_API_URL = "https://api.e2b.dev"

        fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build()
        }
    }

    // Stateful cache: session_id -> active sandbox_id
    private val activeSandboxes = ConcurrentHashMap<String, String>()

    @Synchronized
    fun getOrCreateSandbox(apiKey: String, sessionId: String, timeoutSec: Int = 300): String {
        val existing = activeSandboxes[sessionId]
        if (!existing.isNullOrBlank()) {
            // Verify if still alive
            if (isSandboxAlive(apiKey, existing)) {
                return existing
            } else {
                activeSandboxes.remove(sessionId)
            }
        }

        val jsonBody = JSONObject().apply {
            put("templateID", "code-interpreter-v1")
            put("timeout", timeoutSec)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url("$E2B_API_URL/sandboxes")
            .addHeader("X-API-KEY", apiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody(mediaType))
            .build()

        val response = client.newCall(request).execute()
        if (!response.isSuccessful) {
            val err = response.body?.string() ?: ""
            throw RuntimeException("Fallo al crear sandbox en E2B Cloud (HTTP ${response.code}): $err")
        }

        val resJson = JSONObject(response.body?.string() ?: "{}")
        val sandboxId = resJson.optString("sandboxID", "")
        if (sandboxId.isEmpty()) {
            throw RuntimeException("E2B Cloud no devolvió un sandboxID válido")
        }

        activeSandboxes[sessionId] = sandboxId
        return sandboxId
    }

    private fun isSandboxAlive(apiKey: String, sandboxId: String): Boolean {
        return try {
            val request = Request.Builder()
                .url("$E2B_API_URL/sandboxes/$sandboxId")
                .addHeader("X-API-KEY", apiKey)
                .get()
                .build()
            val resp = client.newCall(request).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }

    fun executePython(
        apiKey: String,
        sessionId: String,
        code: String,
        onStdoutChunk: ((String) -> Unit)? = null
    ): E2BExecutionResult {
        val cleanCode = code.trim()
        if (cleanCode.isEmpty()) {
            return E2BExecutionResult(success = false, error = "El código Python a ejecutar está vacío")
        }

        val actualApiKey = if (apiKey.isNotBlank()) apiKey else DEFAULT_API_KEY
        val sandboxId = try {
            getOrCreateSandbox(actualApiKey, sessionId)
        } catch (e: Exception) {
            return E2BExecutionResult(success = false, error = "Error conectando con E2B Cloud: " + e.message)
        }

        val jupyterUrl = "https://49999-$sandboxId.e2b.app/execute"

        val jsonBody = JSONObject().apply {
            put("code", cleanCode)
        }

        val mediaType = "application/json; charset=utf-8".toMediaType()
        val request = Request.Builder()
            .url(jupyterUrl)
            .addHeader("X-API-KEY", actualApiKey)
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody(mediaType))
            .build()

        val stdoutBuffer = StringBuilder()
        val stderrBuffer = StringBuilder()
        var errorMsg: String? = null

        try {
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errText = response.body?.string() ?: ""
                return E2BExecutionResult(
                    success = false,
                    error = "Error al ejecutar en E2B Cloud (HTTP ${response.code}): $errText",
                    sandboxId = sandboxId
                )
            }

            val inputStream = response.body?.byteStream()
                ?: return E2BExecutionResult(success = false, error = "Cuerpo de respuesta vacío de E2B", sandboxId = sandboxId)

            val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
            var line: String? = reader.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.isNotEmpty()) {
                    try {
                        val obj = JSONObject(trimmed)
                        val type = obj.optString("type", "")
                        when (type) {
                            "stdout" -> {
                                val text = obj.optString("text", "")
                                stdoutBuffer.append(text)
                                onStdoutChunk?.invoke(text)
                            }
                            "stderr" -> {
                                val text = obj.optString("text", "")
                                stderrBuffer.append(text)
                            }
                            "error" -> {
                                val name = obj.optString("name", "Error")
                                val value = obj.optString("value", "")
                                errorMsg = if (errorMsg == null) "$name: $value" else "$errorMsg\n$name: $value"
                            }
                        }
                    } catch (e: Exception) {
                        // Skip unparseable line
                    }
                }
                line = reader.readLine()
            }

            val isSuccess = errorMsg == null
            return E2BExecutionResult(
                success = isSuccess,
                stdout = stdoutBuffer.toString().trim(),
                stderr = stderrBuffer.toString().trim(),
                error = errorMsg,
                sandboxId = sandboxId
            )
        } catch (e: Exception) {
            return E2BExecutionResult(
                success = false,
                error = "Excepción durante la ejecución en E2B Cloud: " + e.message,
                sandboxId = sandboxId
            )
        }
    }

    fun killSandbox(apiKey: String, sessionId: String): Boolean {
        val sandboxId = activeSandboxes.remove(sessionId) ?: return false
        val actualApiKey = if (apiKey.isNotBlank()) apiKey else DEFAULT_API_KEY
        return try {
            val request = Request.Builder()
                .url("$E2B_API_URL/sandboxes/$sandboxId")
                .addHeader("X-API-KEY", actualApiKey)
                .delete()
                .build()
            val resp = client.newCall(request).execute()
            resp.isSuccessful
        } catch (e: Exception) {
            false
        }
    }
}
