package com.codex.chat.parser

import com.codex.chat.core.parser.SseStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class SseStreamParserTest {

    @Test
    fun testStandardContentStreaming() {
        val contentChunks = mutableListOf<String>()
        var completedContent = ""
        var completedReasoning = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {
                contentChunks.add(delta)
            }
            override fun onComplete(fullContent: String, fullReasoning: String) {
                completedContent = fullContent
                completedReasoning = fullReasoning
            }
            override fun onError(error: Throwable) {
                throw AssertionError("Unexpected error: " + error.message)
            }
        })

        val line1 = "data: {\"choices\": [{\"delta\": {\"content\": \"Hola \"}}]}" + "\n\n"
        val line2 = "data: {\"choices\": [{\"delta\": {\"content\": \"mundo!\"}}]}" + "\n\n"
        val line3 = "data: [DONE]" + "\n\n"

        parser.feedChunk(line1)
        parser.feedChunk(line2)
        parser.feedChunk(line3)

        assertEquals("Hola mundo!", completedContent)
        assertEquals("", completedReasoning)
        assertEquals(listOf("Hola ", "mundo!"), contentChunks)
    }

    @Test
    fun testExplicitReasoningExtraction() {
        var finalContent = ""
        var finalReasoning = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {
                finalContent = fullContent
                finalReasoning = fullReasoning
            }
            override fun onError(error: Throwable) {
                throw AssertionError("Unexpected error: " + error.message)
            }
        })

        val line1 = "data: {\"choices\": [{\"delta\": {\"reasoning_content\": \"Paso 1: Analizar. \"}}]}" + "\n\n"
        val line2 = "data: {\"choices\": [{\"delta\": {\"reasoning_content\": \"Paso 2: Calcular.\"}}]}" + "\n\n"
        val line3 = "data: {\"choices\": [{\"delta\": {\"content\": \"Resultado 42.\"}}]}" + "\n\n"
        val line4 = "data: [DONE]" + "\n\n"

        parser.feedChunk(line1)
        parser.feedChunk(line2)
        parser.feedChunk(line3)
        parser.feedChunk(line4)

        assertEquals("Paso 1: Analizar. Paso 2: Calcular.", finalReasoning)
        assertEquals("Resultado 42.", finalContent)
    }

    @Test
    fun testInlineThinkingTagSeparation() {
        var finalContent = ""
        var finalReasoning = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {
                finalContent = fullContent
                finalReasoning = fullReasoning
            }
            override fun onError(error: Throwable) {
                throw AssertionError("Unexpected error: " + error.message)
            }
        })

        val line1 = "data: {\"choices\": [{\"delta\": {\"content\": \"<think>Pensamiento interno</think>Respuesta final.\"}}]}" + "\n\n"
        val line2 = "data: [DONE]" + "\n\n"

        parser.feedChunk(line1)
        parser.feedChunk(line2)

        assertEquals("Pensamiento interno", finalReasoning)
        assertEquals("Respuesta final.", finalContent)
    }

    @Test
    fun testPartialTagFragmentationAcrossTcpPackets() {
        var finalContent = ""
        var finalReasoning = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {
                finalContent = fullContent
                finalReasoning = fullReasoning
            }
            override fun onError(error: Throwable) {
                throw AssertionError("Unexpected error: " + error.message)
            }
        })

        // Tag <think> fragmented across TCP packet boundaries: "<th" then "ink>"
        parser.feedChunk("data: {\"choices\": [{\"delta\": {\"content\": \"<th") // Chunk 1
        parser.feedChunk("ink>Razonamiento profundo</th")                                // Chunk 2 (splits </think>)
        parser.feedChunk("ought>Respuesta final visible.\"}}]}" + "\n\n")            // Chunk 3 (closes tag with </thought>)
        parser.feedChunk("data: [DONE]" + "\n\n")

        assertEquals("Razonamiento profundo", finalReasoning)
        assertEquals("Respuesta final visible.", finalContent)
    }

    @Test
    fun testPreservePythonIndentationInStreaming() {
        var finalContent = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {
                finalContent = fullContent
            }
            override fun onError(error: Throwable) {
                throw AssertionError("Unexpected error: " + error.message)
            }
        })

        // Four leading spaces in Python code must be preserved
        val pythonLine = "    def solve():" + "\n        return True"
        val jsonStr = "{\"choices\": [{\"delta\": {\"content\": \"    def solve():\\n        return True\"}}]}"
        parser.feedChunk("data: " + jsonStr + "\n\n")
        parser.feedChunk("data: [DONE]" + "\n\n")

        assertEquals(pythonLine, finalContent)
    }

    @Test
    fun testErrorPayloadHandling() {
        val errorTriggered = AtomicBoolean(false)
        var errorMsg = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {}
            override fun onError(error: Throwable) {
                errorTriggered.set(true)
                errorMsg = error.message ?: ""
            }
        })

        val line1 = "data: {\"error\": {\"message\": \"Modelo sobrecargado\"}}" + "\n\n"
        parser.feedChunk(line1)

        assertTrue(errorTriggered.get())
        assertEquals("Modelo sobrecargado", errorMsg)
    }

    @Test
    fun testToolCallsDeltaStreaming() {
        val contentDeltas = mutableListOf<String>()
        var finalContent = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {
                contentDeltas.add(delta)
            }
            override fun onComplete(fullContent: String, fullReasoning: String) {
                finalContent = fullContent
            }
            override fun onError(error: Throwable) {
                throw AssertionError("Unexpected error: " + error.message)
            }
        })

        val chunk1 = "data: {\"choices\": [{\"delta\": {\"tool_calls\": [{\"index\": 0, \"function\": {\"name\": \"get_battery_status\", \"arguments\": \"{\"}}]}}]}" + "\n\n"
        val chunk2 = "data: {\"choices\": [{\"delta\": {\"tool_calls\": [{\"index\": 0, \"function\": {\"arguments\": \"}\"}}]}}]}" + "\n\n"
        val chunk3 = "data: [DONE]" + "\n\n"

        parser.feedChunk(chunk1)
        parser.feedChunk(chunk2)
        parser.feedChunk(chunk3)

        assertTrue("Debe contener la llamada a la herramienta", finalContent.contains("get_battery_status"))
        assertTrue("Debe contener los argumentos", finalContent.contains("{}"))
    }
}
