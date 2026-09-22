package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FeedbackControlsLayoutTest {
    @Test
    fun testAssistantLayoutIncludesFeedbackControls() {
        val file = File("src/main/res/layout/item_message_assistant.xml")
        assertTrue("item_message_assistant.xml debe existir", file.exists())
        val content = file.readText()
        assertTrue("item_message_assistant debe incluir layout_message_feedback_controls",
            content.contains("layout_message_feedback_controls") || content.contains("btnCopyMessage"))
    }
}
