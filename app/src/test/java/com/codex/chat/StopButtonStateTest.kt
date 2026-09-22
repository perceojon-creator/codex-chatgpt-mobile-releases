package com.codex.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopButtonStateTest {
    @Test
    fun testExecutingStateTogglesCorrectly() {
        var isExecuting = false
        var buttonIconRes = 0
        val sendIcon = 1001
        val stopIcon = 1002

        fun updateState(executing: Boolean) {
            isExecuting = executing
            buttonIconRes = if (executing) stopIcon else sendIcon
        }

        updateState(true)
        assertTrue(isExecuting)
        assertTrue(buttonIconRes == stopIcon)

        updateState(false)
        assertFalse(isExecuting)
        assertTrue(buttonIconRes == sendIcon)
    }
}
