package com.codex.chat.swarm

import com.codex.chat.core.mcp.taint.TaintOrigin
import com.codex.chat.core.swarm.model.SwarmRole
import com.codex.chat.core.swarm.model.SwarmTask
import com.codex.chat.core.swarm.model.SwarmTaskResult
import com.codex.chat.core.swarm.model.WorkerProfile
import com.codex.chat.core.swarm.model.WorkerStatus
import com.codex.chat.core.swarm.model.WorkerTicket
import org.junit.Assert.*
import org.junit.Test

/**
 * Fase 0: Pruebas unitarias de contratos del protocolo de Enjambre Móvil (Mobile Hive).
 * Verifica inmutabilidad, transiciones de estado, aislamiento de payloads y validación de tipos.
 */
class SwarmProtocolContractTest {

    @Test
    fun tarea_con_profundidad_valida_retiene_invariantes() {
        val task = SwarmTask(
            taskId = "task-101",
            role = SwarmRole.SENSOR_OS,
            prompt = "Verifica la telemetria de bateria",
            timeoutMs = 15000L,
            parentTaskId = "root-task-0",
            depth = 2
        )

        assertEquals("task-101", task.taskId)
        assertEquals(SwarmRole.SENSOR_OS, task.role)
        assertEquals("Verifica la telemetria de bateria", task.prompt)
        assertEquals(15000L, task.timeoutMs)
        assertEquals("root-task-0", task.parentTaskId)
        assertEquals(2, task.depth)
    }

    @Test
    fun resultado_exitoso_contiene_payload_y_duracion_positiva() {
        val result = SwarmTaskResult(
            taskId = "task-101",
            role = SwarmRole.SENSOR_OS,
            status = WorkerStatus.COMPLETED,
            outputPayload = "{\"battery_level\": 84, \"temperature_c\": 31.5}",
            executionDurationMs = 245L
        )

        assertEquals(WorkerStatus.COMPLETED, result.status)
        assertTrue(result.outputPayload.contains("battery_level"))
        assertTrue(result.executionDurationMs > 0L)
        assertNull(result.errorMessage)
        assertNull(result.taintOrigin)
    }

    @Test
    fun resultado_fallido_contiene_mensaje_de_error_y_payload_vacio() {
        val result = SwarmTaskResult(
            taskId = "task-102",
            role = SwarmRole.CODE_COMPUTE,
            status = WorkerStatus.FAILED,
            outputPayload = "",
            executionDurationMs = 120L,
            errorMessage = "Memory limit exceeded in sandbox worker"
        )

        assertEquals(WorkerStatus.FAILED, result.status)
        assertEquals("", result.outputPayload)
        assertEquals("Memory limit exceeded in sandbox worker", result.errorMessage)
    }

    @Test
    fun resultado_contaminado_registra_origen_de_taint_camel() {
        val result = SwarmTaskResult(
            taskId = "task-103",
            role = SwarmRole.WEB_RESEARCH,
            status = WorkerStatus.COMPLETED,
            outputPayload = "Resultados de navegacion web...",
            executionDurationMs = 850L,
            taintOrigin = TaintOrigin.WEB_SEARCH
        )

        assertEquals(TaintOrigin.WEB_SEARCH, result.taintOrigin)
        assertEquals(WorkerStatus.COMPLETED, result.status)
    }

    @Test
    fun ticket_de_worker_genera_estructura_trazable() {
        val now = System.currentTimeMillis()
        val ticket = WorkerTicket(
            ticketId = "ticket-99",
            taskId = "task-101",
            role = SwarmRole.MULTIMEDIA,
            dispatchedAt = now
        )

        assertEquals("ticket-99", ticket.ticketId)
        assertEquals("task-101", ticket.taskId)
        assertEquals(SwarmRole.MULTIMEDIA, ticket.role)
        assertEquals(now, ticket.dispatchedAt)
    }

    @Test
    fun perfil_de_worker_declara_herramientas_permitidas_por_rol() {
        val profile = WorkerProfile.forRole(SwarmRole.SENSOR_OS)
        assertEquals(SwarmRole.SENSOR_OS, profile.role)
        assertTrue(profile.allowedTools.contains("get_battery_status"))
        assertTrue(profile.allowedTools.contains("get_device_telemetry"))
        assertFalse("Sensor OS no debe tener acceso a herramientas de ejecucion de comandos root", profile.allowedTools.contains("execute_root_command"))
        assertFalse("Sensor OS no debe tener acceso a envio de SMS", profile.allowedTools.contains("send_sms"))
    }

    @Test
    fun perfil_de_worker_web_research_solo_permite_herramientas_de_lectura_web() {
        val profile = WorkerProfile.forRole(SwarmRole.WEB_RESEARCH)
        assertEquals(SwarmRole.WEB_RESEARCH, profile.role)
        assertTrue(profile.allowedTools.contains("http_get") || profile.allowedTools.contains("web_search") || profile.allowedTools.contains("ping_host"))
        assertFalse(profile.allowedTools.contains("write_file"))
        assertFalse(profile.allowedTools.contains("delete_file"))
    }

    @Test
    fun roles_del_enjambre_tienen_nombres_canonicos_estrictos() {
        val expectedRoles = setOf("ORCHESTRATOR", "SENSOR_OS", "CODE_COMPUTE", "MULTIMEDIA", "WEB_RESEARCH", "CRITIC")
        val actualRoles = SwarmRole.values().map { it.name }.toSet()
        assertEquals(expectedRoles, actualRoles)
    }
}