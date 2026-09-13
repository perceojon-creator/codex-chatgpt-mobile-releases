package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ApprovalPolicy
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsApprovalPolicyTest {

    object ApprovalPolicyStore {
        fun leer(nivelGuardado: Int): ApprovalPolicy =
            ApprovalPolicy.fromNivel(nivelGuardado)
        fun escribir(p: ApprovalPolicy): Int = p.nivel
    }

    @Test
    fun defecto_es_nivel1_always_ask() {
        val porDefecto = ApprovalPolicyStore.leer(1)
        assertEquals(ApprovalPolicy.ALWAYS_ASK, porDefecto)
        assertEquals("Solicitar aprobacion", porDefecto.etiqueta)
    }

    @Test
    fun round_trip_para_los_tres_niveles() {
        for (policy in ApprovalPolicy.values()) {
            val nivelSerializado = ApprovalPolicyStore.escribir(policy)
            val deserializado = ApprovalPolicyStore.leer(nivelSerializado)
            assertEquals(policy, deserializado)
        }
    }

    @Test
    fun nivel_corrupto_o_invalido_devuelve_defecto_seguro() {
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicyStore.leer(-1))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicyStore.leer(0))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicyStore.leer(4))
        assertEquals(ApprovalPolicy.ALWAYS_ASK, ApprovalPolicyStore.leer(999))
    }
}
