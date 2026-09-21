package com.codex.chat.compaction

import com.codex.chat.core.compaction.CompactionConstants
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionConstantsTest {

    @Test
    fun testPreambleAndTagsMatchDshSpecification() {
        assertTrue(CompactionConstants.CHECKPOINT_PREAMBLE.contains("automatically generated checkpoint condensing"))
        assertEquals("<compacted-summary>", CompactionConstants.SUMMARY_OPEN_TAG)
        assertEquals("</compacted-summary>", CompactionConstants.SUMMARY_CLOSE_TAG)
    }

    @Test
    fun testThresholdRatioIsNinetyPercent() {
        assertEquals(0.90, CompactionConstants.DEFAULT_THRESHOLD_RATIO, 0.0001)
    }

    @Test
    fun testAllEightMarkdownSectionsPresentInInstruction() {
        val inst = CompactionConstants.COMPACTION_INSTRUCTION
        assertTrue(inst.contains("## Primary Request and Intent"))
        assertTrue(inst.contains("## Key Technical Concepts"))
        assertTrue(inst.contains("## Files and Code"))
        assertTrue(inst.contains("## Errors and Fixes"))
        assertTrue(inst.contains("## Pending Jobs"))
        assertTrue(inst.contains("## Current Work"))
        assertTrue(inst.contains("## Next Step"))
        assertTrue(inst.contains("## Critical Context"))
    }
}