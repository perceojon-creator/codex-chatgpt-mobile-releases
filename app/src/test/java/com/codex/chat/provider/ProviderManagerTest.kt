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
    fun verificar_perfil_1_apinex_hardcodeado_y_solo_lectura() {
        val p1 = BuiltInProviders.PROFILE_1_APINEX
        assertEquals("builtin_apinex_free", p1.id)
        assertEquals("https://api.apinex.bond/v1", p1.baseUrl)
        assertEquals("sk-apx2cd4566ea72f06ff794d50efb60e76b3775365d02ba702e", p1.apiKey)
        assertEquals("free/deepseek-v4.1-flash", p1.defaultModel)
        assertTrue("El perfil 1 debe ser inmutable / solo lectura", p1.isReadOnly)
    }

    @Test
    fun verificar_perfil_2_bai_hardcodeado_y_solo_lectura() {
        val p2 = BuiltInProviders.PROFILE_2_BAI
        assertEquals("builtin_b_ai", p2.id)
        assertEquals("https://api.b.ai/v1", p2.baseUrl)
        assertEquals("sk-186pywopfvf7i1yh06tn498dmpgvvoc4", p2.apiKey)
        assertEquals("glm-5.3-flash", p2.defaultModel)
        assertTrue("El perfil 2 debe ser inmutable / solo lectura", p2.isReadOnly)
    }

    @Test
    fun verificar_perfil_3_codex_pc_hardcodeado_y_solo_lectura() {
        val p3 = BuiltInProviders.PROFILE_3_CODEX_PC
        assertEquals("builtin_codex_pc", p3.id)
        assertEquals("sk-cpa-9f0357f2aad7ba80c349d81907f29b9fa65fa5b9bb76b74d632f0bcfe3e9341f", p3.apiKey)
        assertEquals("gpt-5.6-sol", p3.defaultModel)
        assertTrue("El perfil 3 debe ser inmutable / solo lectura", p3.isReadOnly)
    }

    @Test
    fun exactamente_3_perfiles_integrados_del_sistema() {
        assertEquals(3, BuiltInProviders.ALL.size)
        val ids = BuiltInProviders.ALL.map { it.id }.toSet()
        assertEquals(3, ids.size)
        assertTrue(ids.contains("builtin_apinex_free"))
        assertTrue(ids.contains("builtin_b_ai"))
        assertTrue(ids.contains("builtin_codex_pc"))
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

        // 1. Inicialmente solo existen los 3 perfiles integrados
        assertEquals(3, providerManager.getAllProfiles().size)

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

        // 3. Ahora la lista tiene 4 perfiles
        val allAfterAdd = providerManager.getAllProfiles()
        assertEquals(4, allAfterAdd.size)
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
        assertEquals(3, allAfterDelete.size)
        assertEquals(BuiltInProviders.PROFILE_3_CODEX_PC.id, settings.activeProfileId)
    }
}
