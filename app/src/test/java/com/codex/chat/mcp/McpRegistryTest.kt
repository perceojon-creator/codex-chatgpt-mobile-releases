package com.codex.chat.mcp

import com.codex.chat.core.mcp.McpRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class McpRegistryTest {

    private lateinit var registry: McpRegistry

    @Before
    fun setUp() {
        registry = McpRegistry(context = null)
    }

    @Test
    fun testBuiltInServersRegistered() {
        val servers = registry.getServers()
        assertTrue("Debe registrar al menos 11 servidores nativos", servers.size >= 11)

        val serverIds = servers.map { it.id }
        assertTrue("Debe incluir mcp-android-device", serverIds.contains("mcp-android-device"))
        assertTrue("Debe incluir mcp-android-memory", serverIds.contains("mcp-android-memory"))
        assertTrue("Debe incluir mcp-android-filesystem", serverIds.contains("mcp-android-filesystem"))
        assertTrue("Debe incluir mcp-android-clipboard", serverIds.contains("mcp-android-clipboard"))
        assertTrue("Debe incluir mcp-android-calculator", serverIds.contains("mcp-android-calculator"))
        assertTrue("Debe incluir mcp-android-network", serverIds.contains("mcp-android-network"))
        assertTrue("Debe incluir mcp-android-personal-data", serverIds.contains("mcp-android-personal-data"))
        assertTrue("Debe incluir mcp-android-telephony-sms", serverIds.contains("mcp-android-telephony-sms"))
        assertTrue("Debe incluir mcp-android-system-settings", serverIds.contains("mcp-android-system-settings"))
        assertTrue("Debe incluir mcp-android-root", serverIds.contains("mcp-android-root"))
        assertTrue("Debe incluir mcp-cloud-e2b", serverIds.contains("mcp-cloud-e2b"))
        assertTrue("Debe incluir mcp-android-html-sandbox", serverIds.contains("mcp-android-html-sandbox"))
    }

    @Test
    fun testAllActiveToolsExposed() {
        val tools = registry.getAllActiveTools()
        assertTrue("Debe exponer al menos 15 herramientas nativas", tools.size >= 15)

        val toolNames = tools.map { it.name }
        assertTrue(toolNames.contains("get_battery_status"))
        assertTrue(toolNames.contains("get_device_telemetry"))
        assertTrue(toolNames.contains("save_memory"))
        assertTrue(toolNames.contains("get_memory"))
        assertTrue(toolNames.contains("write_file"))
        assertTrue(toolNames.contains("read_file"))
        assertTrue(toolNames.contains("evaluate_math"))
        assertTrue(toolNames.contains("compute_hash"))
        assertTrue(toolNames.contains("http_get"))
    }

    @Test
    fun testDeviceBatteryToolExecution() {
        val res = registry.executeTool("get_battery_status")
        assertFalse("No debe reportar error", res.isError)
        val json = JSONObject(res.content)
        assertTrue(json.has("level_percent"))
        assertTrue(json.has("is_charging"))
    }

    @Test
    fun testMemoryToolWorkflow() {
        val saveRes = registry.executeTool(
            "save_memory",
            JSONObject().put("key", "framework").put("value", "Jetpack Compose").toString()
        )
        assertFalse(saveRes.isError)
        assertTrue(saveRes.content.contains("guardada exitosamente"))

        val getRes = registry.executeTool(
            "get_memory",
            JSONObject().put("key", "framework").toString()
        )
        assertFalse(getRes.isError)
        assertTrue(getRes.content.contains("Jetpack Compose"))

        val delRes = registry.executeTool(
            "delete_memory",
            JSONObject().put("key", "framework").toString()
        )
        assertFalse(delRes.isError)
        assertTrue(delRes.content.contains("eliminada"))
    }

    @Test
    fun testFileSystemWorkflowAndPathTraversalProtection() {
        // 1. Write file
        val writeRes = registry.executeTool(
            "write_file",
            JSONObject().put("file_name", "test_mcp.txt").put("content", "Hola MCP Nativo").toString()
        )
        assertFalse(writeRes.isError)

        // 2. Read file
        val readRes = registry.executeTool(
            "read_file",
            JSONObject().put("file_name", "test_mcp.txt").toString()
        )
        assertFalse(readRes.isError)
        assertEquals("Hola MCP Nativo", readRes.content)

        // 3. Path traversal attack rejection
        val attackRes = registry.executeTool(
            "read_file",
            JSONObject().put("file_name", "../../../etc/passwd").toString()
        )
        assertTrue("Debe rechazar intento de path traversal", attackRes.isError)

        // 4. Delete file
        val delRes = registry.executeTool(
            "delete_file",
            JSONObject().put("file_name", "test_mcp.txt").toString()
        )
        assertFalse(delRes.isError)
    }

    @Test
    fun testCalculatorMathEvaluation() {
        val res = registry.executeTool(
            "evaluate_math",
            JSONObject().put("expression", "2^10 + 24").toString()
        )
        assertFalse(res.isError)
        assertTrue("Resultado debe contener 1048.0", res.content.contains("1048.0"))

        val hashRes = registry.executeTool(
            "compute_hash",
            JSONObject().put("text", "hello").put("algorithm", "SHA-256").toString()
        )
        assertFalse(hashRes.isError)
        assertTrue("Hash SHA-256 correcto de 'hello'", hashRes.content.contains("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824"))
    }

    @Test
    fun testServerToggleDisablesTools() {
        val initialToolsCount = registry.getAllActiveTools().size

        registry.setServerEnabled("mcp-android-calculator", false)
        val afterDisabled = registry.getAllActiveTools().size
        assertEquals(initialToolsCount - 3, afterDisabled)

        val failCall = registry.executeTool("evaluate_math", "{}")
        assertTrue("Debe fallar al invocar herramienta de servidor desactivado", failCall.isError)

        registry.setServerEnabled("mcp-android-calculator", true)
        val restored = registry.getAllActiveTools().size
        assertEquals(initialToolsCount, restored)
    }

    @Test
    fun testOpenAiToolSchemaConversion() {
        val tool = registry.getAllActiveTools().first { it.name == "get_battery_status" }
        val openAiSchema = tool.toOpenAiToolSchema()

        assertEquals("function", openAiSchema.getString("type"))
        val fn = openAiSchema.getJSONObject("function")
        assertEquals("get_battery_status", fn.getString("name"))
        assertTrue(fn.getString("description").isNotEmpty())
        assertTrue(fn.has("parameters"))
    }

    @Test
    fun testStorageRootAndDirectoryCreation() {
        // 1. get_storage_root
        val rootRes = registry.executeTool("get_storage_root", "{}")
        assertFalse(rootRes.isError)
        val json = JSONObject(rootRes.content)
        assertTrue(json.has("workspace_internal_path"))
        assertTrue(json.has("shared_storage_path"))
        assertTrue(json.has("common_directories"))

        // 2. create_directory
        val dirRes = registry.executeTool(
            "create_directory",
            JSONObject().put("directory_path", "Download/test_codex_dir").toString()
        )
        assertFalse(dirRes.isError)
        assertTrue(dirRes.content.contains("creada o confirmada"))
    }

    @Test
    fun testDeviceVibrationAndWifiStatus() {
        val vibRes = registry.executeTool(
            "vibrate_device",
            JSONObject().put("duration_ms", 250).toString()
        )
        assertFalse(vibRes.isError)
        assertTrue(vibRes.content.contains("Vibración háptica"))

        val wifiRes = registry.executeTool("get_wifi_status", "{}")
        assertFalse(wifiRes.isError)
        val json = JSONObject(wifiRes.content)
        assertTrue(json.has("wifi_enabled"))
    }

    @Test
    fun testPersonalDataContactsAndCalendar() {
        val contactsRes = registry.executeTool("list_contacts", "{}")
        assertFalse(contactsRes.isError)
        val jsonContacts = JSONObject(contactsRes.content)
        assertTrue(jsonContacts.has("contacts"))

        val calRes = registry.executeTool("list_calendar_events", "{}")
        assertFalse(calRes.isError)
        val jsonCal = JSONObject(calRes.content)
        assertTrue(jsonCal.has("events"))

        val createEventRes = registry.executeTool(
            "create_calendar_event",
            JSONObject().put("title", "Reunión de Codex").toString()
        )
        assertFalse(createEventRes.isError)
        assertTrue(createEventRes.content.contains("creado con éxito"))
    }

    @Test
    fun testTelephonyCallLogAndSms() {
        val callLogRes = registry.executeTool("get_call_log", "{}")
        assertFalse(callLogRes.isError)
        val jsonCalls = JSONObject(callLogRes.content)
        assertTrue(jsonCalls.has("calls"))

        val smsRes = registry.executeTool("read_sms_messages", "{}")
        assertFalse(smsRes.isError)
        val jsonSms = JSONObject(smsRes.content)
        assertTrue(jsonSms.has("messages"))

        val sendRes = registry.executeTool(
            "send_sms",
            JSONObject().put("phone_number", "+34612345678").put("message", "Test SMS").toString()
        )
        assertFalse(sendRes.isError)
        assertTrue(sendRes.content.contains("enviado con éxito"))
    }

    @Test
    fun testSystemSettingsAndUsage() {
        val settingsRes = registry.executeTool("get_device_settings", "{}")
        assertFalse(settingsRes.isError)
        val jsonSettings = JSONObject(settingsRes.content)
        assertTrue(jsonSettings.has("screen_brightness_percent"))
        assertTrue(jsonSettings.has("music_volume_percent"))

        val volRes = registry.executeTool(
            "set_audio_volume",
            JSONObject().put("stream_type", "music").put("level_percent", 80).toString()
        )
        assertFalse(volRes.isError)
        assertTrue(volRes.content.contains("80%"))

        val usageRes = registry.executeTool("get_app_usage_stats", "{}")
        assertFalse(usageRes.isError)
        val jsonUsage = JSONObject(usageRes.content)
        assertTrue(jsonUsage.has("usage"))
    }

    @Test
    fun testRootMcpServerTools() {
        val rootStatus = registry.executeTool("check_root_status", "{}")
        assertFalse(rootStatus.isError)
        val jsonStatus = JSONObject(rootStatus.content)
        assertTrue(jsonStatus.has("is_rooted"))

        val execRes = registry.executeTool(
            "execute_root_command",
            JSONObject().put("command", "id").toString()
        )
        assertFalse(execRes.isError)
        val jsonExec = JSONObject(execRes.content)
        assertTrue(jsonExec.has("success"))

        val readRes = registry.executeTool(
            "root_read_file",
            JSONObject().put("path", "/system/build.prop").toString()
        )
        assertFalse(readRes.isError)
        assertTrue(readRes.content.contains("Contenido simulado"))

        val writeRes = registry.executeTool(
            "root_write_file",
            JSONObject().put("path", "/data/local/tmp/test.txt").put("content", "hello root").toString()
        )
        assertFalse(writeRes.isError)
        assertTrue(writeRes.content.contains("escrito exitosamente"))

        val grantRes = registry.executeTool(
            "root_grant_permissions",
            JSONObject().put("permission", "ALL").toString()
        )
        assertFalse(grantRes.isError)
        val jsonGrant = JSONObject(grantRes.content)
        assertTrue(jsonGrant.has("granted_count"))

        val rebootRes = registry.executeTool(
            "root_reboot_device",
            JSONObject().put("mode", "recovery").toString()
        )
        assertFalse(rebootRes.isError)
        assertTrue(rebootRes.content.contains("recovery"))
    }

    @Test
    fun testE2bCloudMcpServerTools() {
        val tools = registry.getAllActiveTools()
        val toolNames = tools.map { it.name }
        assertTrue("Debe exponer execute_python", toolNames.contains("execute_python"))
        assertTrue("Debe exponer execute_sandbox_command", toolNames.contains("execute_sandbox_command"))

        val e2bServer = registry.getServers().find { it.id == "mcp-cloud-e2b" }
        assertNotNull(e2bServer)
        assertEquals("E2B Cloud Code Interpreter", e2bServer!!.name)
        assertEquals(2, e2bServer.toolsCount)
    }
}




