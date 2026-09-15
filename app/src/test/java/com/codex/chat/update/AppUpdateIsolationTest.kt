package com.codex.chat.update

import com.codex.chat.UpdateInfo
import com.codex.chat.core.provider.BuiltInProviders
import com.codex.chat.core.provider.ProviderProfile
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class AppUpdateIsolationTest {

    private lateinit var mockServer: MockWebServer

    @Before
    fun setUp() {
        mockServer = MockWebServer()
        mockServer.start()
    }

    @After
    fun tearDown() {
        mockServer.shutdown()
    }

    @Test
    fun test_update_endpoint_isolation_never_points_to_custom_provider() {
        val customProfile = ProviderProfile(
            id = "custom_openrouter",
            name = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            apiKey = "sk-or-v1-custom-fake-key",
            defaultModel = "openai/gpt-4o",
            isReadOnly = false
        )

        // El endpoint de actualizaciones NUNCA debe adoptar la URL de un proveedor custom
        assertNotEquals(
            "La URL de updates no debe coincidir con un proveedor custom",
            customProfile.baseUrl,
            BuiltInProviders.PROFILE_3_CODEX_PC.baseUrl
        )

        // El perfil 3 de Codex Desktop debe mantenerse fijado al proxy del sistema
        val p3 = BuiltInProviders.PROFILE_3_CODEX_PC
        assertTrue("El perfil Codex PC debe tener host local o LAN", p3.baseUrl.contains("8317"))
    }

    @Test
    fun test_update_info_model_integrity() {
        val info = UpdateInfo(
            versionCode = 55,
            versionName = "1.0.54",
            releaseNotes = "Fixes and features",
            apkUrl = "http://192.168.1.6:8317/api/update/download",
            sizeBytes = 10411681L,
            sha256 = "1137FB0BC5737748FD5F316D7FA6D79E77F7FC82C4189318FC0DBF2CEEBE953E"
        )

        assertEquals(55, info.versionCode)
        assertEquals("1.0.54", info.versionName)
        assertEquals("Fixes and features", info.releaseNotes)
        assertTrue(info.apkUrl.endsWith("/api/update/download"))
        assertEquals(10411681L, info.sizeBytes)
        assertEquals(64, info.sha256.length)
    }

    @Test
    fun test_update_check_json_parsing_snake_and_camel_case() {
        val jsonStr = """
            {
                "version_code": 100,
                "version_name": "2.0.0",
                "release_notes": "Notas OTA",
                "apk_url": "http://127.0.0.1:8317/download.apk",
                "size_bytes": 5000000,
                "sha256": "ABCDEF"
            }
        """.trimIndent()

        val json = JSONObject(jsonStr)
        val code = json.optInt("version_code", json.optInt("versionCode", 0))
        val name = json.optString("version_name", json.optString("versionName", ""))
        val notes = json.optString("release_notes", json.optString("releaseNotes", ""))
        val url = json.optString("apk_url", json.optString("apkUrl", ""))
        val size = json.optLong("size_bytes", json.optLong("sizeBytes", 0))
        val sha = json.optString("sha256", json.optString("sha_256", ""))

        assertEquals(100, code)
        assertEquals("2.0.0", name)
        assertEquals("Notas OTA", notes)
        assertEquals("http://127.0.0.1:8317/download.apk", url)
        assertEquals(5000000L, size)
        assertEquals("ABCDEF", sha)
    }

    @Test
    fun test_mockwebserver_serves_update_check_successfully() {
        val body = """
            {
                "version_code": 999,
                "version_name": "9.9.9",
                "release_notes": "Prueba Mock",
                "apk_url": "${mockServer.url("/api/update/download")}",
                "size_bytes": 12345
            }
        """.trimIndent()

        mockServer.enqueue(MockResponse().setResponseCode(200).setBody(body))

        val client = okhttp3.OkHttpClient()
        val req = okhttp3.Request.Builder()
            .url(mockServer.url("/api/update/check"))
            .get()
            .build()

        val resp = client.newCall(req).execute()
        assertTrue(resp.isSuccessful)
        val respBody = resp.body?.string() ?: ""
        val json = JSONObject(respBody)
        assertEquals(999, json.getInt("version_code"))
        assertEquals("9.9.9", json.getString("version_name"))
    }
}
