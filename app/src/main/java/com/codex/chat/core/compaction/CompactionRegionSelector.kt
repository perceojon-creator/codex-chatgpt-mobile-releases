package com.codex.chat.core.compaction

import com.codex.chat.core.metrics.ContextMetricsCalculator
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole

data class CompactionRegion(
    val spanToCompact: List<ChatMessage>,
    val retainedTail: List<ChatMessage>,
    val startCutIndex: Int,
    val endCutIndex: Int,
    val spanTokens: Int,
    val tailTokens: Int
)

object CompactionRegionSelector {

    fun estimateMessageTokens(msg: ChatMessage): Int {
        var tokens = ContextMetricsCalculator.estimateTextTokens(msg.content)
        if (msg.hasReasoning) {
            tokens += ContextMetricsCalculator.estimateTextTokens(msg.reasoningContent)
        }
        if (msg.toolCallsJson.isNotBlank()) {
            tokens += ContextMetricsCalculator.estimateJsonTokens(msg.toolCallsJson)
        }
        for (att in msg.attachments) {
            tokens += if (att.isImage) 1600 else ContextMetricsCalculator.estimateTextTokens(att.fileName) + 150
        }
        return tokens.coerceAtLeast(1)
    }

    fun isToolPairingBalancedAtCut(messages: List<ChatMessage>, cutIndex: Int): Boolean {
        if (cutIndex <= 0 || cutIndex >= messages.size) return true
        val nextMsg = messages[cutIndex]
        if (nextMsg.role == MessageRole.TOOL) {
            return false
        }
        val prevMsg = messages[cutIndex - 1]
        if (prevMsg.role == MessageRole.ASSISTANT && prevMsg.toolCallsJson.isNotBlank()) {
            if (nextMsg.role == MessageRole.TOOL) {
                return false
            }
        }
        return true
    }

    fun selectRegion(
        messages: List<ChatMessage>,
        contextWindow: Int,
        retainRatio: Double = CompactionConstants.DEFAULT_RETAIN_RATIO,
        minRetainMessages: Int = CompactionConstants.MIN_RETAIN_MESSAGES,
        force: Boolean = false
    ): CompactionRegion? {
        if (messages.size < CompactionConstants.MIN_MESSAGES_TO_COMPACT) {
            return null
        }

        val retainBudgetTokens = if (contextWindow > 0) {
            (contextWindow * retainRatio).toInt().coerceAtLeast(1)
        } else 0

        var accumulatedTailTokens = 0
        var retainFromIdx = messages.size

        for (i in messages.size - 1 downTo 0) {
            val msgTokens = estimateMessageTokens(messages[i])
            accumulatedTailTokens += msgTokens
            retainFromIdx = i
            val retainedCount = messages.size - i
            if (retainedCount >= minRetainMessages) {
                if (force || accumulatedTailTokens >= retainBudgetTokens) {
                    break
                }
            }
        }

        if (retainFromIdx <= 0) {
            retainFromIdx = (messages.size - minRetainMessages).coerceAtLeast(1)
        }

        while (retainFromIdx > 0 && !isToolPairingBalancedAtCut(messages, retainFromIdx)) {
            retainFromIdx--
        }

        var preferredUserCut = retainFromIdx
        while (preferredUserCut > 0 && messages[preferredUserCut].role != MessageRole.USER) {
            if (isToolPairingBalancedAtCut(messages, preferredUserCut - 1)) {
                preferredUserCut--
            } else {
                break
            }
        }
        if (preferredUserCut > 0 && messages[preferredUserCut].role == MessageRole.USER) {
            retainFromIdx = preferredUserCut
        }

        if (retainFromIdx <= 0 || retainFromIdx >= messages.size) {
            return null
        }

        val span = messages.subList(0, retainFromIdx)
        val tail = messages.subList(retainFromIdx, messages.size)

        if (span.isEmpty() || tail.isEmpty()) {
            return null
        }

        var spanTok = 0
        for (m in span) spanTok += estimateMessageTokens(m)

        var tailTok = 0
        for (m in tail) tailTok += estimateMessageTokens(m)

        return CompactionRegion(
            spanToCompact = ArrayList(span),
            retainedTail = ArrayList(tail),
            startCutIndex = 0,
            endCutIndex = retainFromIdx - 1,
            spanTokens = spanTok,
            tailTokens = tailTok
        )
    }
}