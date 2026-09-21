package com.codex.chat.mcp

import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.device.IDeviceController
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.MobileUseMcpServer
import com.codex.chat.core.security.EstopSentinel
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class MobileUseMcpServerTest {

    private lateinit var mockDevice: MockDeviceController
    private lateinit var server: MobileUseMcpServer

    class MockDeviceController : IDeviceController {
        override val isAvailable: Boolean = true
        var lastDispatchedAction: AgentAction? = null
        var screenshotToReturn: String = "mock_base64_data"
        var hierarchyToReturn: String = """{"count": 2, "elements": [{"text": "Buscar", "clickable": true}]}"""

        override suspend fun captureScreenshotBase64(maxDimension: Int, quality: Int): String = screenshotToReturn
        override fun dumpUiHierarchy(): String = hierarchyToReturn
        override suspend fun dispatch(action: AgentAction): Boolean {
            lastDispatchedAction = action
            return true
        }
    }

    @Before
    fun setUp() {
        EstopSentinel.disengage()
        mockDevice = MockDeviceController()
        server = MobileUseMcpServer(deviceControllerProvider = { mockDevice })
    }

    @After
    fun tearDown() {
        EstopSentinel.disengage()
    }

    @Test
    fun testServerInfoAndToolCount() {
        assertEquals("mcp-mobile-use", server.info.id)
        assertEquals(6, server.info.toolsCount)
        assertEquals(6, server.getTools().size)

        val toolNames = server.getTools().map { it.name }.toSet()
        val expectedTools = setOf(
            "mobile_get_screen",
            "mobile_click",
            "mobile_swipe",
            "mobile_type",
            "mobile_press_key",
            "mobile_wait"
        )
        assertEquals(expectedTools, toolNames)
    }

    @Test
    fun testMobileGetScreenExecution() {
        val call = McpToolCallRequest("c1", "mobile_get_screen", "{}")
        val result = server.executeTool(call)

        assertFalse("Expected success", result.isError)
        val json = JSONObject(result.content)
        assertEquals("success", json.getString("status"))
        assertTrue("Contains screenshot", json.has("screenshot_base64"))
        assertTrue("Contains hierarchy", json.has("ui_hierarchy"))
        assertTrue("Contains clean summary", json.has("summary"))
        assertEquals("mock_base64_data", json.getString("screenshot_base64"))
    }

    @Test
    fun testMobileGetScreenRetrySucceedsOnSecondAttempt() {
        var callCount = 0
        val flakingDevice = object : IDeviceController {
            override val isAvailable: Boolean = true
            override suspend fun captureScreenshotBase64(maxDimension: Int, quality: Int): String {
                callCount++
                return if (callCount == 1) "" else "recovered_base64_data"
            }
            override fun dumpUiHierarchy(): String = """{"count": 0}"""
            override suspend fun dispatch(action: AgentAction): Boolean = true
        }

        val serverWithRetry = MobileUseMcpServer(deviceControllerProvider = { flakingDevice })
        val call = McpToolCallRequest("c1_retry", "mobile_get_screen", "{}")
        val result = serverWithRetry.executeTool(call)

        assertFalse("Expected success after retry", result.isError)
        val json = JSONObject(result.content)
        assertEquals("recovered_base64_data", json.getString("screenshot_base64"))
        assertTrue("Attempted at least 2 times", callCount >= 2)
    }

    @Test
    fun testMobileGetScreenFailsGracefullyWhenAllAttemptsEmpty() {
        var callCount = 0
        val deadDevice = object : IDeviceController {
            override val isAvailable: Boolean = true
            override suspend fun captureScreenshotBase64(maxDimension: Int, quality: Int): String {
                callCount++
                return ""
            }
            override fun dumpUiHierarchy(): String = """{"count": 0}"""
            override suspend fun dispatch(action: AgentAction): Boolean = true
        }

        val serverWithDeadDevice = MobileUseMcpServer(deviceControllerProvider = { deadDevice })
        val call = McpToolCallRequest("c1_dead", "mobile_get_screen", "{}")
        val result = serverWithDeadDevice.executeTool(call)

        assertFalse("Even with empty screenshot, response is structured JSON", result.isError)
        val json = JSONObject(result.content)
        assertEquals("", json.getString("screenshot_base64"))
        assertEquals(3, callCount)
    }

    @Test
    fun testMobileClickExecution() {
        val args = JSONObject().apply {
            put("x", 500)
            put("y", 1200)
            put("reason", "Click play button")
        }
        val call = McpToolCallRequest("c2", "mobile_click", args.toString())
        val result = server.executeTool(call)

        assertFalse("Expected success", result.isError)
        assertNotNull("Action must be dispatched", mockDevice.lastDispatchedAction)
        val dispatched = mockDevice.lastDispatchedAction as AgentAction.Tap
        assertEquals(500, dispatched.x)
        assertEquals(1200, dispatched.y)
        assertEquals("Click play button", dispatched.reason)
    }

    @Test
    fun testMobileSwipeExecution() {
        val args = JSONObject().apply {
            put("startX", 500)
            put("startY", 1500)
            put("endX", 500)
            put("endY", 400)
            put("duration_ms", 400)
            put("reason", "Scroll down feed")
        }
        val call = McpToolCallRequest("c3", "mobile_swipe", args.toString())
        val result = server.executeTool(call)

        assertFalse("Expected success", result.isError)
        val dispatched = mockDevice.lastDispatchedAction as AgentAction.Swipe
        assertEquals(500, dispatched.startX)
        assertEquals(1500, dispatched.startY)
        assertEquals(500, dispatched.endX)
        assertEquals(400, dispatched.endY)
        assertEquals(400L, dispatched.durationMs)
    }

    @Test
    fun testMobileTypeExecution() {
        val args = JSONObject().apply {
            put("text", "electronic music")
            put("press_enter", true)
            put("reason", "Type search query")
        }
        val call = McpToolCallRequest("c4", "mobile_type", args.toString())
        val result = server.executeTool(call)

        assertFalse("Expected success", result.isError)
        val dispatched = mockDevice.lastDispatchedAction as AgentAction.InputText
        assertEquals("electronic music", dispatched.text)
        assertTrue(dispatched.pressEnter)
    }

    @Test
    fun testMobilePressKeyExecution() {
        val args = JSONObject().apply {
            put("key", "HOME")
            put("reason", "Return to launcher")
        }
        val call = McpToolCallRequest("c5", "mobile_press_key", args.toString())
        val result = server.executeTool(call)

        assertFalse("Expected success", result.isError)
        val dispatched = mockDevice.lastDispatchedAction as AgentAction.PressKey
        assertEquals(AgentAction.GlobalKey.HOME, dispatched.key)
    }

    @Test
    fun testEstopRejectsExecution() {
        EstopSentinel.engage("Manual ESTOP engaged")
        val args = JSONObject().apply {
            put("x", 100)
            put("y", 200)
        }
        val call = McpToolCallRequest("c6", "mobile_click", args.toString())
        val result = server.executeTool(call)

        assertTrue("Expected error when ESTOP is engaged", result.isError)
        assertTrue("Mentions ESTOP", result.content.contains("ESTOP") || result.content.contains("emergencia"))
    }
}
