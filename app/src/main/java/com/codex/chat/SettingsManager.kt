package com.codex.chat

import android.content.Context
import android.content.SharedPreferences
import com.codex.chat.core.model.ReasoningEffort

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "http://192.168.1.6:8317/v1") ?: "http://192.168.1.6:8317/v1"
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "local-zcode-key-8317") ?: "local-zcode-key-8317"
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var selectedModelId: String
        get() = prefs.getString("selected_model", "gpt-5.6-sol") ?: "gpt-5.6-sol"
        set(value) = prefs.edit().putString("selected_model", value.trim()).apply()

    var reasoningEffort: ReasoningEffort
        get() = ReasoningEffort.fromString(prefs.getString("reasoning_effort", "high"))
        set(value) = prefs.edit().putString("reasoning_effort", value.value).apply()

    var activeSubagentId: String?
        get() = prefs.getString("active_subagent_id", null)
        set(value) = prefs.edit().putString("active_subagent_id", value).apply()

    var e2bApiKey: String
        get() = prefs.getString("e2b_api_key", "e2b_1084ac21c94441ec1fe7f15d06d5953c2568b6ee") ?: "e2b_1084ac21c94441ec1fe7f15d06d5953c2568b6ee"
        set(value) = prefs.edit().putString("e2b_api_key", value.trim()).apply()
}
