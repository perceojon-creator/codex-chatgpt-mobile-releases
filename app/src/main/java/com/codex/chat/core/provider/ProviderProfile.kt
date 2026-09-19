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
    val PROFILE_CODEX_PC = ProviderProfile(
        id = "builtin_codex_pc",
        name = "Codex Desktop (Proxy Local)",
        baseUrl = BuildConfig.DEFAULT_BASE_URL,
        apiKey = SecureKeyVault.getCodexLocalKey().ifBlank { "proxy-pool" },
        defaultModel = "gpt-5.6-sol",
        isReadOnly = true,
        description = "Servidor local en PC con acceso a herramientas MCP nativas"
    )

    // Alias para compatibilidad con código existente
    val PROFILE_3_CODEX_PC = PROFILE_CODEX_PC

    val ALL: List<ProviderProfile> = listOf(
        PROFILE_CODEX_PC
    )
}
