package com.codex.chat.agent

import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.core.GroundingPromptBuilder
import org.junit.Assert.*
import org.junit.Test

class GroundingPromptBuilderTest {
    private val builder = GroundingPromptBuilder()

    @Test
    fun testSystemPromptContainsDeviceDimensions() {
        val prompt = builder.buildSystemPrompt(1080, 1920)
        assertTrue("Expected width 1080 in prompt", prompt.contains("1080"))
        assertTrue("Expected height 1920 in prompt", prompt.contains("1920"))
    }

    @Test
    fun testSystemPromptContainsJsonSchemaKeywords() {
        val prompt = builder.buildSystemPrompt(1080, 1920)
        assertTrue("Expected 'action' in prompt", prompt.contains("action"))
        assertTrue("Expected 'tap' in prompt", prompt.contains("tap"))
        assertTrue("Expected 'swipe' in prompt", prompt.contains("swipe"))
        assertTrue("Expected 'input_text' in prompt", prompt.contains("input_text"))
    }

    @Test
    fun testUserMessageContainsHierarchyString() {
        val hierarchy = "{\"root\":{\"class\":\"android.widget.Button\",\"text\":\"Search\"}}"
        val msg = builder.buildUserMessage("Open YouTube", 1, hierarchy, emptyList())
        assertTrue("Expected hierarchy text in message", msg.contains("android.widget.Button"))
        assertTrue("Expected hierarchy text in message", msg.contains("Search"))
    }

    @Test
    fun testUserMessageContainsGoalAndStep() {
        val msg = builder.buildUserMessage("Search for lofi music", 3, "{}", emptyList())
        assertTrue("Expected goal in message", msg.contains("Search for lofi music"))
        assertTrue("Expected step 3 in message", msg.contains("3"))
    }

    @Test
    fun testUserMessageIncludesActionHistory() {
        val history = listOf(
            AgentAction.Tap(500, 500, "clicked icon"),
            AgentAction.InputText("lofi", true, "typed search")
        )
        val msg = builder.buildUserMessage("Search for lofi music", 2, "{}", history)
        assertTrue("Expected history in message", msg.contains("clicked icon"))
        assertTrue("Expected history in message", msg.contains("typed search"))
    }
}
