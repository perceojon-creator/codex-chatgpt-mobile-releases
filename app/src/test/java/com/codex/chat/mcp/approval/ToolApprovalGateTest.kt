package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class ToolApprovalGateTest {

    private fun gate(
        viva: Boolean = true,
        enPrincipal: Boolean = false,
        timeout: Long = 2,
        renderer: (ApprovalRequest, (ApprovalDecision) -> Unit) -> Unit
    ) = ToolApprovalGate(
        enHiloUi        = { r -> Thread(r).start() },   // simula post a UI
        actividadViva   = { viva },
        enHiloPrincipal = { enPrincipal },
        timeoutSegundos = timeout,
        dialogRenderer  = renderer
    )

    private fun req(
        tool: String = "read_file",
        risk: ToolRiskLevel = ToolRiskLevel.SENSITIVE
    ) = ApprovalRequest(tool, "{}", risk, "Servidor Test")

    @Test
    fun nivel3_aprueba_sin_abrir_dialogo() {
        var abierto = false
        val g = gate { _, cb -> abierto = true; cb(ApprovalDecision.APPROVED) }
        val d = g.decide(req(risk = ToolRiskLevel.ROOT), ApprovalPolicy.FULL_ACCESS)
        assertEquals(ApprovalDecision.APPROVED, d)
        assertFalse("Nivel 3 no debe abrir dialogo nunca", abierto)
    }

    @Test
    fun nivel2_no_abre_dialogo_para_herramienta_segura() {
        var abierto = false
        val g = gate { _, cb -> abierto = true; cb(ApprovalDecision.APPROVED) }
        val d = g.decide(req("get_battery_status", ToolRiskLevel.SAFE), ApprovalPolicy.ASK_ON_RISK)
        assertEquals(ApprovalDecision.APPROVED, d)
        assertFalse(abierto)
    }

    @Test
    fun nivel2_si_abre_dialogo_para_root() {
        var abierto = false
        val g = gate { _, cb -> abierto = true; cb(ApprovalDecision.DENIED) }
        val d = g.decide(req("execute_root_command", ToolRiskLevel.ROOT), ApprovalPolicy.ASK_ON_RISK)
        assertTrue("Root debe abrir dialogo en nivel 2", abierto)
        assertEquals(ApprovalDecision.DENIED, d)
    }

    @Test
    fun el_hilo_llamante_se_bloquea_hasta_que_el_usuario_responde() {
        val renderizado = CountDownLatch(1)
        var callbackGuardado: ((ApprovalDecision) -> Unit)? = null

        val g = gate { _, cb ->
            callbackGuardado = cb
            renderizado.countDown()
            // deliberadamente NO llamamos cb todavia
        }

        val resultado = AtomicReference<ApprovalDecision>()
        val hilo = Thread {
            resultado.set(g.decide(req(), ApprovalPolicy.ALWAYS_ASK))
        }
        hilo.start()

        assertTrue("El dialogo deberia haberse pedido",
            renderizado.await(2, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertNull("El hilo no puede haber terminado sin respuesta",
            resultado.get())
        assertTrue("El hilo debe estar bloqueado", hilo.isAlive)

        callbackGuardado!!(ApprovalDecision.APPROVED)
        hilo.join(2000)

        assertEquals(ApprovalDecision.APPROVED, resultado.get())
        assertFalse(hilo.isAlive)
    }

    @Test
    fun sin_respuesta_devuelve_TIMEOUT_y_no_APPROVED() {
        val g = gate(timeout = 1) { _, _ -> /* nunca responde */ }
        val inicio = System.currentTimeMillis()
        val d = g.decide(req(), ApprovalPolicy.ALWAYS_ASK)
        val transcurrido = System.currentTimeMillis() - inicio

        assertEquals(ApprovalDecision.TIMEOUT, d)
        assertTrue("Debe haber esperado ~1 s, espero $transcurrido ms",
            transcurrido >= 950)
        assertNotEquals("Un timeout jamas puede aprobar",
            ApprovalDecision.APPROVED, d)
    }

    @Test
    fun actividad_destruida_deniega_sin_colgarse() {
        val g = gate(viva = false, timeout = 5) { _, cb ->
            fail("No debe renderizarse el dialogo con la actividad muerta")
        }
        val inicio = System.currentTimeMillis()
        val d = g.decide(req(), ApprovalPolicy.ALWAYS_ASK)
        val transcurrido = System.currentTimeMillis() - inicio

        assertEquals(ApprovalDecision.DENIED, d)
        assertTrue("Debe resolver rapido, no esperar al timeout",
            transcurrido < 2000)
    }

    @Test
    fun permitir_en_sesion_evita_el_segundo_dialogo() {
        var veces = 0
        val g = gate { _, cb -> veces++; cb(ApprovalDecision.APPROVED_SESSION) }

        val d1 = g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK)
        val d2 = g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK)
        val d3 = g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK)

        assertEquals(ApprovalDecision.APPROVED_SESSION, d1)
        assertEquals(ApprovalDecision.APPROVED, d2)
        assertEquals(ApprovalDecision.APPROVED, d3)
        assertEquals("Solo debe preguntarse una vez", 1, veces)
    }

    @Test
    fun permitir_una_vez_NO_recuerda() {
        var veces = 0
        val g = gate { _, cb -> veces++; cb(ApprovalDecision.APPROVED) }
        g.decide(req("write_file"), ApprovalPolicy.ALWAYS_ASK)
        g.decide(req("write_file"), ApprovalPolicy.ALWAYS_ASK)
        assertEquals("'Una vez' debe preguntar cada vez", 2, veces)
    }

    @Test
    fun permitir_en_esta_sesion_NO_se_aplica_a_herramientas_irreversibles() {
        var vecesAbierto = 0
        val g = gate { _, cb ->
            vecesAbierto++
            cb(ApprovalDecision.APPROVED_SESSION)
        }

        g.decide(req("send_sms", ToolRiskLevel.DESTRUCTIVE), ApprovalPolicy.ALWAYS_ASK)
        g.decide(req("send_sms", ToolRiskLevel.DESTRUCTIVE), ApprovalPolicy.ALWAYS_ASK)

        assertEquals("Herramientas irreversibles deben preguntar cada vez",
            2, vecesAbierto)
    }

    @Test
    fun la_allowlist_no_se_filtra_entre_herramientas_distintas() {
        val preguntadas = mutableListOf<String>()
        val g = gate { r, cb ->
            preguntadas.add(r.toolName); cb(ApprovalDecision.APPROVED_SESSION)
        }
        g.decide(req("read_file"),  ApprovalPolicy.ALWAYS_ASK)
        g.decide(req("write_file"), ApprovalPolicy.ALWAYS_ASK)
        g.decide(req("read_file"),  ApprovalPolicy.ALWAYS_ASK)

        assertEquals(listOf("read_file", "write_file"), preguntadas)
        assertEquals(setOf("read_file", "write_file"), g.allowlistActual())
    }

    @Test
    fun limpiar_allowlist_vuelve_a_preguntar() {
        var veces = 0
        val g = gate { _, cb -> veces++; cb(ApprovalDecision.APPROVED_SESSION) }
        g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK)
        assertEquals(setOf("read_file"), g.allowlistActual())
        g.limpiarAllowlist()
        assertTrue("La allowlist debe quedar vacia tras limpiarla", g.allowlistActual().isEmpty())
        g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK)
        assertEquals("Tras limpiar debe volver a preguntar", 2, veces)
    }

    @Test(expected = IllegalStateException::class)
    fun llamar_desde_el_hilo_principal_lanza_en_vez_de_bloquear() {
        val g = gate(enPrincipal = true) { _, cb -> cb(ApprovalDecision.APPROVED) }
        g.decide(req(), ApprovalPolicy.ALWAYS_ASK)
    }

    @Test
    fun decisiones_concurrentes_sobre_la_misma_herramienta_no_corrompen() {
        val g = gate { _, cb -> cb(ApprovalDecision.APPROVED_SESSION) }
        val hilos = (1..8).map {
            Thread { g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK) }
        }
        hilos.forEach { it.start() }
        hilos.forEach { it.join(3000) }
        assertEquals(setOf("read_file"), g.allowlistActual())
    }

    @Test
    fun excepcion_en_dialogRenderer_resuelve_a_DENIED_sin_colgarse() {
        val g = gate(timeout = 5) { _, _ ->
            throw RuntimeException("Fallo de renderizado simulado")
        }
        val d = g.decide(req("read_file"), ApprovalPolicy.ALWAYS_ASK)
        assertEquals(ApprovalDecision.DENIED, d)
    }
}
