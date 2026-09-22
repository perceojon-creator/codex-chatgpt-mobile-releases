package com.codex.chat.mcp

import com.codex.chat.core.parser.SseStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpExecutionStateTest {

    @Test
    fun testSseStreamParserNotifiesToolCallsBeforeComplete() {
        val callOrder = mutableListOf<String>()
        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {}
            override fun onCompleteWithMetrics(fullContent: String, fullReasoning: String, metrics: com.codex.chat.core.metrics.StreamMetrics) {
                callOrder.add("complete")
            }
            override fun onToolCallsReceived(toolCalls: List<SseStreamParser.CompletedToolCall>) {
                callOrder.add("toolCalls")
            }
            override fun onError(error: Throwable) {}
        })

        val chunk1 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\\\"test.txt\\\"}\"}}]}}]}" + "\n\n"
        val chunk2 = "data: [DONE]\n\n"

        parser.feedChunk(chunk1)
        parser.feedChunk(chunk2)

        assertEquals("Debe emitir toolCalls y complete", 2, callOrder.size)
        assertEquals("toolCalls DEBE llamarse antes de complete para no apagar el boton stop prematuramente", "toolCalls", callOrder[0])
        assertEquals("complete DEBE llamarse despues de toolCalls", "complete", callOrder[1])
    }

    @Test
    fun testToolChainExecutionPreservesExecutingState() {
        var isPromptExecuting = true
        var pendingToolCalls: List<String>? = null

        fun onToolCallsDetected(tools: List<String>) {
            pendingToolCalls = tools
        }

        fun onComplete(hasTools: Boolean) {
            if (!hasTools) {
                isPromptExecuting = false
            }
        }

        // Model emits tool call
        onToolCallsDetected(listOf("read_file", "search_files"))
        onComplete(hasTools = !pendingToolCalls.isNullOrEmpty())

        // Execution state MUST remain true!
        assertTrue("El estado de ejecucion debe permanecer activo mientras se ejecutan herramientas MCP", isPromptExecuting)

        // When continuation turn has no more tools
        pendingToolCalls = null
        onComplete(hasTools = false)
        assertFalse("El estado de ejecucion se apaga cuando ya no hay mas herramientas", isPromptExecuting)
    }
}
