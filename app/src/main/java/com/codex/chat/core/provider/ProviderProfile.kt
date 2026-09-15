package com.codex.chat.core.provider

import com.codex.chat.BuildConfig
import com.codex.chat.core.security.SecureKeyVault
import org.json.JSONObject

data class ProviderProfile(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val defaultModel: String,
    val isReadOnly: Boolean = false,
    val description: String = ""
) {
    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("name", name)
            put("baseUrl", baseUrl)
            put("apiKey", apiKey)
            put("defaultModel", defaultModel)
            put("isReadOnly", isReadOnly)
            put("description", description)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): ProviderProfile {
            return ProviderProfile(
                id = json.optString("id"),
                name = json.optString("name"),
                baseUrl = json.optString("baseUrl"),
                apiKey = json.optString("apiKey"),
                defaultModel = json.optString("defaultModel"),
                isReadOnly = json.optBoolean("isReadOnly", false),
                description = json.optString("description")
            )
        }
    }
}

object BuiltInProviders {
    val PROFILE_1_APINEX = ProviderProfile(
        id = "builtin_apinex_free",
        name = "Perfil 1: APInex (Free Models)",
        baseUrl = "https://api.apinex.bond/v1",
        apiKey = SecureKeyVault.getApinexKey(),
        defaultModel = "free/deepseek-v4.1-flash",
        isReadOnly = true,
        description = "DeepSeek v4.1 Flash, Qwen 3.8 Max, GLM 5.3 Flash (10M tokens/día gratis con check-in)"
    )

    val PROFILE_2_BAI = ProviderProfile(
        id = "builtin_b_ai",
        name = "Perfil 2: B.AI (GLM & GPT)",
        baseUrl = "https://api.b.ai/v1",
        apiKey = SecureKeyVault.getBaiKey(),
        defaultModel = "glm-5.3-flash",
        isReadOnly = true,
        description = "GLM 5.3 Flash, MiniMax M3, GPT 5.6 (47 modelos disponibles)"
    )

    val PROFILE_3_CODEX_PC = ProviderProfile(
        id = "builtin_codex_pc",
        name = "Perfil 3: Codex Desktop (Proxy Local)",
        baseUrl = BuildConfig.DEFAULT_BASE_URL,
        apiKey = SecureKeyVault.getCodexLocalKey(),
        defaultModel = "gpt-5.6-sol",
        isReadOnly = true,
        description = "Servidor local en PC con acceso a herramientas MCP nativas"
    )

    val ALL: List<ProviderProfile> = listOf(
        PROFILE_1_APINEX,
        PROFILE_2_BAI,
        PROFILE_3_CODEX_PC
    )
}
