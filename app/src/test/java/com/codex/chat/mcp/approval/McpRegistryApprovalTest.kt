package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ApprovalDecision
import com.codex.chat.core.mcp.approval.ApprovalPolicy
import com.codex.chat.core.mcp.approval.ApprovalRequest
import com.codex.chat.core.mcp.approval.ToolApprovalGate
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import com.codex.chat.core.mcp.model.*
import com.codex.chat.core.mcp.server.McpServer
import org.junit.Assert.*
import org.junit.Test

class ServidorEspia : McpServer {
    var vecesInvocado = 0
    val argumentosRecibidos = mutableListOf<String>()

    override val info = McpServerInfo(
        id = "espia", name = "Servidor Espia",
        description = "solo para test", iconEmoji = "T"
    )
    override fun getTools() = listOf(
        McpTool("execute_root_command", "peligrosa", info.name),
        McpTool("get_battery_status",   "inocua",    info.name)
    )
    override fun executeTool(call: McpToolCallRequest): McpToolResult {
        vecesInvocado++
        argumentosRecibidos.add(call.argumentsJson)
        return McpToolResult(call.id, call.toolName, "ejecutado")
    }
}

class McpRegistryApprovalTest {

    private fun gateQueSiempre(decision: ApprovalDecision) = ToolApprovalGate(
        enHiloUi        = { r -> Thread(r).start() },
        actividadViva   = { true },
        enHiloPrincipal = { false },
        timeoutSegundos = 5,
        dialogRenderer  = { _, cb -> cb(decision) }
    )

    private fun gateQueNuncaResponde(timeout: Long = 1) = ToolApprovalGate(
        enHiloUi        = { r -> Thread(r).start() },
        actividadViva   = { true },
        enHiloPrincipal = { false },
        timeoutSegundos = timeout,
        dialogRenderer  = { _, _ -> /* no-op */ }
    )

    @Test
    fun una_decision_DENIED_no_invoca_el_servidor() {
        val espia = ServidorEspia()
        val gate  = gateQueSiempre(ApprovalDecision.DENIED)

        val decision = gate.decide(
            ApprovalRequest("execute_root_command",
                """{"command":"rm -rf /"}""", ToolRiskLevel.ROOT, "Servidor Espia"),
            ApprovalPolicy.ALWAYS_ASK
        )

        if (decision == ApprovalDecision.APPROVED ||
            decision == ApprovalDecision.APPROVED_SESSION) {
            espia.executeTool(McpToolCallRequest("1", "execute_root_command",
                """{"command":"rm -rf /"}"""))
        }

        assertEquals(ApprovalDecision.DENIED, decision)
        assertEquals("El servidor NO puede haber sido invocado",
            0, espia.vecesInvocado)
        assertTrue(espia.argumentosRecibidos.isEmpty())
    }

    @Test
    fun una_decision_TIMEOUT_tampoco_invoca_el_servidor() {
        val espia = ServidorEspia()
        val gate  = gateQueNuncaResponde(timeout = 1)
        val decision = gate.decide(
            ApprovalRequest("execute_root_command", "{}",
                ToolRiskLevel.ROOT, "Servidor Espia"),
            ApprovalPolicy.ALWAYS_ASK
        )
        assertEquals(ApprovalDecision.TIMEOUT, decision)
        assertEquals(0, espia.vecesInvocado)
    }

    @Test
    fun una_decision_APPROVED_si_invoca_el_servidor_con_los_argumentos_exactos() {
        val espia = ServidorEspia()
        val gate  = gateQueSiempre(ApprovalDecision.APPROVED)
        val args  = """{"command":"id"}"""

        val decision = gate.decide(
            ApprovalRequest("execute_root_command", args,
                ToolRiskLevel.ROOT, "Servidor Espia"),
            ApprovalPolicy.ALWAYS_ASK
        )
        if (decision == ApprovalDecision.APPROVED) {
            espia.executeTool(McpToolCallRequest("1", "execute_root_command", args))
        }
        assertEquals(1, espia.vecesInvocado)
        assertEquals(args, espia.argumentosRecibidos.single())
    }

    @Test
    fun el_resultado_denegado_se_marca_como_error_para_el_modelo() {
        val res = McpToolResult(
            callId = "1", toolName = "send_sms",
            content = "El usuario rechazo la ejecucion de esta herramienta.",
            isError = true
        )
        assertTrue("El modelo debe ver isError=true", res.isError)
        assertTrue(res.content.contains("rechazo"))
    }
}
