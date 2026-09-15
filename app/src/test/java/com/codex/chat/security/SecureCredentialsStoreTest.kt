package com.codex.chat.security

import android.content.SharedPreferences
import com.codex.chat.core.security.SecureCredentialsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec

class SecureCredentialsStoreTest {

    private val testStore = SecureCredentialsStore(
        keyAlias = "UnitTestMasterKey",
        testSecretKey = SecretKeySpec(ByteArray(32) { (it * 7 + 13).toByte() }, "AES")
    )

    @Test
    fun test_aes_gcm_encryption_roundtrip() {
        val originalSecret = "sk-live-production-secret-token-abcdef123456"
        val encrypted = testStore.encrypt(originalSecret)

        assertNotNull(encrypted)
        assertTrue("El payload cifrado debe tener prefijo ENC:", encrypted.startsWith("ENC:"))
        assertNotEquals("El texto cifrado no debe coincidir con el texto plano", originalSecret, encrypted)

        val decrypted = testStore.decrypt(encrypted)
        assertEquals("El descifrado debe recuperar exactamente el secreto original", originalSecret, decrypted)
    }

    @Test
    fun test_different_iv_per_encryption_semantic_security() {
        val secret = "sk-static-token-to-encrypt"
        val enc1 = testStore.encrypt(secret)
        val enc2 = testStore.encrypt(secret)

        assertNotEquals("Dos cifrados sucesivos del mismo texto deben usar IVs distintos", enc1, enc2)
        assertEquals(secret, testStore.decrypt(enc1))
        assertEquals(secret, testStore.decrypt(enc2))
    }

    @Test
    fun test_legacy_unencrypted_value_transparent_passthrough() {
        val legacyValue = "sk-legacy-unencrypted-key-from-old-version"
        val result = testStore.decrypt(legacyValue)
        assertEquals("Un valor sin prefijo ENC: debe ser retornado tal cual para retrocompatibilidad", legacyValue, result)
    }

    @Test
    fun test_empty_and_blank_handling() {
        assertEquals("", testStore.encrypt(""))
        assertEquals("", testStore.decrypt(""))
    }

    @Test
    fun test_corrupted_payload_fails_safe_without_crashing() {
        val corrupted = "ENC:PayloadTotalmenteCorruptoOInvalido=="
        val result = testStore.decrypt(corrupted)
        assertEquals("Un payload corrupto debe fallar de forma segura retornando cadena vacía", "", result)
    }

    @Test
    fun test_shared_preferences_encryption_integration() {
        val memoryMap = ConcurrentHashMap<String, Any?>()
        val mockEditor = object : SharedPreferences.Editor {
            override fun putString(key: String?, value: String?): SharedPreferences.Editor {
                if (key != null) memoryMap[key] = value
                return this
            }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = this
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = this
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = this
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = this
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = this
            override fun remove(key: String?): SharedPreferences.Editor {
                if (key != null) memoryMap.remove(key)
                return this
            }
            override fun clear(): SharedPreferences.Editor {
                memoryMap.clear()
                return this
            }
            override fun commit(): Boolean = true
            override fun apply() {}
        }

        val mockPrefs = object : SharedPreferences {
            override fun getAll(): MutableMap<String, *> = memoryMap
            override fun getString(key: String?, defValue: String?): String? = (memoryMap[key] as? String) ?: defValue
            override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
            override fun getInt(key: String?, defValue: Int): Int = defValue
            override fun getLong(key: String?, defValue: Long): Long = defValue
            override fun getFloat(key: String?, defValue: Float): Float = defValue
            override fun getBoolean(key: String?, defValue: Boolean): Boolean = defValue
            override fun contains(key: String?): Boolean = memoryMap.containsKey(key)
            override fun edit(): SharedPreferences.Editor = mockEditor
            override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
            override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        }

        val testKey = "api_key"
        val secretValue = "sk-user-custom-secret-key-999"

        testStore.putEncryptedString(mockPrefs, testKey, secretValue)

        // Comprobar que en el mapa de memoria crudo (SharedPreferences físico) NO está en texto plano
        val rawStored = mockPrefs.getString(testKey, null)
        assertNotNull(rawStored)
        assertTrue(rawStored!!.startsWith("ENC:"))
        assertFalse(rawStored.contains(secretValue))

        // Comprobar que al leerlo mediante el Store se descifra limpiamente
        val recovered = testStore.getEncryptedString(mockPrefs, testKey, "default")
        assertEquals(secretValue, recovered)
    }
}
