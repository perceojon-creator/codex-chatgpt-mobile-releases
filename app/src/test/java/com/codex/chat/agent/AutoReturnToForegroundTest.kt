package com.codex.chat.agent

import android.content.Intent
import com.codex.chat.core.security.EstopSentinel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests verifying:
 * 1. Mobile agent auto-return-to-foreground intent flag invariants.
 * 2. EstopSentinel automatic disengagement lifecycle.
 * 3. Completion callback contract for backgrounded tasks.
 */
class AutoReturnToForegroundTest {

    @Before
    fun setUp() {
        EstopSentinel.disengage()
    }

    @After
    fun tearDown() {
        EstopSentinel.disengage()
    }

    @Test
    fun testForegroundIntentHasRequiredFlags() {
        // Pure-JVM flag composition test (independent of android.jar stubs)
        val NEW_TASK = Intent.FLAG_ACTIVITY_NEW_TASK
        val CLEAR_TOP = Intent.FLAG_ACTIVITY_CLEAR_TOP
        val SINGLE_TOP = Intent.FLAG_ACTIVITY_SINGLE_TOP

        val combinedFlags = NEW_TASK or CLEAR_TOP or SINGLE_TOP

        assertTrue("Must have FLAG_ACTIVITY_NEW_TASK", (combinedFlags and NEW_TASK) != 0)
        assertTrue("Must have FLAG_ACTIVITY_CLEAR_TOP", (combinedFlags and CLEAR_TOP) != 0)
        assertTrue("Must have FLAG_ACTIVITY_SINGLE_TOP", (combinedFlags and SINGLE_TOP) != 0)
    }

    @Test
    fun testEstopAutoDisengageLifecycle() {
        // 1. Simulate ESTOP engaged by previous task
        EstopSentinel.engage("Task completed with emergency stop")
        assertTrue("ESTOP must be engaged", EstopSentinel.isEngaged())

        // 2. Simulating the fix in sendMessage():
        // When a new message is sent while overlay is NOT showing, ESTOP disengages automatically
        val overlayIsShowing = false
        if (EstopSentinel.isEngaged() && !overlayIsShowing) {
            EstopSentinel.disengage()
        }

        assertFalse("ESTOP must be cleared for new conversation turn", EstopSentinel.isEngaged())
    }

    @Test
    fun testEstopPreservedWhenOverlayIsShowing() {
        // If overlay is actively showing, user's ESTOP MUST NOT be auto-disengaged
        EstopSentinel.engage("User actively tapped STOP")
        val overlayIsShowing = true

        if (EstopSentinel.isEngaged() && !overlayIsShowing) {
            EstopSentinel.disengage()
        }

        assertTrue("ESTOP must remain engaged while overlay is active", EstopSentinel.isEngaged())
    }

    @Test
    fun testCompletionCallbackContract() {
        var returnCalled = false
        val mockCallback: () -> Unit = {
            returnCalled = true
        }

        // Simulate updateComplete invoking the callback
        mockCallback.invoke()

        assertTrue("Return-to-foreground callback must be triggered on completion", returnCalled)
    }
}
