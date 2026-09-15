package com.codex.chat.security

import com.codex.chat.core.security.SecureCredentialsStore
import com.codex.chat.core.security.SecureKeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec

/**
 * Auditoría Criptográfica Profunda y Resistencia a Manipulación (AEAD AES-256-GCM).
 *
 * Evalúa 20 métodos dedicados para:
 * 1. Integridad de autenticación GCM y detección de manipulación de bits (Tamper Resistance).
 * 2. Unicidad de vectores de inicialización (IVs) y prevención de colisiones criptográficas.
 * 3. Resiliencia multihilo ante alta concurrencia de llamadas concurrentes.
 * 4. Gestión de payloads masivos, emojis, caracteres nulos y retrocompatibilidad heredada.
 * 5. Integridad de ofuscación de secretos en SecureKeyVault.
 */
class SecureCryptoAndKeyStoreDeepTest {

    private val secureStore = SecureCredentialsStore(testSecretKey = SecretKeySpec(ByteArray(32) { (it * 13 + 7).toByte() }, "AES"))

    // --- GRUPO 1: Cifrado y Descifrado Básico e Integridad ---

    @Test
    fun test_crypto_encrypt_returns_enc_prefix() {
        val plain = "sk-test-super-secret-key-12345"
        val enc = secureStore.encrypt(plain)
        assertTrue("El texto cifrado debe comenzar con 'ENC:'", enc.startsWith("ENC:"))
        assertNotEquals(plain, enc)
    }

    @Test
    fun test_crypto_decrypt_recovers_original_secret() {
        val plain = "sk-test-super-secret-key-12345"
        val enc = secureStore.encrypt(plain)
        val dec = secureStore.decrypt(enc)
        assertEquals(plain, dec)
    }

    @Test
    fun test_crypto_empty_plaintext() {
        assertEquals("", secureStore.encrypt(""))
        assertEquals("", secureStore.decrypt(""))
    }

    @Test
    fun test_crypto_unicode_emojis_and_multilingual() {
        val unicodeSecret = "🔑 Clave_Secreta_日本語_Русский_العربية_🚀_9988"
        val enc = secureStore.encrypt(unicodeSecret)
        val dec = secureStore.decrypt(enc)
        assertEquals(unicodeSecret, dec)
    }

    @Test
    fun test_crypto_massive_payload_100kb() {
        val largeBuilder = StringBuilder()
        for (i in 0 until 5000) {
            largeBuilder.append("CustomConfigItem-$i:Value=$i;\n")
        }
        val massivePlaintext = largeBuilder.toString()
        val enc = secureStore.encrypt(massivePlaintext)
        val dec = secureStore.decrypt(enc)
        assertEquals(massivePlaintext, dec)
    }

    @Test
    fun test_crypto_legacy_plaintext_migration_without_enc_prefix() {
        val legacySecret = "sk-old-unencrypted-legacy-key-9988"
        // Si no tiene prefijo ENC:, decrypt debe retornar el texto tal cual sin romper
        val dec = secureStore.decrypt(legacySecret)
        assertEquals(legacySecret, dec)
    }

    // --- GRUPO 2: Unicidad de IV y Resistencia a Manipulación (Tamper Detection) ---

    @Test
    fun test_crypto_iv_uniqueness_across_hundred_encryptions() {
        val plain = "same_constant_secret_string"
        val ivSet = Collections.synchronizedSet(mutableSetOf<String>())
        for (i in 0 until 100) {
            val enc = secureStore.encrypt(plain)
            val b64 = enc.substring("ENC:".length)
            val bytes = Base64.getDecoder().decode(b64)
            // Extraer los primeros 12 bytes (IV)
            val ivStr = Base64.getEncoder().encodeToString(bytes.copyOfRange(0, 12))
            ivSet.add(ivStr)
        }
        assertEquals("Todos los 100 IVs deben ser estrictamente únicos (cero colisiones)", 100, ivSet.size)
    }

    @Test
    fun test_crypto_tamper_ciphertext_bit_flip_fails_gracefully() {
        val plain = "critical_banking_token_abcdef123456"
        val enc = secureStore.encrypt(plain)
        val b64 = enc.substring("ENC:".length)
        val bytes = Base64.getDecoder().decode(b64)

        // Voltear 1 bit en el cuerpo del ciphertext
        val lastIdx = bytes.size - 2
        bytes[lastIdx] = (bytes[lastIdx].toInt() xor 0x01).toByte()

        val tamperedEnc = "ENC:" + Base64.getEncoder().encodeToString(bytes)
        val dec = secureStore.decrypt(tamperedEnc)

        // En AES-GCM, la alteración del tag o ciphertext falla la autenticación (retorna "")
        assertEquals("Cualquier alteración de bit debe invalidar el descifrado por integridad", "", dec)
    }

    @Test
    fun test_crypto_tamper_iv_bit_flip_fails_gracefully() {
        val plain = "critical_banking_token_abcdef123456"
        val enc = secureStore.encrypt(plain)
        val b64 = enc.substring("ENC:".length)
        val bytes = Base64.getDecoder().decode(b64)

        // Voltear 1 bit en el IV (primer byte)
        bytes[0] = (bytes[0].toInt() xor 0x01).toByte()

        val tamperedEnc = "ENC:" + Base64.getEncoder().encodeToString(bytes)
        val dec = secureStore.decrypt(tamperedEnc)
        assertEquals("", dec)
    }

    @Test
    fun test_crypto_tamper_truncated_iv_under_12_bytes() {
        val shortBytes = ByteArray(8) { 1.toByte() }
        val corruptedEnc = "ENC:" + Base64.getEncoder().encodeToString(shortBytes)
        val dec = secureStore.decrypt(corruptedEnc)
        assertEquals("", dec)
    }

    @Test
    fun test_crypto_tamper_corrupted_base64_payload() {
        val invalidB64 = "ENC:@@@ThisIsNotValidBase64@@@"
        val dec = secureStore.decrypt(invalidB64)
        assertEquals("", dec)
    }

    // --- GRUPO 3: Concurrencia Multihilo (Thread Safety Stress Test) ---

    @Test
    fun test_crypto_concurrent_encryption_thread_safety() {
        val threads = 20
        val iterationsPerThread = 50
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val results = ConcurrentHashMap<String, String>()

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until iterationsPerThread) {
                        val input = "Thread-$t-Iter-$i-Secret"
                        val enc = secureStore.encrypt(input)
                        results[enc] = input
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals(threads * iterationsPerThread, results.size)
        // Verificar que cada uno descifra correctamente
        for ((enc, orig) in results) {
            assertEquals(orig, secureStore.decrypt(enc))
        }
    }

    @Test
    fun test_crypto_concurrent_decryption_thread_safety() {
        val threads = 10
        val sampleSecret = "SharedConcurrentSecretToDecrypt"
        val encrypted = secureStore.encrypt(sampleSecret)
        val executor = Executors.newFixedThreadPool(threads)
        val latch = CountDownLatch(threads)
        val failures = AtomicBoolean(false)

        for (t in 0 until threads) {
            executor.submit {
                try {
                    for (i in 0 until 100) {
                        val dec = secureStore.decrypt(encrypted)
                        if (dec != sampleSecret) {
                            failures.set(true)
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        executor.shutdown()
        assertFalse("Ningún hilo debe obtener descifrado erróneo", failures.get())
    }

    // --- GRUPO 4: SecureKeyVault y Desofuscación ---

    @Test
    fun test_keyvault_apinex_key_valid() {
        val k = SecureKeyVault.getApinexKey()
        assertTrue(k.startsWith("sk-apx"))
        assertTrue(k.length > 20)
    }

    @Test
    fun test_keyvault_e2b_key_valid() {
        val k = SecureKeyVault.getE2bDefaultKey()
        assertTrue(k.startsWith("e2b_"))
        assertTrue(k.length > 20)
    }

    @Test
    fun test_keyvault_bai_key_valid() {
        val k = SecureKeyVault.getBaiKey()
        assertTrue(k.startsWith("sk-"))
        assertTrue(k.length > 20)
    }

    @Test
    fun test_keyvault_codex_local_key_valid() {
        val k = SecureKeyVault.getCodexLocalKey()
        assertTrue(k.isNotBlank())
    }

    @Test
    fun test_keyvault_deterministic_unmask() {
        val k1 = SecureKeyVault.getApinexKey()
        val k2 = SecureKeyVault.getApinexKey()
        assertEquals("Llamadas repetidas a getApinexKey deben retornar idéntico resultado", k1, k2)
    }

    @Test
    fun test_crypto_singleton_instance_reference_stability() {
        val s1 = SecureCredentialsStore.getInstance()
        val s2 = SecureCredentialsStore.getInstance()
        assertSame("SecureCredentialsStore.getInstance() debe retornar el mismo singleton", s1, s2)
    }
}
