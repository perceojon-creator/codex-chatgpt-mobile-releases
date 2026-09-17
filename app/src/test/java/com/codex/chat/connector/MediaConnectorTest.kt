package com.codex.chat.connector

import com.codex.chat.core.connector.*
import com.codex.chat.core.media.VisualMediaParser
import com.codex.chat.core.media.VisualMediaType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class MediaConnectorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: MediaConnectorClient
    private lateinit var manager: MediaConnectorManager

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = MediaConnectorClient()
        manager = MediaConnectorManager(null, client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun testResolveEndpointUrl() {
        assertEquals(
            "http://127.0.0.1:8317/v1/images/generations",
            client.resolveEndpointUrl("http://127.0.0.1:8317/v1", "/images/generations")
        )
        assertEquals(
            "http://127.0.0.1:8317/v1/images/generations",
            client.resolveEndpointUrl("http://127.0.0.1:8317/v1/", "images/generations")
        )
        assertEquals(
            "http://127.0.0.1:8317/v1/images/generations",
            client.resolveEndpointUrl("http://127.0.0.1:8317", "/images/generations")
        )
        assertEquals(
            "http://127.0.0.1:8317/v1/images/generations",
            client.resolveEndpointUrl("http://127.0.0.1:8317/v1/images/generations", "/images/generations")
        )
        assertEquals(
            "http://127.0.0.1:8317/v1/videos/generations",
            client.resolveEndpointUrl("http://127.0.0.1:8317/v1", "/videos/generations")
        )
    }

    @Test
    fun testExtractImageDataOpenAIFormat() {
        val jsonUrl = """{"created": 1740000000, "data": [{"url": "https://example.com/monkey.png"}]}"""
        val (url, b64) = client.extractImageData(jsonUrl)
        assertEquals("https://example.com/monkey.png", url)
        assertNull(b64)

        val jsonB64 = """{"created": 1740000000, "data": [{"b64_json": "iVBORw0KGgoAAAANSUhEUgAA"}]}"""
        val (url2, b642) = client.extractImageData(jsonB64)
        assertNull(url2)
        assertEquals("iVBORw0KGgoAAAANSUhEUgAA", b642)
    }

    @Test
    fun testExtractImageDataCandidatesFormat() {
        val jsonCandidates = """{"candidates": [{"image": "iVBORw0KGgoAAAANSUhEUgAA"}]}"""
        val (url, b64) = client.extractImageData(jsonCandidates)
        assertNull(url)
        assertEquals("iVBORw0KGgoAAAANSUhEUgAA", b64)
    }

    @Test
    fun testExtractVideoData() {
        val json1 = """{"url": "https://example.com/video.mp4"}"""
        assertEquals("https://example.com/video.mp4", client.extractVideoData(json1))

        val json2 = """{"data": [{"url": "https://example.com/veo.mp4"}]}"""
        assertEquals("https://example.com/veo.mp4", client.extractVideoData(json2))
    }

    @Test
    fun testFormatImageMarkdownAndVisualMediaParserParity() {
        val markdown = client.formatImageMarkdown(
            prompt = "un mono bailando",
            provider = ConnectorProvider.GOOGLE_FLOW.displayName,
            model = "imagen-3.1",
            url = "https://example.com/dancing_monkey.png",
            b64 = null
        )

        assertTrue(markdown.contains("![un mono bailando](https://example.com/dancing_monkey.png)"))
        assertTrue(markdown.contains("Google Flow"))
        assertTrue(markdown.contains("imagen-3.1"))

        // Guarantee VisualMediaParser parses it into an Image!
        val parsed = VisualMediaParser.parse(markdown)
        assertTrue("VisualMediaParser must recognize generated image", parsed.hasMedia)
        assertEquals(VisualMediaType.IMAGE, parsed.type)
        assertEquals("https://example.com/dancing_monkey.png", parsed.mediaSource)
    }

    @Test
    fun testFormatVideoMarkdownAndVisualMediaParserParity() {
        val markdown = client.formatVideoMarkdown(
            prompt = "un mono bailando salsa",
            provider = ConnectorProvider.GOOGLE_FLOW.displayName,
            model = "veo-3.1",
            videoUrl = "https://example.com/dancing_monkey.mp4"
        )

        assertTrue(markdown.contains("dancing_monkey.mp4"))
        assertTrue(markdown.contains("Google Flow"))
        assertTrue(markdown.contains("veo-3.1"))

        // Guarantee VisualMediaParser parses it into a Video!
        val parsed = VisualMediaParser.parse(markdown)
        assertTrue("VisualMediaParser must recognize generated video", parsed.hasMedia)
        assertEquals(VisualMediaType.VIDEO, parsed.type)
        assertTrue(parsed.mediaSource.contains("dancing_monkey.mp4"))
    }

    @Test
    fun testGenerateImageMockWebServerSuccess() {
        val mockResponseBody = """
            {
              "created": 1740000000,
              "data": [
                {
                  "url": "https://images.proxy.local/dancing_monkey_1024.png"
                }
              ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(mockResponseBody)
        )

        val serverUrl = server.url("/v1").toString()
        val config = MediaConnectorConfig(
            provider = ConnectorProvider.GOOGLE_FLOW,
            baseUrl = serverUrl,
            apiKey = "proxy-pool",
            imageModel = "imagen-3.1"
        )

        val result = client.generateImage("un mono bailando", config)

        assertTrue(result.isSuccess)
        assertEquals(MediaConnectorType.IMAGE, result.type)
        assertEquals("Google Flow", result.provider)
        assertEquals("un mono bailando", result.prompt)
        assertEquals("imagen-3.1", result.model)
        assertEquals("https://images.proxy.local/dancing_monkey_1024.png", result.mediaUrl)
        assertTrue(result.markdownContent.contains("![un mono bailando]"))
        assertTrue(result.markdownContent.contains("Google Flow"))

        // Verify request sent to MockWebServer
        val recordedRequest = server.takeRequest(5, TimeUnit.SECONDS)
        assertNotNull(recordedRequest)
        assertEquals("POST", recordedRequest!!.method)
        assertEquals("/v1/images/generations", recordedRequest.path)
        assertEquals("Bearer proxy-pool", recordedRequest.getHeader("Authorization"))
        val bodyText = recordedRequest.body.readUtf8()
        assertTrue(bodyText.contains("un mono bailando"))
        assertTrue(bodyText.contains("imagen-3.1"))
    }

    @Test
    fun testGenerateImageMockWebServerFailure() {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"error": {"message": "imagefx upstream 401 unauthenticated"}}""")
        )

        val serverUrl = server.url("/v1").toString()
        val config = MediaConnectorConfig(
            provider = ConnectorProvider.GOOGLE_FLOW,
            baseUrl = serverUrl
        )

        val result = client.generateImage("un mono bailando", config)

        assertFalse(result.isSuccess)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("Google Flow"))
        assertTrue(result.errorMessage!!.contains("imagefx upstream 401 unauthenticated"))
    }

    @Test
    fun testMediaConnectorManagerTriggers() {
        assertFalse(manager.isConnectorTrigger(""))
        assertFalse(manager.isConnectorTrigger("Hola, cómo estás?"))

        assertTrue(manager.isConnectorTrigger("🎨 un mono bailando"))
        assertTrue(manager.isConnectorTrigger("🎬 video de una playa al atardecer"))
        assertTrue(manager.isConnectorTrigger("/flow un paisaje hermoso"))
        assertTrue(manager.isConnectorTrigger("/imagen un gato volador"))
        assertTrue(manager.isConnectorTrigger("/veo un carro en la pista"))

        assertEquals(MediaConnectorType.IMAGE, manager.resolveConnectorType("/imagen gato"))
        assertEquals(MediaConnectorType.VIDEO, manager.resolveConnectorType("/veo carro"))

        assertEquals("un mono bailando", manager.extractPrompt("🎨 [Google Flow - Imagen 3.1]: un mono bailando"))
        assertEquals("un mono bailando", manager.extractPrompt("🎨 [Imagen 3.1]: un mono bailando"))
        assertEquals("un perro corriendo", manager.extractPrompt("/imagen un perro corriendo"))
        assertEquals("un dron volando", manager.extractPrompt("🎬 [Google Flow - Veo 3.1]: un dron volando"))

        // Activation toggle
        manager.activate(MediaConnectorType.IMAGE, ConnectorProvider.GOOGLE_FLOW)
        assertTrue(manager.isConnectorActive)
        assertEquals(ConnectorProvider.GOOGLE_FLOW, manager.activeProvider)
        assertTrue("When active, any text is treated as connector trigger", manager.isConnectorTrigger("mono bailando"))

        manager.deactivate()
        assertFalse(manager.isConnectorActive)
    }
}
