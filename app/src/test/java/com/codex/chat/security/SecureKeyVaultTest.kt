package com.codex.chat.security

import com.codex.chat.core.security.SecureKeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecureKeyVaultTest {

    @Test
    fun test_unmask_apinex_key_format_and_length() {
        val key = SecureKeyVault.getApinexKey()
        assertNotNull(key)
        assertTrue("La clave APInex debe comenzar con prefijo sk-apx", key.startsWith("sk-apx"))
        assertEquals("La longitud de la clave APInex debe ser de 53 caracteres", 53, key.length)
    }

    @Test
    fun test_unmask_bai_key_format_and_length() {
        val key = SecureKeyVault.getBaiKey()
        assertNotNull(key)
        assertTrue("La clave B.AI debe comenzar con prefijo sk-", key.startsWith("sk-"))
        assertEquals("La longitud de la clave B.AI debe ser de 35 caracteres", 35, key.length)
    }

    @Test
    fun test_unmask_codex_local_key() {
        val key = SecureKeyVault.getCodexLocalKey()
        assertEquals("sk-cpa-9f0357f2aad7ba80c349d81907f29b9fa65fa5b9bb76b74d632f0bcfe3e9341f", key)
    }

    @Test
    fun test_unmask_e2b_key() {
        val key = SecureKeyVault.getE2bDefaultKey()
        assertNotNull(key)
        assertTrue("La clave E2B debe comenzar con e2b_", key.startsWith("e2b_"))
        assertEquals("La longitud de la clave E2B debe ser de 44 caracteres", 44, key.length)
    }

    @Test
    fun test_no_plaintext_secrets_in_vault_fields() {
        val fields = SecureKeyVault::class.java.declaredFields
        for (f in fields) {
            f.isAccessible = true
            val value = f.get(SecureKeyVault)
            if (value is String) {
                assertFalse("Ningún campo del Vault debe contener sk- en texto plano", value.startsWith("sk-"))
                assertFalse("Ningún campo del Vault debe contener e2b_ en texto plano", value.startsWith("e2b_"))
            }
        }
    }
}
