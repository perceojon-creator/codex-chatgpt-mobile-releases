package com.codex.chat.core.repository

import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class DynamicModelsRepository(private val client: OkHttpClient = defaultClient()) {

    companion object {
        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .build()
        }

        val DEFAULT_MODELS = listOf(
            ModelInfo(
                id = "z-ai/glm-5.3-flash",
                displayName = "GLM 5.3 Flash (Freebuff)",
                provider = "Freebuff",
                contextWindow = 128_000,
                supportsReasoning = true,
                defaultReasoningEffort = ReasoningEffort.MEDIUM
            ),
            ModelInfo(
                id = "minimax/minimax-m3",
                displayName = "MiniMax M3 (Freebuff)",
                provider = "Freebuff",
                contextWindow = 1_000_000,
                supportsReasoning = true,
                defaultReasoningEffort = ReasoningEffort.MEDIUM
            ),
            ModelInfo(
                id = "google/gemini-3.1-flash-lite-preview",
                displayName = "Gemini 3.1 Flash Lite 1M (Freebuff)",
                provider = "Freebuff",
                contextWindow = 1_048_576,
                supportsReasoning = false,
                defaultReasoningEffort = ReasoningEffort.LOW
            ),
            ModelInfo(
                id = "gpt-5.6-sol",
                displayName = "GPT-5.6 Sol (Gemini 3.8 Flash High)",
                provider = "Antigravity",
                contextWindow = 1_048_576,
                supportsReasoning = true,
                defaultReasoningEffort = ReasoningEffort.HIGH
            ),
            ModelInfo(
                id = "astra",
                displayName = "Astra (Claude Sonnet 4.6)",
                provider = "Antigravity",
                contextWindow = 200_000,
                supportsReasoning = true,
                defaultReasoningEffort = ReasoningEffort.XHIGH
            ),
            ModelInfo(
                id = "gpt-6-astra",
                displayName = "GPT-6 Astra (Claude Sonnet 4.6 Max)",
                provider = "Antigravity",
                contextWindow = 200_000,
                supportsReasoning = true,
                defaultReasoningEffort = ReasoningEffort.XHIGH
            ),
            ModelInfo(
                id = "gpt-5.6-terra",
                displayName = "GPT-5.6 Terra (DeepSeek V4 Flash)",
                provider = "DeepSeek",
                contextWindow = 128_000,
                supportsReasoning = true,
                defaultReasoningEffort = ReasoningEffort.MEDIUM
            ),
            ModelInfo(
                id = "gpt-5.6-luna",
                displayName = "GPT-5.6 Luna (GLM 5.3 Flash)",
                provider = "B-AI Free",
                contextWindow = 128_000,
                supportsReasoning = false,
                defaultReasoningEffort = ReasoningEffort.LOW
            )
        )
    }

    private val cachedModels = mutableListOf<ModelInfo>().apply {
        addAll(DEFAULT_MODELS)
    }

    fun getCachedModels(): List<ModelInfo> = synchronized(cachedModels) {
        cachedModels.toList()
    }

    fun getModelById(id: String): ModelInfo {
        return synchronized(cachedModels) {
            cachedModels.firstOrNull { it.id == id }
                ?: ModelInfo(
                    id = id,
                    displayName = id,
                    provider = "Custom",
                    supportsReasoning = id.contains("sol") || id.contains("astra") || id.contains("o1") || id.contains("r1")
                )
        }
    }

    /**
     * Fetches live models dynamically from CLIProxyAPI /v1/models
     */
    fun fetchLiveModels(baseUrl: String, apiKey: String): Result<List<ModelInfo>> {
        val url = "${baseUrl.trimEnd('/')}/models"
        val request = Request.Builder()
            .url(url)
            .addHeader("Authorization", "Bearer $apiKey")
            .get()
            .build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(RuntimeException("HTTP ${response.code}: ${response.message}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val dataArray = json.optJSONArray("data")
                    ?: return Result.failure(RuntimeException("JSON no contiene arreglo 'data'"))

                val fetched = mutableListOf<ModelInfo>()
                for (i in 0 until dataArray.length()) {
                    val item = dataArray.getJSONObject(i)
                    val modelId = item.optString("id", "")
                    if (modelId.isNotBlank()) {
                        val ownedBy = item.optString("owned_by", "Proxy")
                        val contextLen = item.optInt("context_length", item.optInt("max_context_length", 0))
                        val supportsReasoning = modelId.contains("sol") ||
                                modelId.contains("astra") ||
                                modelId.contains("claude") ||
                                modelId.contains("gemini") ||
                                modelId.contains("o1") ||
                                modelId.contains("deepseek")

                        fetched.add(
                            ModelInfo(
                                id = modelId,
                                displayName = formatModelDisplayName(modelId),
                                provider = ownedBy,
                                contextWindow = contextLen,
                                supportsReasoning = supportsReasoning,
                                defaultReasoningEffort = if (supportsReasoning) ReasoningEffort.HIGH else ReasoningEffort.LOW
                            )
                        )
                    }
                }

                if (fetched.isNotEmpty()) {
                    synchronized(cachedModels) {
                        cachedModels.clear()
                        cachedModels.addAll(fetched)
                    }
                    Result.success(fetched)
                } else {
                    Result.success(DEFAULT_MODELS)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetches models directly from native OpenAI Codex app-server via /api/codex/models
     */
    fun fetchNativeCodexModels(codexServerBaseUrl: String): Result<List<ModelInfo>> {
        val url = "${codexServerBaseUrl.trimEnd('/')}/api/codex/models"
        val request = Request.Builder().url(url).get().build()

        return try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return Result.failure(RuntimeException("HTTP ${response.code}: ${response.message}"))
                }
                val bodyStr = response.body?.string() ?: ""
                val json = JSONObject(bodyStr)
                val modelsArray = json.optJSONArray("models")
                    ?: return Result.failure(RuntimeException("JSON no contiene 'models'"))

                val fetched = mutableListOf<ModelInfo>()
                for (i in 0 until modelsArray.length()) {
                    val item = modelsArray.getJSONObject(i)
                    val modelId = item.optString("model", item.optString("id", ""))
                    if (modelId.isNotBlank()) {
                        val dName = item.optString("displayName", formatModelDisplayName(modelId))
                        val contextLen = item.optInt("context_length", item.optInt("max_context_length", 0))
                        val supportsReasoning = item.has("supportedReasoningEfforts") ||
                                modelId.contains("sol") || modelId.contains("astra") || modelId.contains("o1")

                        fetched.add(
                            ModelInfo(
                                id = modelId,
                                displayName = "$dName (Nativo)",
                                provider = "OpenAI Codex",
                                contextWindow = contextLen,
                                supportsReasoning = supportsReasoning,
                                defaultReasoningEffort = if (supportsReasoning) ReasoningEffort.HIGH else ReasoningEffort.LOW
                            )
                        )
                    }
                }

                if (fetched.isNotEmpty()) {
                    synchronized(cachedModels) {
                        cachedModels.clear()
                        cachedModels.addAll(fetched)
                    }
                    Result.success(fetched)
                } else {
                    Result.success(DEFAULT_MODELS)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatModelDisplayName(id: String): String {
        return when (id) {
            "z-ai/glm-5.3-flash" -> "GLM 5.3 Flash (Freebuff)"
            "z-ai/glm-5.1" -> "GLM 5.1 (Freebuff)"
            "minimax/minimax-m2.7" -> "MiniMax M2.7 (Freebuff)"
            "minimax/minimax-m3" -> "MiniMax M3 (Freebuff)"
            "google/gemini-2.5-flash-lite" -> "Gemini 2.5 Flash Lite (Freebuff)"
            "google/gemini-3.1-flash-lite-preview" -> "Gemini 3.1 Flash Lite 1M (Freebuff)"
            "google/gemini-3.8-flash" -> "Gemini 3.8 Flash (Freebuff)"
            "claude-fable-5-1" -> "Claude Fable 5.1 (Freebuff)"
            "gpt-5.6-luna" -> "GPT-5.6 Luna (Freebuff)"
            "gpt-5.6-sol" -> "GPT-5.6 Sol (Gemini 3.8 Flash High)"
            "astra" -> "Astra (Claude Sonnet 4.6 Antigravity)"
            "gpt-6-astra" -> "GPT-6 Astra (Claude Sonnet 4.6 Max)"
            "gpt-5.6-terra" -> "GPT-5.6 Terra (DeepSeek V4 Flash)"
            else -> id
        }
    }
}
