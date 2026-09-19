package com.codex.chat.service

import com.codex.chat.core.service.NotificationRedactor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auditoria v1.0.79 SEC-4: verificacion del redactor de notificaciones.
 */
class NotificationRedactorTest {

    @Test
    fun redacta_un_otp_precedido_de_codigo() {
        val texto = "Tu codigo de verificacion es 123456. No lo compartas."
        val resultado = NotificationRedactor.redact(texto)
        assertFalse("El OTP no debe aparecer en el resultado", resultado.contains("123456"))
        assertTrue(resultado.contains("[REDACTADO]"))
    }

    @Test
    fun redacta_un_otp_de_6_digitos_al_inicio_del_mensaje() {
        val texto = "748392 es tu codigo de Google"
        val resultado = NotificationRedactor.redact(texto)
        assertFalse(resultado.contains("748392"))
        assertTrue(resultado.contains("[REDACTADO]"))
    }

    @Test
    fun no_redacta_numeros_de_telefono_largos() {
        val texto = "Llamada de +34612345678"
        val resultado = NotificationRedactor.redact(texto)
        assertEquals("Los numeros de telefono no deben redactarse", texto, resultado)
    }

    @Test
    fun no_redacta_texto_sin_codigos() {
        val texto = "Maria te ha enviado una foto"
        assertEquals(texto, NotificationRedactor.redact(texto))
    }

    @Test
    fun google_authenticator_esta_en_la_lista_negra() {
        assertTrue(NotificationRedactor.isPackageBlocked("com.google.android.apps.authenticator2"))
    }

    @Test
    fun santander_esta_en_la_lista_negra() {
        assertTrue(NotificationRedactor.isPackageBlocked("es.bancosantander.apps"))
    }

    @Test
    fun una_app_de_musica_no_esta_en_la_lista_negra() {
        assertFalse(NotificationRedactor.isPackageBlocked("com.spotify.music"))
    }

    @Test
    fun la_verificacion_es_insensible_a_mayusculas_de_paquete() {
        assertTrue(NotificationRedactor.isPackageBlocked("COM.GOOGLE.ANDROID.APPS.AUTHENTICATOR2"))
    }

    @Test
    fun redacta_pin_de_banco() {
        val texto = "Pin: 4521 para aprobar tu transferencia"
        val resultado = NotificationRedactor.redact(texto)
        assertFalse(resultado.contains("4521"))
    }

    @Test
    fun texto_vacio_devuelve_texto_vacio() {
        assertEquals("", NotificationRedactor.redact(""))
    }
}
