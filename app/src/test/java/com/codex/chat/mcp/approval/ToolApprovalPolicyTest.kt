package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ToolApprovalPolicy
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolApprovalPolicyTest {

    @Test
    fun nivel1_pide_aprobacion_para_los_cuatro_riesgos() {
        for (r in ToolRiskLevel.values()) {
            assertTrue(
                "Nivel 1 debe pedir aprobacion para $r",
                ToolApprovalPolicy.requiresApproval(r, ApprovalPolicy.ALWAYS_ASK)
            )
        }
    }

    @Test
    fun nivel3_no_pide_aprobacion_para_ningun_riesgo() {
        for (r in ToolRiskLevel.values()) {
            assertFalse(
                "Nivel 3 (acceso completo) no debe pedir nada para $r",
                ToolApprovalPolicy.requiresApproval(r, ApprovalPolicy.FULL_ACCESS)
            )
        }
    }

    @Test
    fun nivel2_solo_deja_pasar_las_seguras() {
        assertFalse(ToolApprovalPolicy.requiresApproval(
            ToolRiskLevel.SAFE, ApprovalPolicy.ASK_ON_RISK))
        assertTrue(ToolApprovalPolicy.requiresApproval(
            ToolRiskLevel.SENSITIVE, ApprovalPolicy.ASK_ON_RISK))
        assertTrue(ToolApprovalPolicy.requiresApproval(
            ToolRiskLevel.DESTRUCTIVE, ApprovalPolicy.ASK_ON_RISK))
        assertTrue(ToolApprovalPolicy.requiresApproval(
            ToolRiskLevel.ROOT, ApprovalPolicy.ASK_ON_RISK))
    }

    /** La matriz completa de 1.3, celda por celda. */
    @Test
    fun matriz_completa_celda_por_celda() {
        val esperado = mapOf(
            (ToolRiskLevel.SAFE        to ApprovalPolicy.ALWAYS_ASK)  to true,
            (ToolRiskLevel.SENSITIVE   to ApprovalPolicy.ALWAYS_ASK)  to true,
            (ToolRiskLevel.DESTRUCTIVE to ApprovalPolicy.ALWAYS_ASK)  to true,
            (ToolRiskLevel.ROOT        to ApprovalPolicy.ALWAYS_ASK)  to true,

            (ToolRiskLevel.SAFE        to ApprovalPolicy.ASK_ON_RISK) to false,
            (ToolRiskLevel.SENSITIVE   to ApprovalPolicy.ASK_ON_RISK) to true,
            (ToolRiskLevel.DESTRUCTIVE to ApprovalPolicy.ASK_ON_RISK) to true,
            (ToolRiskLevel.ROOT        to ApprovalPolicy.ASK_ON_RISK) to true,

            (ToolRiskLevel.SAFE        to ApprovalPolicy.FULL_ACCESS) to false,
            (ToolRiskLevel.SENSITIVE   to ApprovalPolicy.FULL_ACCESS) to false,
            (ToolRiskLevel.DESTRUCTIVE to ApprovalPolicy.FULL_ACCESS) to false,
            (ToolRiskLevel.ROOT        to ApprovalPolicy.FULL_ACCESS) to false
        )
        assertEquals("La matriz debe tener 12 celdas", 12, esperado.size)
        for ((par, debePedir) in esperado) {
            assertEquals(
                "Celda ${par.first} x ${par.second}",
                debePedir,
                ToolApprovalPolicy.requiresApproval(par.first, par.second)
            )
        }
    }

    @Test
    fun politica_desde_nivel_invalido_cae_en_el_mas_seguro() {
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(0))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(99))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicy.fromNivel(-1))
    }

    @Test
    fun politica_desde_nivel_valido_mapea_correcto() {
        assertEquals(ApprovalPolicy.ALWAYS_ASK,  ApprovalPolicy.fromNivel(1))
        assertEquals(ApprovalPolicy.ASK_ON_RISK, ApprovalPolicy.fromNivel(2))
        assertEquals(ApprovalPolicy.FULL_ACCESS, ApprovalPolicy.fromNivel(3))
    }

    @Test
    fun las_seis_irreversibles_estan_marcadas() {
        val esperadas = listOf(
            "send_sms", "execute_root_command", "root_write_file",
            "root_grant_permissions", "root_reboot_device", "delete_file"
        )
        for (t in esperadas) {
            assertTrue("$t debe ser irreversible",
                ToolApprovalPolicy.isIrreversible(t))
        }
        assertFalse(ToolApprovalPolicy.isIrreversible("get_battery_status"))
        assertFalse(ToolApprovalPolicy.isIrreversible("read_file"))
    }
}
