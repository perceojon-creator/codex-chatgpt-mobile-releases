package com.codex.chat.network

import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.repository.DynamicModelsRepository
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.BufferedReader
import java.io.InputStreamReader

class NativeCodexIntegrationTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var repo: DynamicModelsRepository

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder().build()
        repo = DynamicModelsRepository(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testFetchNativeCodexModelsSuccess() {
        val mockModelsJson = """
            {
                "count": 3,
                "models": [
                    {
                        "model": "gpt-6-astra",
                        "displayName": "GPT-6-Astra",
                        "supportedReasoningEfforts": ["low", "medium", "high", "xhigh"]
                    },
                    {
                        "model": "gpt-5.6-sol",
                        "displayName": "GPT-5.6-Sol",
                        "supportedReasoningEfforts": ["low", "medium", "high"]
                    },
                    {
                        "model": "gpt-5.5",
                        "displayName": "GPT-5.5"
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockModelsJson)
        )

        val baseUrl = server.url("/").toString().trimEnd('/')
        val result = repo.fetchNativeCodexModels(baseUrl)

        assertTrue("Expected success fetching native models", result.isSuccess)
        val models = result.getOrNull()
        assertNotNull(models)
        assertEquals(3, models?.size)

        val astra = models?.find { it.id == "gpt-6-astra" }
        assertNotNull(astra)
        assertEquals("GPT-6-Astra (Nativo)", astra?.displayName)
        assertEquals("OpenAI Codex", astra?.provider)
        assertTrue(astra?.supportsReasoning == true)
        assertEquals(ReasoningEffort.HIGH, astra?.defaultReasoningEffort)

        val gpt55 = models?.find { it.id == "gpt-5.5" }
        assertNotNull(gpt55)
        assertEquals("GPT-5.5 (Nativo)", gpt55?.displayName)
        assertTrue(gpt55?.supportsReasoning == false)
    }

    @Test
    fun testNativeCodexSseStreamParsing() {
        val ssePayload = """
            data: {"type": "reasoning", "text": "Analizando estructura del proyecto..."}

            data: {"type": "delta", "text": "¡Hola "}

            data: {"type": "delta", "text": "desde "}

            data: {"type": "delta", "text": "Codex Nativo!"}

            data: {"type": "tool_call", "name": "read_file", "args": "path: main.rs"}

            data: {"type": "done", "thread_id": "01a09870-native-uuid"}

            data: [DONE]

        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/event-stream; charset=utf-8")
                .setBody(ssePayload)
        )

        val url = server.url("/api/codex/stream").toString()
        val reqBody = """{"prompt":"test"}""".toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(url)
            .post(reqBody)
            .build()

        val resp = client.newCall(request).execute()
        assertTrue(resp.isSuccessful)

        val reader = BufferedReader(InputStreamReader(resp.body!!.byteStream(), Charsets.UTF_8))
        val content = StringBuilder()
        val reasoning = StringBuilder()
        var capturedThreadId = ""
        var capturedTool = ""

        var line: String?
        while (reader.readLine().also { line = it } != null) {
            val l = line?.trim() ?: continue
            if (l == "data: [DONE]") break
            if (l.startsWith("data:")) {
                val jsonStr = l.removePrefix("data:").trim()
                if (jsonStr.isEmpty()) continue
                val json = JSONObject(jsonStr)
                when (json.optString("type")) {
                    "delta" -> content.append(json.optString("text"))
                    "reasoning" -> reasoning.append(json.optString("text"))
                    "tool_call" -> capturedTool = json.optString("name")
                    "done" -> capturedThreadId = json.optString("thread_id")
                }
            }
        }

        assertEquals("¡Hola desde Codex Nativo!", content.toString())
        assertEquals("Analizando estructura del proyecto...", reasoning.toString())
        assertEquals("read_file", capturedTool)
        assertEquals("01a09870-native-uuid", capturedThreadId)
    }
}
