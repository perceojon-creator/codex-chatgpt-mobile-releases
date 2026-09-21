package com.codex.chat.agent

import com.codex.chat.agent.core.AgentStatus
import com.codex.chat.agent.core.AutonomousAgentLoop
import com.codex.chat.core.goal.engine.GoalEngine

import com.codex.chat.core.goal.model.GoalSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OneMillionStepsLimitTest {

    @Test
    fun testAgentStatusDefaultsToOneMillionSteps() {
        val status = AgentStatus()
        assertEquals(1_000_000, status.maxSteps)
    }

    @Test
    fun testGoalEngineAllowsOneMillionRounds() {
        val engine = GoalEngine()
        val snapshot = engine.createGoal("Objetivo masivo de 1M de pasos", maxRounds = 1_000_000)
        assertEquals(1_000_000, snapshot.maxGoalRounds)
    }

    @Test
    fun testGoalEngineClampsUpToOneMillionRounds() {
        val engine = GoalEngine()
        val snapshot = engine.createGoal("Objetivo clamped", maxRounds = 2_000_000)
        assertEquals(1_000_000, snapshot.maxGoalRounds)
    }

    @Test
    fun testGoalSnapshotJsonSerializationWithOneMillionRounds() {
        val snapshot = GoalSnapshot(objective = "Test", maxGoalRounds = 1_000_000)
        val json = snapshot.toJson()
        assertEquals(1_000_000, json.getInt("maxGoalRounds"))

        val restored = GoalSnapshot.fromJson(json)
        assertEquals(1_000_000, restored.maxGoalRounds)
    }
}