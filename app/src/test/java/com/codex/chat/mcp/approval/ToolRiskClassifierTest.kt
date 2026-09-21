package com.codex.chat.mcp.approval

import com.codex.chat.core.mcp.approval.ToolRiskClassifier
import com.codex.chat.core.mcp.approval.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ToolRiskClassifierTest {

    private val esperado = mapOf(
        // --- SAFE ---
        "evaluate_math"              to ToolRiskLevel.SAFE,
        "compute_hash"               to ToolRiskLevel.SAFE,
        "base64_codec"               to ToolRiskLevel.SAFE,
        "get_battery_status"         to ToolRiskLevel.SAFE,
        "get_device_telemetry"       to ToolRiskLevel.SAFE,
        "get_storage_info"           to ToolRiskLevel.SAFE,
        "vibrate_device"             to ToolRiskLevel.SAFE,
        "get_wifi_status"            to ToolRiskLevel.SAFE,
        "get_storage_root"           to ToolRiskLevel.SAFE,
        "dns_resolve"                to ToolRiskLevel.SAFE,
        "ping_host"                  to ToolRiskLevel.SAFE,
        "check_root_status"          to ToolRiskLevel.SAFE,
        "list_memories"              to ToolRiskLevel.SAFE,
        "get_memory"                 to ToolRiskLevel.SAFE,
        "search_memory"              to ToolRiskLevel.SAFE,
        "wal_status"                 to ToolRiskLevel.SAFE,
        "get_device_settings"        to ToolRiskLevel.SAFE,
        "test_html_code"             to ToolRiskLevel.SAFE,
        "inspect_html_dom"           to ToolRiskLevel.SAFE,
        "get_worker_status"          to ToolRiskLevel.SAFE,
        "get_goal"                   to ToolRiskLevel.SAFE,
        "web_search"                 to ToolRiskLevel.SAFE,
        "fetch_web_page"             to ToolRiskLevel.SAFE,
        "mobile_get_screen"          to ToolRiskLevel.SAFE,
        "mobile_wait"                to ToolRiskLevel.SAFE,
        "termux_get_environment"     to ToolRiskLevel.SAFE,
        // --- SENSITIVE ---
        "get_device_location"        to ToolRiskLevel.SENSITIVE,
        "get_clipboard_text"         to ToolRiskLevel.SENSITIVE,
        "list_files"                 to ToolRiskLevel.SENSITIVE,
        "read_file"                  to ToolRiskLevel.SENSITIVE,
        "list_contacts"              to ToolRiskLevel.SENSITIVE,
        "list_calendar_events"       to ToolRiskLevel.SENSITIVE,
        "get_app_usage_stats"        to ToolRiskLevel.SENSITIVE,
        "mobile_click"               to ToolRiskLevel.SENSITIVE,
        "mobile_swipe"               to ToolRiskLevel.SENSITIVE,
        "mobile_type"                to ToolRiskLevel.SENSITIVE,
        "mobile_press_key"           to ToolRiskLevel.SENSITIVE,
        "termux_read_file"           to ToolRiskLevel.SENSITIVE,
        // --- DESTRUCTIVE (19) ---
        "set_clipboard_text"         to ToolRiskLevel.DESTRUCTIVE,
        "write_file"                 to ToolRiskLevel.DESTRUCTIVE,
        "delete_file"                to ToolRiskLevel.DESTRUCTIVE,
        "create_directory"           to ToolRiskLevel.DESTRUCTIVE,
        "send_sms"                   to ToolRiskLevel.DESTRUCTIVE,
        "save_memory"                to ToolRiskLevel.DESTRUCTIVE,
        "delete_memory"              to ToolRiskLevel.DESTRUCTIVE,
        "create_calendar_event"      to ToolRiskLevel.DESTRUCTIVE,
        "set_audio_volume"           to ToolRiskLevel.DESTRUCTIVE,
        "set_screen_brightness"      to ToolRiskLevel.DESTRUCTIVE,
        "http_get"                   to ToolRiskLevel.DESTRUCTIVE,
        "execute_python"             to ToolRiskLevel.DESTRUCTIVE,
        "execute_sandbox_command"    to ToolRiskLevel.DESTRUCTIVE,
        "get_call_log"               to ToolRiskLevel.DESTRUCTIVE,
        "read_sms_messages"          to ToolRiskLevel.DESTRUCTIVE,
        "get_captured_notifications" to ToolRiskLevel.DESTRUCTIVE,
        "spawn_worker"               to ToolRiskLevel.DESTRUCTIVE,
        "await_workers"              to ToolRiskLevel.DESTRUCTIVE,
        "create_goal"                to ToolRiskLevel.DESTRUCTIVE,
        "update_goal"                to ToolRiskLevel.DESTRUCTIVE,
        "wal_truncate"               to ToolRiskLevel.DESTRUCTIVE,
        "termux_execute_command"     to ToolRiskLevel.DESTRUCTIVE,
        "termux_write_file"          to ToolRiskLevel.DESTRUCTIVE,
        "termux_pkg_install"         to ToolRiskLevel.DESTRUCTIVE,
        // --- ROOT (5) ---
        "execute_root_command"       to ToolRiskLevel.ROOT,
        "root_read_file"             to ToolRiskLevel.ROOT,
        "root_write_file"            to ToolRiskLevel.ROOT,
        "root_grant_permissions"     to ToolRiskLevel.ROOT,
        "root_reboot_device"         to ToolRiskLevel.ROOT
    )

    @Test
    fun las_herramientas_conocidas_tienen_la_clasificacion_esperada() {
        for ((tool, nivel) in esperado) {
            assertEquals("Clasificacion de '$tool'",
                nivel, ToolRiskClassifier.classify(tool))
        }
    }

    @Test
    fun el_inventario_del_test_cubre_todas_las_conocidas() {
        val enClasificador = ToolRiskClassifier.conocidas()
        val enTest         = esperado.keys
        assertEquals(
            "Hay herramientas en el clasificador sin caso de test: " +
                (enClasificador - enTest),
            emptySet<String>(), enClasificador - enTest
        )
        assertEquals(
            "Hay casos de test que ya no existen en el clasificador: " +
                (enTest - enClasificador),
            emptySet<String>(), enTest - enClasificador
        )
    }

    @Test
    fun herramienta_desconocida_nunca_es_SAFE() {
        val desconocidas = listOf(
            "herramienta_que_no_existe",
            "format_disk",
            "wipe_device",
            "tool_de_servidor_remoto_nuevo",
            ""
        )
        for (t in desconocidas) {
            val r = ToolRiskClassifier.classify(t)
            assertNotEquals(
                "Una herramienta desconocida ('$t') jamas puede ser SAFE",
                ToolRiskLevel.SAFE, r
            )
            assertEquals(
                "Fail-closed: desconocida -> DESTRUCTIVE",
                ToolRiskLevel.DESTRUCTIVE, r
            )
        }
    }

    @Test
    fun cualquier_prefijo_root_escala_a_ROOT_aunque_no_este_listado() {
        assertEquals(ToolRiskLevel.ROOT,
            ToolRiskClassifier.classify("root_herramienta_futura"))
        assertEquals(ToolRiskLevel.ROOT,
            ToolRiskClassifier.classify("root_wipe"))
        assertEquals(ToolRiskLevel.ROOT,
            ToolRiskClassifier.classify("ejecutar_sudo_algo"))
    }

    @Test
    fun la_clasificacion_ignora_mayusculas_y_espacios() {
        assertEquals(ToolRiskLevel.ROOT,
            ToolRiskClassifier.classify("EXECUTE_ROOT_COMMAND"))
        assertEquals(ToolRiskLevel.ROOT,
            ToolRiskClassifier.classify("  execute_root_command  "))
        assertEquals(ToolRiskLevel.SAFE,
            ToolRiskClassifier.classify("Get_Battery_Status"))
    }

    @Test
    fun los_cuatro_conjuntos_son_disjuntos() {
        val todas = ToolRiskClassifier.conocidas()
        val porNivel = todas.groupBy { ToolRiskClassifier.classify(it) }
        val suma = porNivel.values.sumOf { it.size }
        assertEquals("Alguna herramienta cae en mas de un conjunto",
            todas.size, suma)
    }

    @Test
    fun ninguna_herramienta_de_root_baja_de_nivel() {
        val deRootMcpServer = listOf(
            "execute_root_command", "root_read_file", "root_write_file",
            "root_grant_permissions", "root_reboot_device"
        )
        for (t in deRootMcpServer) {
            assertEquals("$t debe ser ROOT, sin excepcion",
                ToolRiskLevel.ROOT, ToolRiskClassifier.classify(t))
        }
    }

    @Test
    fun http_get_no_se_clasifica_como_lectura() {
        assertEquals(
            "http_get permite exfiltrar datos en la URL: es DESTRUCTIVE",
            ToolRiskLevel.DESTRUCTIVE, ToolRiskClassifier.classify("http_get")
        )
    }
}
