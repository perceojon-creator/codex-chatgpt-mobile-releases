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

    @Test
    fun testCompletedToolCallsCallback() {
        var receivedToolName = ""
        var receivedToolArgs = ""

        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onReasoningDelta(delta: String) {}
            override fun onContentDelta(delta: String) {}
            override fun onComplete(fullContent: String, fullReasoning: String) {}
            override fun onToolCallsReceived(toolCalls: List<SseStreamParser.CompletedToolCall>) {
                if (toolCalls.isNotEmpty()) {
                    receivedToolName = toolCalls[0].name
                    receivedToolArgs = toolCalls[0].argumentsJson
                }
            }
            override fun onError(error: Throwable) {}
        })

        val chunk1 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_123\",\"function\":{\"name\":\"get_battery_status\",\"arguments\":\"{\\\"verbose\\\":\"}}]}}]}" + "\n\n"
        val chunk2 = "data: {\"choices\":[{\"delta\":{\"tool_calls\":[{\"index\":0,\"function\":{\"arguments\":\"true}\"}}]}}]}" + "\n\n"
        val chunk3 = "data: [DONE]" + "\n\n"

        parser.feedChunk(chunk1)
        parser.feedChunk(chunk2)
        parser.feedChunk(chunk3)

        assertEquals("get_battery_status", receivedToolName)
        assertEquals("{\"verbose\":true}", receivedToolArgs)
        assertEquals(1, parser.getCompletedToolCalls().size)
    }

    @Test
    fun un_delta_con_content_nulo_no_emite_la_cadena_null() {
        val recibido = StringBuilder()
        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onContentDelta(delta: String) { recibido.append(delta) }
            override fun onReasoningDelta(delta: String) {}
            override fun onComplete(c: String, r: String) {}
            override fun onError(e: Throwable) {}
        })
        parser.feedChunk("""data: {"choices":[{"delta":{"content":null}}]}""" + "\n\n")
        parser.feedChunk("""data: {"choices":[{"delta":{"content":"Hola"}}]}""" + "\n\n")
        parser.close()

        assertEquals("Un content nulo debe producir cadena vacia, no \"null\"", "Hola", recibido.toString())
        org.junit.Assert.assertFalse(recibido.toString().contains("null"))
    }

    @Test
    fun un_delta_sin_campo_content_tampoco_emite_null() {
        val recibido = StringBuilder()
        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onContentDelta(delta: String) { recibido.append(delta) }
            override fun onReasoningDelta(delta: String) {}
            override fun onComplete(c: String, r: String) {}
            override fun onError(e: Throwable) {}
        })
        parser.feedChunk("""data: {"choices":[{"delta":{}}]}""" + "\n\n")
        parser.feedChunk("""data: {"choices":[{"delta":{"content":"Texto"}}]}""" + "\n\n")
        parser.close()

        assertEquals("Texto", recibido.toString())
    }

    @Test
    fun una_respuesta_que_empieza_por_null_se_conserva_intacta() {
        val recibido = StringBuilder()
        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onContentDelta(delta: String) { recibido.append(delta) }
            override fun onReasoningDelta(delta: String) {}
            override fun onComplete(c: String, r: String) {}
            override fun onError(e: Throwable) {}
        })
        parser.feedChunk("""data: {"choices":[{"delta":{"content":"null safety en Kotlin"}}]}""" + "\n\n")
        parser.close()

        assertEquals(
            "El texto legitimo que empieza por 'null' no debe recortarse",
            "null safety en Kotlin", recibido.toString()
        )
    }

    @Test
    fun reasoning_content_nulo_recibe_el_mismo_trato() {
        val razonamiento = StringBuilder()
        val parser = SseStreamParser(object : SseStreamParser.SseEventListener {
            override fun onContentDelta(delta: String) {}
            override fun onReasoningDelta(delta: String) { razonamiento.append(delta) }
            override fun onComplete(c: String, r: String) {}
            override fun onError(e: Throwable) {}
        })
        parser.feedChunk("""data: {"choices":[{"delta":{"reasoning_content":null}}]}""" + "\n\n")
        parser.close()

        assertEquals("", razonamiento.toString())
    }
}

