package com.codex.chat.agent

import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.network.VisionResponseParser
import org.junit.Assert.*
import org.junit.Test

class VisionResponseParserTest {
    private val parser = VisionResponseParser()

    @Test
    fun testParsesMarkdownFenceTapAction() {
        val input = "I will tap the search button.\n\n```json\n{\"action\":\"tap\",\"x\":500,\"y\":900,\"reason\":\"click button\"}\n```"
        val result = parser.parse(input)
        assertTrue("Expected Tap action, got $result", result is AgentAction.Tap)
        val tap = result as AgentAction.Tap
        assertEquals(500, tap.x)
        assertEquals(900, tap.y)
        assertEquals("click button", tap.reason)
    }

    @Test
    fun testParsesRawJsonSwipeAction() {
        val input = "{\"action\":\"swipe\",\"startX\":100,\"startY\":500,\"endX\":100,\"endY\":200,\"duration_ms\":400,\"reason\":\"scroll down\"}"
        val result = parser.parse(input)
        assertTrue("Expected Swipe action, got $result", result is AgentAction.Swipe)
        val swipe = result as AgentAction.Swipe
        assertEquals(100, swipe.startX)
        assertEquals(500, swipe.startY)
        assertEquals(100, swipe.endX)
        assertEquals(200, swipe.endY)
        assertEquals(400L, swipe.durationMs)
    }

    @Test
    fun testParsesCompleteAction() {
        val input = "{\"action\":\"complete\",\"result\":\"Found the requested video and started playback\"}"
        val result = parser.parse(input)
        assertTrue("Expected Complete action, got $result", result is AgentAction.Complete)
        assertEquals("Found the requested video and started playback", (result as AgentAction.Complete).result)
    }

    @Test
    fun testParsesFailAction() {
        val input = "{\"action\":\"fail\",\"error\":\"App crashed and cannot proceed\"}"
        val result = parser.parse(input)
        assertTrue("Expected Fail action, got $result", result is AgentAction.Fail)
        assertEquals("App crashed and cannot proceed", (result as AgentAction.Fail).error)
    }

    @Test
    fun testReturnsFailOnMalformedInput() {
        val result = parser.parse("There is no valid json anywhere in this output.")
        assertTrue("Expected Fail action on malformed, got $result", result is AgentAction.Fail)
    }

    @Test
    fun testExtractsJsonAfterLeadingConversationalText() {
        val input = "Looking at the screen, the back button is visible. {\"action\":\"press_key\",\"key\":\"BACK\",\"reason\":\"dismiss dialog\"}"
        val result = parser.parse(input)
        assertTrue("Expected PressKey action, got $result", result is AgentAction.PressKey)
        val key = result as AgentAction.PressKey
        assertEquals(AgentAction.GlobalKey.BACK, key.key)
    }

    @Test
    fun testParsesInputTextAction() {
        val input = "{\"action\":\"input_text\",\"text\":\"ambient music\",\"press_enter\":true,\"reason\":\"submit query\"}"
        val result = parser.parse(input)
        assertTrue("Expected InputText action, got $result", result is AgentAction.InputText)
        val inputAction = result as AgentAction.InputText
        assertEquals("ambient music", inputAction.text)
        assertTrue(inputAction.pressEnter)
    }
}
