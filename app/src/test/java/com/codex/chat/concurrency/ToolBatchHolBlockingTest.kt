package com.codex.chat.concurrency

import com.codex.chat.core.concurrency.ToolBatchExecutor
import com.codex.chat.core.mcp.McpRegistry
import com.codex.chat.core.mcp.model.McpToolResult
import com.codex.chat.core.parser.SseStreamParser.CompletedToolCall
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class ToolBatchHolBlockingTest {

    @Test
    fun test_fast_tools_yield_results_before_slow_tool_finishes() {
        val completedOrder = ConcurrentLinkedQueue<String>()
        val slowStartedLatch = CountDownLatch(1)

        val registry = McpRegistry()

        val executor = object : ToolBatchExecutor(registry) {
            override fun canRunConcurrently(toolName: String): Boolean = true

            override fun executeSingleTool(tc: CompletedToolCall, isWebTainted: Boolean): McpToolResult {
                return if (tc.name == "slow_tool") {
                    slowStartedLatch.countDown()
                    Thread.sleep(300)
                    McpToolResult(callId = tc.id, toolName = tc.name, content = "slow_done")
                } else {
                    slowStartedLatch.await(1, TimeUnit.SECONDS)
                    Thread.sleep(10)
                    McpToolResult(callId = tc.id, toolName = tc.name, content = "fast_done")
                }
            }
        }

        val calls = listOf(
            CompletedToolCall(id = "c-slow", name = "slow_tool", argumentsJson = "{}"),
            CompletedToolCall(id = "c-fast-1", name = "fast_tool", argumentsJson = "{}"),
            CompletedToolCall(id = "c-fast-2", name = "fast_tool", argumentsJson = "{}")
        )

        val summary = executor.executeBatch(calls) { call, _ ->
            completedOrder.add(call.id)
        }

        assertEquals(3, summary.results.size)
        // La herramienta lenta c-slow NO debe bloquear la entrega progresiva de c-fast-1 y c-fast-2:
        val orderList = completedOrder.toList()
        assertEquals(3, orderList.size)
        assertNotEquals("c-slow no debe ser la primera en reportarse si las rápidas terminaron antes", "c-slow", orderList[0])
        assertEquals("c-slow", orderList.last()) // c-slow debe ser la última en completarse
    }
}
