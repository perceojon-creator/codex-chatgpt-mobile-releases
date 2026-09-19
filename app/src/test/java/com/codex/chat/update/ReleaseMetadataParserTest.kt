package com.codex.chat.update

import com.codex.chat.core.update.ReleaseMetadataParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ReleaseMetadataParserTest {

    private val cuerpoRealDeRelease = """
        v1.0.80: Correcciones de seguridad y verificacion OTA.

        ## Artefacto
        apkName: Codex-ChatGPT-Mobile.apk
        versionCode: 81
        sizeBytes: 3488031
        SHA-256: ebfed198cdadf3b22f5310e66f6a38bbc20e5b9aadafdc106a03cccba2b8e06b
    """.trimIndent()

    @Test
    fun extrae_el_sha256_de_un_cuerpo_de_release_real() {
        assertEquals(
            "ebfed198cdadf3b22f5310e66f6a38bbc20e5b9aadafdc106a03cccba2b8e06b",
            ReleaseMetadataParser.extractSha256(cuerpoRealDeRelease)
        )
    }

    @Test
    fun extrae_el_version_code_de_un_cuerpo_de_release_real() {
        assertEquals(81, ReleaseMetadataParser.extractVersionCode(cuerpoRealDeRelease))
    }

    @Test
    fun normaliza_el_sha256_a_minusculas() {
        val cuerpo = "SHA-256: EBFED198CDADF3B22F5310E66F6A38BBC20E5B9AADAFDC106A03CCCBA2B8E06B"
        assertEquals(
            "ebfed198cdadf3b22f5310e66f6a38bbc20e5b9aadafdc106a03cccba2b8e06b",
            ReleaseMetadataParser.extractSha256(cuerpo)
        )
    }

    @Test
    fun acepta_las_variantes_de_escritura_del_campo() {
        val esperado = "a".repeat(64)
        assertEquals(esperado, ReleaseMetadataParser.extractSha256("sha256: " + esperado))
        assertEquals(esperado, ReleaseMetadataParser.extractSha256("SHA-256 = " + esperado))
        assertEquals(esperado, ReleaseMetadataParser.extractSha256("Sha256:   " + esperado))
    }

    @Test
    fun devuelve_null_cuando_no_hay_sha256() {
        assertNull(ReleaseMetadataParser.extractSha256("Notas de version sin metadatos."))
        assertNull(ReleaseMetadataParser.extractSha256(""))
    }

    @Test
    fun rechaza_un_hash_de_longitud_incorrecta() {
        assertNull(ReleaseMetadataParser.extractSha256("SHA-256: " + "a".repeat(63)))
        assertNull(ReleaseMetadataParser.extractSha256("SHA-256: " + "a".repeat(65)))
    }

    @Test
    fun rechaza_un_hash_con_caracteres_no_hexadecimales() {
        assertNull(ReleaseMetadataParser.extractSha256("SHA-256: " + "z".repeat(64)))
    }

    @Test
    fun devuelve_null_cuando_no_hay_version_code() {
        assertNull(ReleaseMetadataParser.extractVersionCode("Notas sin version code."))
    }

    @Test
    fun acepta_version_code_con_guion_bajo_o_espacio() {
        assertEquals(81, ReleaseMetadataParser.extractVersionCode("version_code: 81"))
        assertEquals(81, ReleaseMetadataParser.extractVersionCode("version code = 81"))
        assertEquals(81, ReleaseMetadataParser.extractVersionCode("versionCode:81"))
    }
}
