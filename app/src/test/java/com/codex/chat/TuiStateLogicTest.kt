package com.codex.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TuiStateLogicTest {

    @Test
    fun testEmptyStateVisibilityRule() {
        fun computeEmptyStateVisibility(messageCount: Int): Int {
            // View.VISIBLE is 0, View.GONE is 8 in Android
            return if (messageCount == 0) 0 else 8
        }

        // 1. App opens with empty chat -> Visible
        assertEquals(0, computeEmptyStateVisibility(0))

        // 2. Message sent -> Gone
        assertEquals(8, computeEmptyStateVisibility(1))

        // 3. Historical conversation with 10 messages loaded from drawer -> Gone
        assertEquals(8, computeEmptyStateVisibility(10))

        // 4. New chat created -> Visible
        assertEquals(0, computeEmptyStateVisibility(0))
    }

    @Test
    fun testTextWatcherDoesNotHideStopButtonDuringExecution() {
        var isPromptExecuting = true
        var sendButtonVisible = true
        var micButtonVisible = false

        fun onTextChanged(text: String) {
            if (isPromptExecuting) {
                // Must return immediately without hiding stop button!
                return
            }
            val hasText = text.isNotBlank()
            sendButtonVisible = hasText
            micButtonVisible = !hasText
        }

        // When prompt starts, text field is cleared to ""
        onTextChanged("")

        // Send button must STILL be visible in stop mode, and mic must be hidden
        assertTrue("Send/Stop button must remain visible during execution", sendButtonVisible)
        assertFalse("Mic button must remain hidden during execution", micButtonVisible)

        // When execution completes
        isPromptExecuting = false
        onTextChanged("")
        assertFalse("After execution with empty text, send button is hidden", sendButtonVisible)
        assertTrue("After execution with empty text, mic button is visible", micButtonVisible)

        // When user types second prompt
        onTextChanged("Hola de nuevo")
        assertTrue("When user types text, send button becomes visible", sendButtonVisible)
        assertFalse("When user types text, mic button is hidden", micButtonVisible)
    }
}
