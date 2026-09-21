package com.codex.chat.compaction

import com.codex.chat.core.compaction.CompactionConfig
import com.codex.chat.core.compaction.CompactionPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionPolicyTest {

    @Test
    fun testThresholdTokensForDifferentContextWindows() {
        // 128k model (DeepSeek / GLM) -> 90% is 115,200
        assertEquals(115_200, CompactionPolicy.resolveThresholdTokens(128_000, 0.90))

        // 200k model (Claude / Astra) -> 90% is 180,000
        assertEquals(180_000, CompactionPolicy.resolveThresholdTokens(200_000, 0.90))

        // 1M model (Gemini / Sol) -> 90% of 1,048,576 is 943,718
        assertEquals(943_718, CompactionPolicy.resolveThresholdTokens(1_048_576, 0.90))
    }

    @Test
    fun testDecisionBelowNinetyPercentDoesNotCompact() {
        val contextWindow = 128_000
        val currentTokens = 100_000 // ~78% (below 90%)
        val decision = CompactionPolicy.evaluate(
            currentTokens = currentTokens,
            contextWindow = contextWindow,
            messageCount = 10
        )
        assertFalse(decision.shouldCompact)
        assertEquals(115_200, decision.thresholdTokens)
    }

    @Test
    fun testDecisionAtOrAboveNinetyPercentTriggersCompaction() {
        val contextWindow = 128_000
        val currentTokens = 116_000 // > 90%
        val decision = CompactionPolicy.evaluate(
            currentTokens = currentTokens,
            contextWindow = contextWindow,
            messageCount = 10
        )
        assertTrue(decision.shouldCompact)
    }

    @Test
    fun testDecisionWithTooFewMessagesRejects() {
        val decision = CompactionPolicy.evaluate(
            currentTokens = 120_000,
            contextWindow = 128_000,
            messageCount = 2 // Too short
        )
        assertFalse(decision.shouldCompact)
    }

    @Test
    fun testAutoDisabledRejects() {
        val config = CompactionConfig(autoCompactEnabled = false)
        val decision = CompactionPolicy.evaluate(
            currentTokens = 125_000,
            contextWindow = 128_000,
            messageCount = 10,
            config = config
        )
        assertFalse(decision.shouldCompact)
    }
}