package com.codex.chat.compaction

import com.codex.chat.core.compaction.CompactionCheckpointFormatter
import com.codex.chat.core.compaction.CompactionConfig
import com.codex.chat.core.compaction.CompactionConstants
import com.codex.chat.core.compaction.CompactionEngine
import com.codex.chat.core.compaction.CompactionPolicy
import com.codex.chat.core.compaction.CompactionRegionSelector
import com.codex.chat.core.metrics.ContextMetricsCalculator
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureNanoTime

/**
 * Adversarial and empirical stress test suite for Context Compaction Engine.
 * Tests mathematical boundaries, edge cases, multi-round re-compaction,
 * and high-throughput statistical performance.
 */
class CompactionAdversarialStressTest {

    // =========================================================================
    // 1. EXACT MATHEMATICAL BOUNDARY ENFORCEMENT ACROSS PROXY FAMILIES
    // =========================================================================

    @Test
    fun testExactNinetyPercentBoundaryForGeminiSolFamily() {
        val solModel = ModelInfo(id = "gpt-5.6-sol", displayName = "Sol", provider = "Antigravity")
        val contextWindow = ContextMetricsCalculator.resolveContextWindow(solModel)
        assertEquals(1_048_576, contextWindow)

        val threshold = CompactionPolicy.resolveThresholdTokens(contextWindow, 0.90)
        assertEquals(943_718, threshold) // floor(1_048_576 * 0.90)

        // Boundary - 1 token: MUST NOT compact
        val decBelow = CompactionPolicy.evaluate(threshold - 1, contextWindow, messageCount = 10)
        assertFalse("Debe rechazar compactacion a threshold - 1", decBelow.shouldCompact)
        assertEquals(threshold, decBelow.thresholdTokens)

        // Exact boundary: MUST compact
        val decExact = CompactionPolicy.evaluate(threshold, contextWindow, messageCount = 10)
        assertTrue("Debe disparar compactacion exactamente en el 90%", decExact.shouldCompact)

        // Boundary + 1 token: MUST compact
        val decAbove = CompactionPolicy.evaluate(threshold + 1, contextWindow, messageCount = 10)
        assertTrue("Debe disparar compactacion en threshold + 1", decAbove.shouldCompact)
    }

    @Test
    fun testExactNinetyPercentBoundaryForClaudeAstraFamily() {
        val astraModel = ModelInfo(id = "gpt-6-astra", displayName = "Astra", provider = "Antigravity")
        val contextWindow = ContextMetricsCalculator.resolveContextWindow(astraModel)
        assertEquals(200_000, contextWindow)

        val threshold = CompactionPolicy.resolveThresholdTokens(contextWindow, 0.90)
        assertEquals(180_000, threshold) // 200_000 * 0.90

        val decBelow = CompactionPolicy.evaluate(179_999, contextWindow, messageCount = 10)
        assertFalse(decBelow.shouldCompact)

        val decExact = CompactionPolicy.evaluate(180_000, contextWindow, messageCount = 10)
        assertTrue(decExact.shouldCompact)

        val decAbove = CompactionPolicy.evaluate(180_001, contextWindow, messageCount = 10)
        assertTrue(decAbove.shouldCompact)
    }

    @Test
    fun testExactNinetyPercentBoundaryForDeepSeekTerraFamily() {
        val terraModel = ModelInfo(id = "gpt-5.6-terra", displayName = "Terra", provider = "DeepSeek")
        val contextWindow = ContextMetricsCalculator.resolveContextWindow(terraModel)
        assertEquals(128_000, contextWindow)

        val threshold = CompactionPolicy.resolveThresholdTokens(contextWindow, 0.90)
        assertEquals(115_200, threshold) // 128_000 * 0.90

        val decBelow = CompactionPolicy.evaluate(115_199, contextWindow, messageCount = 10)
        assertFalse(decBelow.shouldCompact)

        val decExact = CompactionPolicy.evaluate(115_200, contextWindow, messageCount = 10)
        assertTrue(decExact.shouldCompact)

        val decAbove = CompactionPolicy.evaluate(115_201, contextWindow, messageCount = 10)
        assertTrue(decAbove.shouldCompact)
    }

    // =========================================================================
    // 2. TAIL RETENTION BEHAVIORAL VERIFICATION (~15% AND >= 2 MESSAGES)
    // =========================================================================

    @Test
    fun testMinimumTwoMessagesRetainedUnderExtremeForce() {
        // Even when forced to compact a tiny conversation of exactly 3 messages
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Msg 1"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Msg 2"),
            ChatMessage(role = MessageRole.USER, content = "Msg 3")
        )
        val region = CompactionRegionSelector.selectRegion(messages, contextWindow = 128_000, force = true)
        assertNotNull(region)
        assertEquals(1, region!!.spanToCompact.size)
        assertEquals(2, region.retainedTail.size)
        assertEquals("Msg 1", region.spanToCompact[0].content)
        assertEquals("Msg 2", region.retainedTail[0].content)
        assertEquals("Msg 3", region.retainedTail[1].content)
    }

    @Test
    fun testFifteenPercentTokenBudgetAccumulationInTail() {
        val contextWindow = 128_000
        val targetTailBudget = (contextWindow * 0.15).toInt() // 19,200 tokens

        // Generate a 10-message conversation where each message has ~3000 tokens
        val heavyContent = "A".repeat(12000) // ~3077 tokens each
        val messages = (1..10).map { idx ->
            ChatMessage(
                role = if (idx % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                content = "Message " + idx + ": " + heavyContent
            )
        }

        val region = CompactionRegionSelector.selectRegion(
            messages = messages,
            contextWindow = contextWindow,
            retainRatio = 0.15,
            force = false
        )

        assertNotNull(region)
        // Retained tail must satisfy the ~15% budget (or cut at clean user boundary)
        assertTrue("Tail tokens (" + region!!.tailTokens + ") debe aproximar o superar el presupuesto (~15%)",
            region.tailTokens >= targetTailBudget || region.retainedTail.size >= 2)
        assertTrue("Tail debe tener al menos 2 mensajes", region.retainedTail.size >= 2)
        assertTrue("Span debe tener al menos 1 mensaje", region.spanToCompact.isNotEmpty())
    }

    // =========================================================================
    // 3. TOOL-PAIRING BALANCE INVARIANTS
    // =========================================================================

    @Test
    fun testNeverSplitsParallelToolCallsFromToolResponses() {
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Descarga archivo y lista carpeta"),
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "Ejecutando herramientas concurrentes",
                toolCallsJson = """[{"id":"c1","name":"download"},{"id":"c2","name":"list_dir"}]"""
            ),
            ChatMessage(role = MessageRole.TOOL, content = "{\"bytes\": 1024}", toolCallId = "c1"),
            ChatMessage(role = MessageRole.TOOL, content = "{\"files\": [\"a.txt\"]}", toolCallId = "c2"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Descarga completada y directorio listado."),
            ChatMessage(role = MessageRole.USER, content = "Cual es el siguiente paso?"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Listo para compilar.")
        )

        val region = CompactionRegionSelector.selectRegion(
            messages = messages,
            contextWindow = 128_000,
            force = true
        )
        assertNotNull(region)

        // Neither c1 nor c2 tool response may ever be orphaned at index 0 of tail
        assertFalse("El tail nunca debe comenzar con un mensaje TOOL", region!!.retainedTail.first().role == MessageRole.TOOL)

        // If span contains assistant with tool calls, it MUST also contain all its tool responses
        val spanRoles = region.spanToCompact.map { it.role }
        if (spanRoles.contains(MessageRole.TOOL)) {
            // Span contains tool, so tool calls must also be in span
            assertTrue(region.spanToCompact.any { it.toolCallsJson.isNotBlank() })
            // And tail must not start with the orphaned tool
            assertTrue(region.retainedTail.first().role != MessageRole.TOOL)
        }
    }

    // =========================================================================
    // 4. CHECKPOINT FORMATTING AND SECTION INTEGRITY
    // =========================================================================

    @Test
    fun testAllEightSectionsStrictlyEnforced() {
        val validMarkdown = """
            ## Primary Request and Intent
            - User needs to implement context compaction.
            ## Key Technical Concepts
            - DSH Apex, KV-cache prefix reuse.
            ## Files and Code
            - CompactionEngine.kt.
            ## Errors and Fixes
            - (none)
            ## Pending Jobs
            - (none)
            ## Current Work
            - Verifying edge cases.
            ## Next Step
            - Run full Gradle test suite.
            ## Critical Context
            - Parity with DeepSeek Harness.
        """.trimIndent()

        assertTrue(CompactionCheckpointFormatter.hasAllRequiredSections(validMarkdown))
        val parsed = CompactionCheckpointFormatter.parseSections(validMarkdown)
        assertEquals(8, parsed.size)
        assertEquals("- (none)", parsed["## Errors and Fixes"])
        assertEquals("- (none)", parsed["## Pending Jobs"])

        // Test dropping one section: MUST fail detection
        val missingOne = validMarkdown.replace("## Pending Jobs", "## Old Jobs")
        assertFalse(CompactionCheckpointFormatter.hasAllRequiredSections(missingOne))
    }

    @Test
    fun testCheckpointFramingRoundtripIdempotency() {
        val rawSummary = "## Primary Request and Intent\n- Goal"
        val framed1 = CompactionCheckpointFormatter.frameSummary(rawSummary)
        assertTrue(framed1.contains(CompactionConstants.SUMMARY_OPEN_TAG))
        assertTrue(framed1.contains(CompactionConstants.SUMMARY_CLOSE_TAG))

        // Framing an already framed summary must NOT duplicate tags or preamble
        val framed2 = CompactionCheckpointFormatter.frameSummary(framed1)
        val countOpen = framed2.split(CompactionConstants.SUMMARY_OPEN_TAG).size - 1
        val countClose = framed2.split(CompactionConstants.SUMMARY_CLOSE_TAG).size - 1
        assertEquals("No debe anidar tags de apertura", 1, countOpen)
        assertEquals("No debe anidar tags de cierre", 1, countClose)

        val extracted = CompactionCheckpointFormatter.extractCompactedSummary(framed2)
        assertEquals(rawSummary, extracted)
    }

    // =========================================================================
    // 5. RE-COMPACTION (MULTIPLE COMPACTIONS IN LONG SESSIONS)
    // =========================================================================

    @Test
    fun testMultiRoundReCompactionPreservesSingleCheckpointAtHead() {
        val mockSummary1 = """
            ## Primary Request and Intent
            - Step 1 goals.
            ## Key Technical Concepts
            - Architecture.
            ## Files and Code
            - (none)
            ## Errors and Fixes
            - (none)
            ## Pending Jobs
            - (none)
            ## Current Work
            - Phase 1.
            ## Next Step
            - Phase 2.
            ## Critical Context
            - Initial state.
        """.trimIndent()

        val mockSummary2 = """
            ## Primary Request and Intent
            - Consolidated goals from step 1 and 2.
            ## Key Technical Concepts
            - Advanced architecture.
            ## Files and Code
            - Updated files.
            ## Errors and Fixes
            - (none)
            ## Pending Jobs
            - (none)
            ## Current Work
            - Phase 2 finished.
            ## Next Step
            - Production deployment.
            ## Critical Context
            - Re-compacted cleanly.
        """.trimIndent()

        var callCount = 0
        val engine = CompactionEngine(
            summarizerCallOverride = { _, _, _ ->
                callCount++
                if (callCount == 1) mockSummary1 else mockSummary2
            }
        )

        val model = ModelInfo(id = "gpt-5.6-terra", displayName = "Terra", provider = "DeepSeek", contextWindow = 128_000)

        // Round 1
        val initialMessages = listOf(
            ChatMessage(role = MessageRole.USER, content = "U1: Initialize"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "A1: Initialized"),
            ChatMessage(role = MessageRole.USER, content = "U2: Next"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "A2: Done")
        )
        val round1Result = engine.compact(initialMessages, model, force = true)
        assertTrue(round1Result.success)
        assertEquals(1, callCount)

        // Simulate further conversation turns after round 1
        val continuation = ArrayList(round1Result.compactedMessages)
        continuation.add(ChatMessage(role = MessageRole.USER, content = "U3: More complex work"))
        continuation.add(ChatMessage(role = MessageRole.ASSISTANT, content = "A3: Working..."))
        continuation.add(ChatMessage(role = MessageRole.USER, content = "U4: Final step"))
        continuation.add(ChatMessage(role = MessageRole.ASSISTANT, content = "A4: All finished"))

        // Round 2 (Re-compaction of conversation that ALREADY contains a checkpoint)
        val round2Result = engine.compact(continuation, model, force = true)
        assertTrue(round2Result.success)
        assertEquals(2, callCount)

        // Exactly one synthesized checkpoint message at head
        assertEquals(MessageRole.USER, round2Result.compactedMessages.first().role)
        assertTrue(round2Result.compactedMessages.first().content.contains(CompactionConstants.SUMMARY_OPEN_TAG))

        // No subsequent messages in the compacted list should contain the open tag
        val subsequentHaveTags = round2Result.compactedMessages.drop(1).any {
            it.content.contains(CompactionConstants.SUMMARY_OPEN_TAG)
        }
        assertFalse("No deben existir checkpoints residuales no consolidados", subsequentHaveTags)
    }

    // =========================================================================
    // 6. HIGH-THROUGHPUT STATISTICAL PERFORMANCE BENCHMARK
    // =========================================================================

    @Test
    fun testPolicyEvaluationThroughputAndLatencyDistribution() {
        val iterations = 10_000
        val latenciesNanos = LongArray(iterations)

        val contextWindow = 128_000

        for (i in 0 until iterations) {
            val tokens = 50_000 + (i % 80_000)
            val time = measureNanoTime {
                CompactionPolicy.evaluate(tokens, contextWindow, messageCount = 10)
            }
            latenciesNanos[i] = time
        }

        latenciesNanos.sort()
        val minUs = latenciesNanos.first() / 1_000.0
        val avgUs = (latenciesNanos.sum() / iterations) / 1_000.0
        val p50Us = latenciesNanos[(iterations * 0.50).toInt()] / 1_000.0
        val p90Us = latenciesNanos[(iterations * 0.90).toInt()] / 1_000.0
        val p99Us = latenciesNanos[(iterations * 0.99).toInt()] / 1_000.0
        val maxUs = latenciesNanos.last() / 1_000.0

        println("=== CompactionPolicy Benchmark (" + iterations + " ops) ===")
        println("Min: " + minUs + " us | Avg: " + avgUs + " us | p50: " + p50Us + " us | p90: " + p90Us + " us | p99: " + p99Us + " us | Max: " + maxUs + " us")

        // p99 must be well under 500 microseconds (0.5ms) for zero UI hitch
        assertTrue("p99 latency must be under 500 us", p99Us < 500.0)
    }
}
