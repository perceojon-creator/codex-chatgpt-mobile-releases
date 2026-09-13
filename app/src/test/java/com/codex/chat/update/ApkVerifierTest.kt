package com.codex.chat.update

import com.codex.chat.core.update.ApkVerifier
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ApkVerifierTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun ficheroCon(bytes: ByteArray): File {
        val f = tmp.newFile("test.apk")
        f.writeBytes(bytes)
        return f
    }

    @Test
    fun el_hash_de_contenido_conocido_es_el_esperado() {
        // SHA-256 de la cadena vacia
        val f = ficheroCon(ByteArray(0))
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            ApkVerifier.sha256(f)
        )
    }

    @Test
    fun el_mismo_contenido_da_el_mismo_hash() {
        val a = ficheroCon("contenido".toByteArray())
        val b = tmp.newFile("otro.apk").apply { writeBytes("contenido".toByteArray()) }
        assertEquals(ApkVerifier.sha256(a), ApkVerifier.sha256(b))
    }

    @Test
    fun un_solo_byte_distinto_cambia_el_hash() {
        val a = ficheroCon("contenido".toByteArray())
        val b = tmp.newFile("b.apk").apply { writeBytes("contenidoX".toByteArray()) }
        assertNotEquals(ApkVerifier.sha256(a), ApkVerifier.sha256(b))
    }

    @Test
    fun coincide_acepta_mayusculas_y_minusculas() {
        val h = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertTrue(ApkVerifier.coincide(h, h.uppercase()))
        assertTrue(ApkVerifier.coincide(h.uppercase(), h))
    }

    @Test
    fun coincide_rechaza_hash_vacio_o_de_longitud_incorrecta() {
        val h = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertFalse("Un hash vacio nunca puede validar", ApkVerifier.coincide("", h))
        assertFalse(ApkVerifier.coincide(h, ""))
        assertFalse(ApkVerifier.coincide("abc", h))
        assertFalse(ApkVerifier.coincide(h, h.dropLast(1)))
        assertFalse(ApkVerifier.coincide(h, h + "0"))
    }

    @Test
    fun coincide_rechaza_hashes_distintos() {
        assertFalse(ApkVerifier.coincide(
            "0000000000000000000000000000000000000000000000000000000000000000",
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        ))
    }

    @Test
    fun un_apk_manipulado_no_valida_contra_el_hash_original() {
        val original  = ficheroCon("APK-ORIGINAL-BUENO".toByteArray())
        val esperado  = ApkVerifier.sha256(original)
        val malicioso = tmp.newFile("mitm.apk")
                           .apply { writeBytes("APK-DEL-ATACANTE".toByteArray()) }
        assertFalse(
            "Un APK sustituido por MITM debe ser rechazado",
            ApkVerifier.coincide(esperado, ApkVerifier.sha256(malicioso))
        )
    }

    @Test
    fun hashing_de_fichero_grande_que_supera_tamano_de_buffer() {
        // Test large payload exceeding the 8192-byte read buffer boundary
        val largeData = ByteArray(65536) { (it % 256).toByte() }
        val f = ficheroCon(largeData)
        val hash = ApkVerifier.sha256(f)
        assertEquals(64, hash.length)
        assertTrue(ApkVerifier.coincide(hash, hash))

        // Single byte change at boundary
        largeData[8192] = (largeData[8192] + 1).toByte()
        val fModified = tmp.newFile("large_mod.apk").apply { writeBytes(largeData) }
        val hashMod = ApkVerifier.sha256(fModified)
        assertNotEquals(hash, hashMod)
    }
}
