package com.codex.chat.core.compaction

object CompactionCheckpointFormatter {

    val REQUIRED_SECTIONS = listOf(
        "## Primary Request and Intent",
        "## Key Technical Concepts",
        "## Files and Code",
        "## Errors and Fixes",
        "## Pending Jobs",
        "## Current Work",
        "## Next Step",
        "## Critical Context"
    )

    fun frameSummary(rawSummary: String): String {
        val clean = rawSummary.trim()
        val stripped = clean
            .removePrefix(CompactionConstants.CHECKPOINT_PREAMBLE)
            .trim()
            .removePrefix(CompactionConstants.SUMMARY_OPEN_TAG)
            .removeSuffix(CompactionConstants.SUMMARY_CLOSE_TAG)
            .trim()

        return buildString {
            append(CompactionConstants.CHECKPOINT_PREAMBLE)
            append("\n\n")
            append(CompactionConstants.SUMMARY_OPEN_TAG)
            append("\n")
            append(stripped)
            append("\n")
            append(CompactionConstants.SUMMARY_CLOSE_TAG)
        }
    }

    fun isCheckpointMessage(content: String): Boolean {
        return content.contains(CompactionConstants.SUMMARY_OPEN_TAG) &&
               content.contains(CompactionConstants.SUMMARY_CLOSE_TAG)
    }

    fun extractCompactedSummary(content: String): String? {
        val start = content.indexOf(CompactionConstants.SUMMARY_OPEN_TAG)
        if (start == -1) return null
        val contentStart = start + CompactionConstants.SUMMARY_OPEN_TAG.length
        val end = content.indexOf(CompactionConstants.SUMMARY_CLOSE_TAG, contentStart)
        if (end == -1) return null
        return content.substring(contentStart, end).trim()
    }

    fun hasAllRequiredSections(summaryMarkdown: String): Boolean {
        return REQUIRED_SECTIONS.all { summaryMarkdown.contains(it, ignoreCase = true) }
    }

    fun parseSections(summaryMarkdown: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = summaryMarkdown.lines()
        var currentHeading: String? = null
        val currentBuffer = StringBuilder()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("## ")) {
                if (currentHeading != null) {
                    result[currentHeading] = currentBuffer.toString().trim()
                    currentBuffer.setLength(0)
                }
                currentHeading = trimmed
            } else if (currentHeading != null) {
                currentBuffer.append(line).append("\n")
            }
        }
        if (currentHeading != null) {
            result[currentHeading] = currentBuffer.toString().trim()
        }
        return result
    }
}