package com.codex.chat.security

import com.codex.chat.core.security.SecureKeyVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

/**
 * Auditoria v1.0.79 SEC-5: SecureKeyVault embarcaba 4 claves API reales
 * ofuscadas con XOR. Un atacante con jadx podia recuperarlas en minutos.
 * Este test verifica que la clase ya no contiene ningun ByteArray privado
 * que pueda ser el portador de un secreto.
 */
class SecureKeyVaultPurgeTest {

    @Test
    fun la_clase_no_declara_ningun_array_de_bytes_ofuscado() {
        val camposPrivados = SecureKeyVault::class.java.declaredFields
            .filter { Modifier.isPrivate(it.modifiers) && it.type == ByteArray::class.java }
        assertEquals(
            "SEC-5 ABIERTO: la clase sigue declarando ${camposPrivados.size} campo(s) ByteArray: " +
            camposPrivados.map { it.name },
            0,
            camposPrivados.size
        )
    }

    @Test
    fun sin_override_de_buildconfig_las_claves_son_cadena_vacia() {
        // En tests unitarios, BuildConfig.OVERRIDE_* no existe → las funciones
        // deben devolver cadena vacia, nunca un secreto embarcado.
        assertTrue(
            "getApinexKey debe devolver vacio sin override",
            SecureKeyVault.getApinexKey().isBlank()
        )
        assertTrue(
            "getBaiKey debe devolver vacio sin override",
            SecureKeyVault.getBaiKey().isBlank()
        )
        assertTrue(
            "getCodexLocalKey debe devolver vacio sin override",
            SecureKeyVault.getCodexLocalKey().isBlank()
        )
        assertTrue(
            "getE2bDefaultKey debe devolver vacio sin override",
            SecureKeyVault.getE2bDefaultKey().isBlank()
        )
    }

    @Test
    fun ninguna_funcion_publica_contiene_la_cadena_e2b_en_su_resultado() {
        val resultado = SecureKeyVault.getE2bDefaultKey()
        assertFalse(
            "La clave E2B embarcada no debe aparecer en el resultado (SEC-5 ABIERTO)",
            resultado.startsWith("e2b_")
        )
    }
}
