package com.codex.chat.network

import com.codex.chat.core.network.DirectE2BClient
import com.codex.chat.core.network.E2BExecutionResult
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class DirectE2BClientTest {

    private lateinit var mockServer: MockWebServer
    private lateinit var client: DirectE2BClient

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        client = DirectE2BClient(okHttpClient)
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun testEmptyCodeReturnsError() {
        val result = client.executePython("dummy_key", "session_1", "   ")
        assertFalse("No debe ejecutar código vacío", result.success)
        assertNotNull("Debe indicar mensaje de error", result.error)
        assertTrue("Error debe mencionar código vacío", result.error!!.contains("vacío"))
    }

    @Test
    fun testKillNonExistentSandboxReturnsFalse() {
        val killed = client.killSandbox("dummy_key", "non_existent_session")
        assertFalse("Matar un sandbox no existente debe retornar false", killed)
    }

    @Test
    fun testDefaultApiKeyMatchesConfiguredKey() {
        assertEquals("e2b_1084ac21c94441ec1fe7f15d06d5953c2568b6ee", DirectE2BClient.DEFAULT_API_KEY)
    }
}
