package com.codex.chat.network

import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.network.CodexApiClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class MockWebServerIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var apiClient: CodexApiClient

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = CodexApiClient()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testEndToEndStreamingWithReasoningAndContent() {
        // SSE body with reasoning followed by content
        val sseBody = """
            data: {"choices": [{"delta": {"reasoning_content": "Pensando paso a paso..."}}]}

            data: {"choices": [{"delta": {"content": "Hola "}}]}

            data: {"choices": [{"delta": {"content": "desde "}}]}

            data: {"choices": [{"delta": {"content": "el proxy!"}}]}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream")
                .setBody(sseBody)
        )

        val latch = CountDownLatch(1)
        var receivedContent = ""
        var receivedReasoning = ""
        val completed = AtomicBoolean(false)

        val model = ModelInfo(
            id = "gpt-5.6-sol",
            displayName = "Sol",
            provider = "Antigravity",
            supportsReasoning = true
        )

        val history = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hola")
        )

        val baseUrl = server.url("/v1").toString().trimEnd('/')

        apiClient.executeStream(
            baseUrl = baseUrl,
            apiKey = "test-secret-key",
            model = model,
            effort = ReasoningEffort.HIGH,
            messages = history,
            activeSubagent = null,
            callback = object : CodexApiClient.StreamCallback {
                override fun onReasoningDelta(delta: String) {}
                override fun onContentDelta(delta: String) {}
                override fun onComplete(fullContent: String, fullReasoning: String) {
                    receivedContent = fullContent
                    receivedReasoning = fullReasoning
                    completed.set(true)
                    latch.countDown()
                }
                override fun onError(error: Throwable) {
                    latch.countDown()
                }
            }
        )

        val finishedInTime = latch.await(5, TimeUnit.SECONDS)
        assertTrue("La llamada en streaming debe completar en menos de 5s", finishedInTime)
        assertTrue("Callback onComplete debe haberse ejecutado", completed.get())
        assertEquals("Pensando paso a paso...", receivedReasoning)
        assertEquals("Hola desde el proxy!", receivedContent)

        // Inspect recorded HTTP request
        val recordedRequest = server.takeRequest()
        assertEquals("/v1/chat/completions", recordedRequest.path)
        assertEquals("Bearer test-secret-key", recordedRequest.getHeader("Authorization"))
        assertEquals("text/event-stream", recordedRequest.getHeader("Accept"))
    }
}
