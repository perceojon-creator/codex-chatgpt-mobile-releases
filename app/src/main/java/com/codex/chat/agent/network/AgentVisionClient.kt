package com.codex.chat.agent.network

import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.core.GroundingPromptBuilder
import com.codex.chat.core.security.EstopSentinel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Multimodal Grounding Client connecting on-device agent state to CLIProxyAPI.
 * Sends high-resolution compressed vision frames, UI accessibility hierarchy,
 * and historical action telemetry over standard OpenAI /v1/chat/completions.
 * Supports reasoning effort (thinking tokens) for Claude 3.7 Sonnet & Gemini 3.8 Flash.
 */
class AgentVisionClient(
    private val baseUrl: String,
    private val apiKey: String,
    private val okHttpClient: OkHttpClient,
    private val promptBuilder: GroundingPromptBuilder = GroundingPromptBuilder(),
    private val parser: VisionResponseParser = VisionResponseParser(),
    private val screenWidth: Int = 1080,
    private val screenHeight: Int = 1920,
    private val model: String = "gemini-3.8-flash",
    private val reasoningEffort: String? = null
) : IVisionClient {

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun think(
        goal: String,
        stepIndex: Int,
        screenshotBase64: String,
        uiHierarchy: String,
        actionHistory: List<AgentAction>
    ): AgentAction = withContext(Dispatchers.IO) {
        // Enforce fail-safe ESTOP check before invoking external brain
        EstopSentinel.checkOrThrow()

        val systemPrompt = promptBuilder.buildSystemPrompt(screenWidth, screenHeight)
        val userPrompt = promptBuilder.buildUserMessage(goal, stepIndex, uiHierarchy, actionHistory)

        val requestJson = buildMultimodalRequestBody(systemPrompt, userPrompt, screenshotBase64)
        val endpoint = resolveEndpoint(baseUrl)

        val requestBuilder = Request.Builder()
            .url(endpoint)
            .post(requestJson.toString().toRequestBody(jsonMediaType))

        if (apiKey.isNotBlank()) {
            requestBuilder.header("Authorization", "Bearer $apiKey")
        }

        try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()
            response.use { resp ->
                val respBody = resp.body?.string() ?: ""
                if (!resp.isSuccessful) {
                    return@withContext AgentAction.Fail(
                        "Vision HTTP " + resp.code + ": " + respBody.take(150)
                    )
                }

                val jsonResponse = JSONObject(respBody)
                val choices = jsonResponse.optJSONArray("choices")
                if (choices == null || choices.length() == 0) {
                    return@withContext AgentAction.Fail("Invalid response: missing 'choices' array")
                }

                val firstChoice = choices.getJSONObject(0)
                val message = firstChoice.optJSONObject("message")
                val content = message?.optString("content") ?: ""

                parser.parse(content)
            }
        } catch (e: Throwable) {
            if (e is com.codex.chat.core.security.EstopEngagedException || e is SecurityException) {
                throw e
            }
            AgentAction.Fail("Network error during vision reasoning: " + e.message)
        }
    }

    private fun resolveEndpoint(base: String): String {
        val trimmed = base.trim().trimEnd('/')
        return if (trimmed.endsWith("/chat/completions")) {
            trimmed
        } else {
            trimmed + "/chat/completions"
        }
    }

    private fun buildMultimodalRequestBody(
        systemPrompt: String,
        userPrompt: String,
        screenshotBase64: String
    ): JSONObject {
        val messagesArray = JSONArray()

        // 1. System Prompt message
        val systemMsg = JSONObject().apply {
            put("role", "system")
            put("content", systemPrompt)
        }
        messagesArray.put(systemMsg)

        // 2. Multimodal User message with text + image_url
        val userContentArray = JSONArray()
        val textPart = JSONObject().apply {
            put("type", "text")
            put("text", userPrompt)
        }
        userContentArray.put(textPart)

        if (screenshotBase64.isNotBlank()) {
            val imagePart = JSONObject().apply {
                put("type", "image_url")
                put("image_url", JSONObject().apply {
                    put("url", "data:image/jpeg;base64," + screenshotBase64)
                })
            }
            userContentArray.put(imagePart)
        }

        val userMsg = JSONObject().apply {
            put("role", "user")
            put("content", userContentArray)
        }
        messagesArray.put(userMsg)

        return JSONObject().apply {
            put("model", model)
            if (!reasoningEffort.isNullOrBlank() && reasoningEffort != "none") {
                put("reasoning_effort", reasoningEffort)
            }
            put("messages", messagesArray)
            put("max_tokens", 1024)
            put("temperature", 0.0)
        }
    }
}