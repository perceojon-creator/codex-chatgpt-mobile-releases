package com.codex.chat.provider

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import com.codex.chat.SettingsManager
import com.codex.chat.core.provider.BuiltInProviders
import com.codex.chat.core.provider.ProviderManager
import com.codex.chat.core.provider.ProviderProfile
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy

class ProviderManagerTest {

    @Test
    fun verificar_perfil_codex_pc_unico_integrado_y_solo_lectura() {
        val p = BuiltInProviders.PROFILE_CODEX_PC
        assertEquals("builtin_codex_pc", p.id)
        assertEquals("proxy-pool", p.apiKey)
        assertEquals("gpt-5.6-sol", p.defaultModel)
        assertTrue("El perfil de Codex Desktop debe ser inmutable / solo lectura", p.isReadOnly)
    }

    @Test
    fun exactamente_1_perfil_integrado_codex_desktop() {
        assertEquals(1, BuiltInProviders.ALL.size)
        assertEquals("builtin_codex_pc", BuiltInProviders.ALL[0].id)
    }

    @Test
    fun serializacion_y_deserializacion_de_proveedor_custom() {
        val custom = ProviderProfile(
            id = "custom_test_123",
            name = "Mi Servidor Local vLLM",
            baseUrl = "http://192.168.1.100:8000/v1",
            apiKey = "sk-custom-secret",
            defaultModel = "qwen-2.5-72b",
            isReadOnly = false,
            description = "Servidor GPU en casa"
        )
        val json = custom.toJson()
        val parsed = ProviderProfile.fromJson(json)

        assertEquals(custom.id, parsed.id)
        assertEquals(custom.name, parsed.name)
        assertEquals(custom.baseUrl, parsed.baseUrl)
        assertEquals(custom.apiKey, parsed.apiKey)
        assertEquals(custom.defaultModel, parsed.defaultModel)
        assertFalse(parsed.isReadOnly)
        assertEquals(custom.description, parsed.description)
    }

    private class FakePrefsContext(private val prefs: SharedPreferences) : ContextWrapper(null) {
        override fun getSharedPreferences(name: String?, mode: Int): SharedPreferences = prefs
    }

    @Test
    fun flujo_completo_anadir_seleccionar_y_eliminar_proveedores_custom() {
        val storage = mutableMapOf<String, Any?>()
        lateinit var editorRef: SharedPreferences.Editor
        val editorProxy = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java)
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    storage[args[0] as String] = args[1]
                    editorRef
                }
                "putInt" -> {
                    storage[args[0] as String] = args[1]
                    editorRef
                }
                "apply", "commit" -> null
                else -> null
            }
        } as SharedPreferences.Editor
        editorRef = editorProxy

        val prefsProxy = Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,
            arrayOf(SharedPreferences::class.java)
        ) { _, method, args ->
            when (method.name) {
                "getString" -> storage[args[0] as String] ?: args[1]
                "getInt" -> storage[args[0] as String] ?: args[1]
                "edit" -> editorProxy
                else -> null
            }
        } as SharedPreferences

        val fakeContext = FakePrefsContext(prefsProxy)
        val settings = SettingsManager(fakeContext)
        val providerManager = ProviderManager(settings)

        // 1. Inicialmente solo existe el perfil integrado de Codex PC
        assertEquals(1, providerManager.getAllProfiles().size)

        // 2. Añadir un proveedor custom (ej. OpenRouter)
        val openRouter = providerManager.saveCustomProfile(
            name = "OpenRouter AI",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-v1-my-secret-key-12345",
            defaultModel = "anthropic/claude-3.5-sonnet",
            description = "Claude 3.5 y DeepSeek V3"
        )
        assertNotNull(openRouter.id)
        assertFalse(openRouter.isReadOnly)

        // 3. Ahora la lista tiene 2 perfiles
        val allAfterAdd = providerManager.getAllProfiles()
        assertEquals(2, allAfterAdd.size)
        assertTrue(allAfterAdd.any { it.id == openRouter.id })

        // 4. Activar el proveedor custom
        providerManager.applyProfile(openRouter)
        assertEquals(openRouter.id, settings.activeProfileId)
        assertEquals("https://openrouter.ai/api/v1", settings.baseUrl)
        assertEquals("sk-or-v1-my-secret-key-12345", settings.apiKey)
        assertEquals("anthropic/claude-3.5-sonnet", settings.selectedModelId)

        // 5. Eliminar el proveedor custom y verificar que hace fallback seguro a Codex PC
        providerManager.deleteCustomProfile(openRouter.id)
        val allAfterDelete = providerManager.getAllProfiles()
        assertEquals(1, allAfterDelete.size)
        assertEquals(BuiltInProviders.PROFILE_CODEX_PC.id, settings.activeProfileId)
    }
}
