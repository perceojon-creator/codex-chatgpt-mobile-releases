package com.codex.chat.connector

import com.codex.chat.core.connector.FlowCreditsResponse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Auditoria v1.0.79 Fase 3: verifica las correcciones en FlowCreditsResponse.
 *
 * Errores corregidos:
 * 1. rem > 0.0 reemplazado por rem >= 0.0 (cero creditos era un valor legitimo que
 *    se descartaba, retornando el default 1050.0 como si hubiera creditos).
 * 2. "perceojon@gmail.com" eliminado de los defaults del modelo y del UI —
 *    la cuenta ahora es cadena vacia cuando el servidor no la proporciona.
 */
class FlowCreditsDefaultsTest {

    @Test
    fun el_account_por_defecto_es_cadena_vacia() {
        val resp = FlowCreditsResponse()
        assertEquals(
            "El account por defecto no debe ser una cuenta personal hardcodeada",
            "",
            resp.account
        )
    }

    @Test
    fun cero_creditos_restantes_no_se_descarta_por_la_comprobacion_de_cota() {
        // Antes: if (rem > 0.0) rem else 1050.0 descartaba cero creditos reales.
        // Ahora: if (rem >= 0.0) rem else 1050.0 acepta cero como valor legitimo.
        val resp = FlowCreditsResponse(creditsRemaining = 0.0)
        assertEquals(
            "Cero creditos restantes es un valor legitimo y no debe retornar el default",
            0.0,
            resp.creditsRemaining,
            0.001
        )
    }

    @Test
    fun cero_creditos_totales_no_se_descarta() {
        val resp = FlowCreditsResponse(creditsTotal = 0.0)
        assertEquals(0.0, resp.creditsTotal, 0.001)
    }

    @Test
    fun cero_creditos_diarios_no_se_descarta() {
        val resp = FlowCreditsResponse(dailyCredits = 0.0)
        assertEquals(0.0, resp.dailyCredits, 0.001)
    }

    @Test
    fun cero_creditos_de_plan_no_se_descarta() {
        val resp = FlowCreditsResponse(planCredits = 0.0)
        assertEquals(0.0, resp.planCredits, 0.001)
    }

    @Test
    fun valores_positivos_se_mantienen_sin_cambio() {
        val resp = FlowCreditsResponse(
            creditsRemaining = 742.5,
            creditsTotal = 1050.0,
            dailyCredits = 50.0,
            planCredits = 1000.0,
            account = "usuario@example.com"
        )
        assertEquals(742.5, resp.creditsRemaining, 0.001)
        assertEquals(1050.0, resp.creditsTotal, 0.001)
        assertEquals("usuario@example.com", resp.account)
    }

    @Test
    fun el_estado_inicial_no_contiene_referencia_a_cuentas_privadas() {
        val resp = FlowCreditsResponse()
        assertFalse(
            "El modelo no debe contener emails privados hardcodeados",
            resp.account.contains("@gmail.com")
        )
    }
}
