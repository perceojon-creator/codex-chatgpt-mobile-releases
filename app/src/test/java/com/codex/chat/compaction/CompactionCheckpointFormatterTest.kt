package com.codex.chat.compaction

import com.codex.chat.core.compaction.CompactionCheckpointFormatter
import com.codex.chat.core.compaction.CompactionConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionCheckpointFormatterTest {

    private val sampleSummary = """
        ## Primary Request and Intent
        - User asked to automate YouTube playback.

        ## Key Technical Concepts
        - Android Accessibility APIs, MediaProjection.

        ## Files and Code
        - MainActivity.kt: task navigation and lifecycle.

        ## Errors and Fixes
        - Fixed ESTOP false positive in background.

        ## Pending Jobs
        - (none)

        ## Current Work
        - Context compaction engine integration.

        ## Next Step
        - Run unit tests and deploy OTA.

        ## Critical Context
        - Target is Pixel Android 15 emulator.
    """.trimIndent()

    @Test
    fun testFrameSummaryWrapsPreambleAndTags() {
        val framed = CompactionCheckpointFormatter.frameSummary(sampleSummary)
        assertTrue(framed.startsWith(CompactionConstants.CHECKPOINT_PREAMBLE))
        assertTrue(framed.contains(CompactionConstants.SUMMARY_OPEN_TAG))
        assertTrue(framed.endsWith(CompactionConstants.SUMMARY_CLOSE_TAG))
        assertTrue(CompactionCheckpointFormatter.isCheckpointMessage(framed))
    }

    @Test
    fun testExtractCompactedSummaryRetrievesBody() {
        val framed = CompactionCheckpointFormatter.frameSummary(sampleSummary)
        val extracted = CompactionCheckpointFormatter.extractCompactedSummary(framed)
        assertNotNull(extracted)
        assertTrue(extracted!!.contains("## Primary Request and Intent"))
        assertTrue(extracted.contains("## Critical Context"))
    }

    @Test
    fun testHasAllRequiredSectionsDetectsAllEight() {
        assertTrue(CompactionCheckpointFormatter.hasAllRequiredSections(sampleSummary))

        val incomplete = "## Primary Request and Intent\n- Goal\n## Current Work\n- Coding"
        assertFalse(CompactionCheckpointFormatter.hasAllRequiredSections(incomplete))
    }

    @Test
    fun testParseSectionsCreatesMap() {
        val sections = CompactionCheckpointFormatter.parseSections(sampleSummary)
        assertEquals(8, sections.size)
        assertTrue(sections.containsKey("## Primary Request and Intent"))
        assertTrue(sections["## Primary Request and Intent"]!!.contains("YouTube playback"))
        assertEquals("- (none)", sections["## Pending Jobs"])
    }
}