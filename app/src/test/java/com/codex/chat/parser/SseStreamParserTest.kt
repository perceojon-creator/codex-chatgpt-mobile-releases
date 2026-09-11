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
        val reasoningChunks = mutableListOf<String>()
        val contentChunks = mutableListOf<String>()
        var finalContent = ""
        var finalReasoning = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {
                reasoningChunks.add(delta)
            }
            override fun onContentDelta(delta: String) {
                contentChunks.add(delta)
            }
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
    fun testChunkFragmentation() {
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

        parser.feedChunk("data: {\"choices\": [{\"del")
        parser.feedChunk("ta\": {\"content\": \"Fragmento unido.\"}}]}" + "\n\n")
        parser.feedChunk("data: [DONE]" + "\n\n")

        assertEquals("Fragmento unido.", finalContent)
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
}