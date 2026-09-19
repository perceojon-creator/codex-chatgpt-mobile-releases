package com.codex.chat.security

import com.codex.chat.core.security.SecureKeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auditoria v1.0.79 SEC-5: Tests actualizados para el contrato fail-closed.
 * Las claves son cadena vacía cuando no hay BuildConfig override (comportamiento correcto).
 */
class SecureKeyVaultTest {

    @Test
    fun test_unmask_apinex_key_format_and_length() {
        // SEC-5: Sin BuildConfig override, la clave debe ser vacía (fail-closed).
        val key = SecureKeyVault.getApinexKey()
        assertNotNull(key)
        // En tests unitarios no hay BuildConfig con OVERRIDE_APINEX_KEY → debe ser vacía
        assertTrue("SEC-5: getApinexKey sin override debe devolver cadena vacía", key.isBlank())
    }

    @Test
    fun test_unmask_bai_key_format_and_length() {
        // SEC-5: Sin BuildConfig override, la clave debe ser vacía (fail-closed).
        val key = SecureKeyVault.getBaiKey()
        assertNotNull(key)
        assertTrue("SEC-5: getBaiKey sin override debe devolver cadena vacía", key.isBlank())
    }

    @Test
    fun test_unmask_codex_local_key() {
        // SEC-5: Sin BuildConfig override, la clave debe ser vacía (fail-closed).
        val key = SecureKeyVault.getCodexLocalKey()
        assertTrue("SEC-5: getCodexLocalKey sin override debe devolver cadena vacía", key.isBlank())
    }

    @Test
    fun test_unmask_e2b_key() {
        // SEC-5: Sin BuildConfig override, la clave debe ser vacía (fail-closed).
        val key = SecureKeyVault.getE2bDefaultKey()
        assertNotNull(key)
        assertTrue("SEC-5: getE2bDefaultKey sin override debe devolver cadena vacía", key.isBlank())
        // Nunca debe devolver la clave embarcada eliminada
        assertFalse("La clave E2B embarcada no debe aparecer en el resultado", key.startsWith("e2b_"))
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
