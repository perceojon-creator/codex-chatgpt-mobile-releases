package com.codex.chat.agent

import com.codex.chat.agent.core.AgentAction
import com.codex.chat.agent.core.GroundingPromptBuilder
import com.codex.chat.agent.network.AgentVisionClient
import com.codex.chat.agent.network.VisionResponseParser
import com.codex.chat.core.security.EstopSentinel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class AgentVisionClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: AgentVisionClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        EstopSentinel.disengage()

        val serverUrl = mockServer.url("/v1").toString().trimEnd('/')
        client = AgentVisionClient(
            baseUrl = serverUrl,
            apiKey = "test-token",
            okHttpClient = OkHttpClient(),
            promptBuilder = GroundingPromptBuilder(),
            parser = VisionResponseParser(),
            screenWidth = 1080,
            screenHeight = 1920,
            model = "gemini-3.8-flash",
            reasoningEffort = "high"
        )
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
        EstopSentinel.disengage()
    }

    @Test
    fun testSuccessfulVisionCompletionReturnsParsedTapActionAndSendsReasoningEffort() = runBlocking {
        val mockResponseBody = """
            {
              "id": "chatcmpl-test",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "{\"action\":\"tap\",\"x\":540,\"y\":960,\"reason\":\"click center search button\"}"
                  }
                }
              ]
            }
        """.trimIndent()

        mockServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(mockResponseBody)
                .setHeader("Content-Type", "application/json")
        )

        val action = client.think(
            goal = "Search for YouTube video",
            stepIndex = 1,
            screenshotBase64 = "dGVzdF9pbWFnZQ==",
            uiHierarchy = "{\"elements\":[]}",
            actionHistory = emptyList()
        )

        assertTrue("Expected Tap action, got: " + action, action is AgentAction.Tap)
        val tap = action as AgentAction.Tap
        assertEquals(540, tap.x)
        assertEquals(960, tap.y)
        assertEquals("click center search button", tap.reason)

        val recordedRequest = mockServer.takeRequest()
        assertEquals("/v1/chat/completions", recordedRequest.path)
        assertEquals("Bearer test-token", recordedRequest.getHeader("Authorization"))
        val body = recordedRequest.body.readUtf8()
        assertTrue("Expected image_url in body", body.contains("data:image/jpeg;base64,dGVzdF9pbWFnZQ=="))
        assertTrue("Expected goal in body", body.contains("Search for YouTube video"))
        assertTrue("Expected reasoning_effort in body", body.contains("\"reasoning_effort\":\"high\""))
        assertTrue("Expected model gemini-3.8-flash in body", body.contains("\"model\":\"gemini-3.8-flash\""))
    }

    @Test
    fun testHttpErrorReturnsFailAction() = runBlocking {
        mockServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("{\"error\":{\"message\":\"Upstream model overloaded\"}}")
        )

        val action = client.think(
            goal = "Do task",
            stepIndex = 1,
            screenshotBase64 = "",
            uiHierarchy = "{}",
            actionHistory = emptyList()
        )

        assertTrue("Expected Fail action on HTTP 500, got: " + action, action is AgentAction.Fail)
        val fail = action as AgentAction.Fail
        assertTrue("Expected error to mention 500", fail.error.contains("500"))
    }

    @Test
    fun testEstopEngagedThrowsSecurityExceptionImmediately() {
        EstopSentinel.engage("Test emergency pause")
        try {
            runBlocking {
                client.think(
                    goal = "Do task",
                    stepIndex = 1,
                    screenshotBase64 = "",
                    uiHierarchy = "{}",
                    actionHistory = emptyList()
                )
            }
            fail("Expected EstopEngagedException")
        } catch (e: Throwable) {
            assertTrue("Expected security exception, got " + e,
                e.javaClass.name.contains("Estop") || e is SecurityException)
        }
    }
}