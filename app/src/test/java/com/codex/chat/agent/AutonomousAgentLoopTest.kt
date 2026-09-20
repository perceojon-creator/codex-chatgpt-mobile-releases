package com.codex.chat.agent

import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.core.AutonomousAgentLoop
import com.codex.chat.agent.device.IDeviceController
import com.codex.chat.agent.network.IVisionClient
import com.codex.chat.core.security.EstopSentinel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AutonomousAgentLoopTest {

    private val testScope = TestScope()

    @Before
    fun setUp() {
        EstopSentinel.disengage()
    }

    @After
    fun tearDown() {
        EstopSentinel.disengage()
    }

    private fun createMockDevice(screenshot: String = "base64", hierarchy: String = "{}"): IDeviceController =
        object : IDeviceController {
            override val isAvailable: Boolean = true
            override suspend fun captureScreenshotBase64(maxDimension: Int, quality: Int): String = screenshot
            override fun dumpUiHierarchy(): String = hierarchy
            override suspend fun dispatch(action: AgentAction): Boolean = true
        }

    private fun createMockVision(responses: List<AgentAction>): IVisionClient {
        var callCount = 0
        return object : IVisionClient {
            override suspend fun think(
                goal: String,
                stepIndex: Int,
                screenshotBase64: String,
                uiHierarchy: String,
                actionHistory: List<AgentAction>
            ): AgentAction {
                val act = responses.getOrElse(callCount++) {
                    AgentAction.Fail("No more responses queued")
                }
                return act
            }
        }
    }

    @Test
    fun testCompletesWhenVisionReturnsCompleteAction() = testScope.runTest {
        val vision = createMockVision(listOf(
            AgentAction.Tap(500, 500, "open app"),
            AgentAction.Complete("Successfully opened app")
        ))
        val loop = AutonomousAgentLoop(
            device = createMockDevice(),
            vision = vision,
            maxSteps = 10,
            externalScope = this,
            stepDelayMs = 10L
        )

        loop.start("Open YouTube")
        val finalStatus = loop.awaitCompletion()

        assertTrue("Expected isComplete to be true", finalStatus.isComplete)
        assertTrue("Expected lastAction to be Complete", finalStatus.lastAction is AgentAction.Complete)
        assertEquals("Successfully opened app", (finalStatus.lastAction as AgentAction.Complete).result)
        assertFalse("Expected isAborted to be false", finalStatus.isAborted)
    }

    @Test
    fun testAbortsImmediatelyWhenEstopIsEngaged() = testScope.runTest {
        EstopSentinel.engage("Testing emergency stop")
        val vision = createMockVision(listOf(AgentAction.Tap(100, 100)))
        val loop = AutonomousAgentLoop(
            device = createMockDevice(),
            vision = vision,
            maxSteps = 10,
            externalScope = this,
            stepDelayMs = 10L
        )

        loop.start("Goal with ESTOP active")
        val finalStatus = loop.awaitCompletion()

        assertTrue("Expected isAborted to be true", finalStatus.isAborted)
        assertTrue("Expected status text to mention ESTOP", finalStatus.statusText.contains("ESTOP"))
    }

    @Test
    fun testAntiLoopSafeguardHaltsOnRepeatedActions() = testScope.runTest {
        // Repeated identical Tap action 4 times
        val identicalAction = AgentAction.Tap(250, 400, "stuck clicking button")
        val vision = createMockVision(List(10) { identicalAction })
        val loop = AutonomousAgentLoop(
            device = createMockDevice(),
            vision = vision,
            maxSteps = 15,
            externalScope = this,
            stepDelayMs = 10L
        )

        loop.start("Loop test goal")
        val finalStatus = loop.awaitCompletion()

        assertTrue("Expected loop completion with failure", finalStatus.isComplete)
        assertTrue("Expected Fail action from anti-loop", finalStatus.lastAction is AgentAction.Fail)
        val fail = finalStatus.lastAction as AgentAction.Fail
        assertTrue("Expected anti-loop message in fail", fail.error.contains("Loop detected") || fail.error.contains("repeated"))
    }

    @Test
    fun testExhaustsMaxStepsCleanly() = testScope.runTest {
        // Infinite distinct Tap actions
        val vision = createMockVision((1..20).map { AgentAction.Tap(it * 10, it * 20, "step " + it) })
        val loop = AutonomousAgentLoop(
            device = createMockDevice(),
            vision = vision,
            maxSteps = 3,
            externalScope = this,
            stepDelayMs = 10L
        )

        loop.start("Max steps test")
        val finalStatus = loop.awaitCompletion()

        assertTrue("Expected loop completion at max steps", finalStatus.isComplete)
        assertEquals(3, finalStatus.stepIndex)
        assertTrue("Expected Fail action at max steps", finalStatus.lastAction is AgentAction.Fail)
    }
}