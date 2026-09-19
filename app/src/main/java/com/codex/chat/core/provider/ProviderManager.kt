package com.codex.chat.core.provider

import com.codex.chat.SettingsManager
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

class ProviderManager(private val settings: SettingsManager) {

    fun getAllProfiles(): List<ProviderProfile> {
        val builtIns = BuiltInProviders.ALL
        val customs = getCustomProfiles()
        return builtIns + customs
    }

    fun getCustomProfiles(): List<ProviderProfile> {
        val list = mutableListOf<ProviderProfile>()
        try {
            val jsonArr = JSONArray(settings.customProfilesJson)
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                list.add(ProviderProfile.fromJson(obj))
            }
        } catch (e: Exception) {
            // Retorna lista vacia en caso de error
        }
        return list
    }

    fun getProfileById(id: String): ProviderProfile? {
        return getAllProfiles().find { it.id == id }
    }

    fun getActiveProfile(): ProviderProfile {
        val currentId = settings.activeProfileId
        return getProfileById(currentId) ?: BuiltInProviders.PROFILE_3_CODEX_PC
    }

    fun applyProfile(profile: ProviderProfile) {
        settings.activeProfileId = profile.id
        settings.baseUrl = profile.baseUrl
        settings.apiKey = profile.apiKey
        if (profile.defaultModel.isNotBlank()) {
            settings.selectedModelId = profile.defaultModel
        }
    }

    fun saveCustomProfile(
        id: String? = null,
        name: String,
        baseUrl: String,
        apiKey: String,
        defaultModel: String,
        description: String = ""
    ): ProviderProfile {
        val existingBuiltin = BuiltInProviders.ALL.find { it.id == id }
        if (existingBuiltin != null) {
            throw IllegalArgumentException("El perfil integrado de Codex Desktop no se puede modificar. Añade un proveedor personalizado.")
        }

        val profileId = id ?: ("custom_" + UUID.randomUUID().toString().take(8))
        val profile = ProviderProfile(
            id = profileId,
            name = name.trim(),
            baseUrl = baseUrl.trim(),
            apiKey = apiKey.trim(),
            defaultModel = defaultModel.trim(),
            isReadOnly = false,
            description = description.trim()
        )

        val customs = getCustomProfiles().toMutableList()
        val index = customs.indexOfFirst { it.id == profileId }
        if (index >= 0) {
            customs[index] = profile
        } else {
            customs.add(profile)
        }

        saveCustoms(customs)
        return profile
    }

    fun deleteCustomProfile(id: String): Boolean {
        if (BuiltInProviders.ALL.any { it.id == id }) {
            throw IllegalArgumentException("No se puede eliminar un perfil integrado del sistema.")
        }

        val customs = getCustomProfiles().toMutableList()
        val removed = customs.removeAll { it.id == id }
        if (removed) {
            saveCustoms(customs)
            if (settings.activeProfileId == id) {
                applyProfile(BuiltInProviders.PROFILE_3_CODEX_PC)
            }
        }
        return removed
    }

    private fun saveCustoms(customs: List<ProviderProfile>) {
        val arr = JSONArray()
        for (c in customs) {
            arr.put(c.toJson())
        }
        settings.customProfilesJson = arr.toString()
    }
}
