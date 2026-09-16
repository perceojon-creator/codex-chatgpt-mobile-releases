package com.codex.chat

import android.content.Context
import android.content.SharedPreferences
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.security.SecureCredentialsStore
import com.codex.chat.core.security.SecureKeyVault

class SettingsManager(
    context: Context,
    private val secureStore: SecureCredentialsStore = SecureCredentialsStore.getInstance()
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", BuildConfig.DEFAULT_BASE_URL) ?: BuildConfig.DEFAULT_BASE_URL
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var apiKey: String
        get() = secureStore.getEncryptedString(prefs, "api_key", SecureKeyVault.getCodexLocalKey())
        set(value) = secureStore.putEncryptedString(prefs, "api_key", value.trim())

    var selectedModelId: String
        get() = prefs.getString("selected_model", "gpt-5.6-sol") ?: "gpt-5.6-sol"
        set(value) = prefs.edit().putString("selected_model", value.trim()).apply()

    var reasoningEffort: ReasoningEffort
        get() = ReasoningEffort.fromString(prefs.getString("reasoning_effort", "high"))
        set(value) = prefs.edit().putString("reasoning_effort", value.value).apply()

    var activeSubagentId: String?
        get() = prefs.getString("active_subagent_id", null)
        set(value) = prefs.edit().putString("active_subagent_id", value).apply()

    var activeSkillId: String?
        get() = prefs.getString("active_skill_id", null)
        set(value) = prefs.edit().putString("active_skill_id", value).apply()

    var e2bApiKey: String
        get() = secureStore.getEncryptedString(prefs, "e2b_api_key", SecureKeyVault.getE2bDefaultKey())
        set(value) = secureStore.putEncryptedString(prefs, "e2b_api_key", value.trim())

    var approvalPolicy: ApprovalPolicy
        get() = ApprovalPolicy.fromNivel(prefs.getInt("approval_policy_nivel", 2))
        set(value) = prefs.edit().putInt("approval_policy_nivel", value.nivel).apply()

    var defaultCwd: String
        get() = prefs.getString("default_cwd", "") ?: ""
        set(value) = prefs.edit().putString("default_cwd", value.trim()).apply()

    var activeProfileId: String
        get() = prefs.getString("active_profile_id", "builtin_codex_pc") ?: "builtin_codex_pc"
        set(value) = prefs.edit().putString("active_profile_id", value.trim()).apply()

    var customProfilesJson: String
        get() = secureStore.getEncryptedString(prefs, "custom_profiles_json", "[]")
        set(value) = secureStore.putEncryptedString(prefs, "custom_profiles_json", value.trim())
}
