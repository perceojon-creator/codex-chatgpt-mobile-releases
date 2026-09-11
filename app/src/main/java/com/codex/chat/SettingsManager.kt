package com.codex.chat

import android.content.Context
import android.content.SharedPreferences

class SettingsManager(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("codex_prefs", Context.MODE_PRIVATE)

    var baseUrl: String
        get() = prefs.getString("base_url", "http://192.168.1.6:8317/v1") ?: "http://192.168.1.6:8317/v1"
        set(value) = prefs.edit().putString("base_url", value.trim()).apply()

    var apiKey: String
        get() = prefs.getString("api_key", "local-zcode-key-8317") ?: "local-zcode-key-8317"
        set(value) = prefs.edit().putString("api_key", value.trim()).apply()

    var selectedModel: String
        get() = prefs.getString("selected_model", "gpt-5.6-sol") ?: "gpt-5.6-sol"
        set(value) = prefs.edit().putString("selected_model", value.trim()).apply()
}
