package com.codex.chat.mcp

import android.content.ContextWrapper
import android.net.Uri
import android.provider.CalendarContract
import android.provider.CallLog
import android.provider.ContactsContract
import android.telephony.SmsManager
import com.codex.chat.core.mcp.model.McpToolCallRequest
import com.codex.chat.core.mcp.server.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class McpServersDeepAuditTest {

    private lateinit var tempDir: File
    private lateinit var mockWebServer: MockWebServer

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("mcp_test_files").toFile()
        mockWebServer = MockWebServer()
        mockWebServer.start()
    }

    @After
    fun tearDown() {
        mockWebServer.shutdown()
        tempDir.deleteRecursively()
    }

    // ==========================================
    // 1. CalculatorMcpServer Tests & Benchmarks
    // ==========================================

    @Test
    fun testCalculatorIndependenceAndCorrectness() {
        val server = CalculatorMcpServer()
        val constructors = CalculatorMcpServer::class.java.constructors
        assertEquals("CalculatorMcpServer must have 0-arg constructor", 1, constructors.size)
        assertEquals(0, constructors[0].parameterCount)

        // Math evaluations
        val call1 = server.executeTool(McpToolCallRequest("1", "evaluate_math", JSONObject().put("expression", "2^16 - 1").toString()))
        assertFalse(call1.isError)
        assertTrue(call1.content.contains("65535"))

        val call2 = server.executeTool(McpToolCallRequest("2", "evaluate_math", JSONObject().put("expression", "sqrt(144) + 15 * 3").toString()))
        assertFalse(call2.isError)
        assertTrue(call2.content.contains("57"))

        val call3 = server.executeTool(McpToolCallRequest("3", "evaluate_math", JSONObject().put("expression", "sin(0) + cos(0)").toString()))
        assertFalse(call3.isError)
        assertTrue(call3.content.contains("1.0"))

        val divZero = server.executeTool(McpToolCallRequest("4", "evaluate_math", JSONObject().put("expression", "10 / 0").toString()))
        assertTrue(divZero.isError)
        assertTrue(divZero.content.contains("División por cero"))

        // Hashes
        val hashCall = server.executeTool(McpToolCallRequest("5", "compute_hash", JSONObject().put("text", "CodexApex").put("algorithm", "SHA-256").toString()))
        assertFalse(hashCall.isError)
        assertTrue(hashCall.content.contains("SHA-256 Hash ="))

        // Base64
        val enc = server.executeTool(McpToolCallRequest("6", "base64_codec", JSONObject().put("text", "Android MCP Engine").put("operation", "encode").toString()))
        assertFalse(enc.isError)
        assertTrue(enc.content.contains("Base64 Encoded: QW5kcm9pZCBNQ1AgRW5naW5l"))

        val dec = server.executeTool(McpToolCallRequest("7", "base64_codec", JSONObject().put("text", "QW5kcm9pZCBNQ1AgRW5naW5l").put("operation", "decode").toString()))
        assertFalse(dec.isError)
        assertTrue(dec.content.contains("Base64 Decoded: Android MCP Engine"))
    }

    @Test
    fun testCalculatorBenchmarkPerformance() {
        val server = CalculatorMcpServer()
        val iterations = 5000
        val latencies = LongArray(iterations)

        // Warm up
        for (i in 0 until 500) {
            server.executeTool(McpToolCallRequest("w", "evaluate_math", "{\"expression\": \"2^8 + sqrt(64) * (10 - 2)\"}"))
        }

        val startTotal = System.nanoTime()
        for (i in 0 until iterations) {
            val s = System.nanoTime()
            server.executeTool(McpToolCallRequest(i.toString(), "evaluate_math", "{\"expression\": \"2^8 + sqrt(64) * (10 - 2)\"}"))
            latencies[i] = System.nanoTime() - s
        }
        val totalTimeMs = (System.nanoTime() - startTotal) / 1_000_000.0

        latencies.sort()
        val minUs = latencies.first() / 1_000.0
        val avgUs = (latencies.sum() / iterations) / 1_000.0
        val p50Us = latencies[(iterations * 0.50).toInt()] / 1_000.0
        val p90Us = latencies[(iterations * 0.90).toInt()] / 1_000.0
        val p99Us = latencies[(iterations * 0.99).toInt()] / 1_000.0
        val opsPerSec = (iterations / totalTimeMs) * 1000.0

        println("=== Calculator Benchmark ===")
        println("Iterations: " + iterations + " | Total time: " + totalTimeMs + "ms | Ops/sec: " + opsPerSec)
        println("Latencies (us): min=" + minUs + ", avg=" + avgUs + ", p50=" + p50Us + ", p90=" + p90Us + ", p99=" + p99Us)
        assertTrue(opsPerSec > 5000.0)
    }

    // ==========================================
    // 2. NetworkMcpServer Tests
    // ==========================================

    @Test
    fun testNetworkIndependenceAndExecution() {
        val server = NetworkMcpServer()
        val constructors = NetworkMcpServer::class.java.constructors
        assertEquals("NetworkMcpServer must have 0-arg constructor", 1, constructors.size)
        assertEquals(0, constructors[0].parameterCount)

        // MockWebServer HTTP GET
        mockWebServer.enqueue(MockResponse().setResponseCode(200).setBody("{\"status\":\"ok\",\"mcp\":\"active\"}"))
        val url = mockWebServer.url("/test-endpoint").toString()

        val httpRes = server.executeTool(McpToolCallRequest("n1", "http_get", JSONObject().put("url", url).toString()))
        assertFalse(httpRes.isError)
        assertTrue(httpRes.content.contains("HTTP 200"))
        assertTrue(httpRes.content.contains("status"))

        // Ping host against MockWebServer port
        val pingRes = server.executeTool(McpToolCallRequest("n2", "ping_host", JSONObject().put("host", mockWebServer.hostName).put("port", mockWebServer.port).toString()))
        assertFalse(pingRes.isError)
        assertTrue(pingRes.content.contains("TCP OK"))

        // DNS Resolve localhost
        val dnsRes = server.executeTool(McpToolCallRequest("n3", "dns_resolve", JSONObject().put("hostname", "localhost").toString()))
        assertFalse(dnsRes.isError)
        val jsonDns = JSONObject(dnsRes.content)
        assertTrue(jsonDns.has("ip_addresses"))
    }

    // ==========================================
    // 3. MemoryMcpServer File Persistence Tests
    // ==========================================

    private class FakeTestContext(private val baseDir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = baseDir
    }

    @Test
    fun testMemoryMcpServerRealFilePersistence() {
        val fakeContext = FakeTestContext(tempDir)
        val server1 = MemoryMcpServer(fakeContext)

        val targetFile = File(tempDir, "mcp_memory.json")
        assertFalse("Initially mcp_memory.json should not exist", targetFile.exists())

        // Save keys
        val save1 = server1.executeTool(McpToolCallRequest("m1", "save_memory", JSONObject().put("key", "user_role").put("value", "lead_architect").toString()))
        assertFalse(save1.isError)
        val save2 = server1.executeTool(McpToolCallRequest("m2", "save_memory", JSONObject().put("key", "project_target").put("value", "Android 15").toString()))
        assertFalse(save2.isError)

        // Verify physical file was written to disk
        assertTrue("mcp_memory.json must exist in fakeContext.filesDir", targetFile.exists())
        val diskContent = targetFile.readText(Charsets.UTF_8)
        val diskJson = JSONObject(diskContent)
        assertEquals("lead_architect", diskJson.getString("user_role"))
        assertEquals("Android 15", diskJson.getString("project_target"))

        // Create a new instance pointing to same directory (app restart)
        val server2 = MemoryMcpServer(fakeContext)
        val getRes = server2.executeTool(McpToolCallRequest("m3", "get_memory", JSONObject().put("key", "user_role").toString()))
        assertFalse(getRes.isError)
        assertTrue(getRes.content.contains("lead_architect"))

        val listRes = server2.executeTool(McpToolCallRequest("m4", "list_memories", "{}"))
        assertFalse(listRes.isError)
        val listJson = JSONObject(listRes.content)
        assertEquals(2, listJson.getInt("total_records"))

        // Delete key
        val delRes = server2.executeTool(McpToolCallRequest("m5", "delete_memory", JSONObject().put("key", "user_role").toString()))
        assertFalse(delRes.isError)
        val diskAfterDelete = JSONObject(targetFile.readText(Charsets.UTF_8))
        assertFalse(diskAfterDelete.has("user_role"))
        assertTrue(diskAfterDelete.has("project_target"))
    }

    @Test
    fun testMemoryMcpServerConcurrencyStress() {
        val fakeContext = FakeTestContext(tempDir)
        val server = MemoryMcpServer(fakeContext)
        val threadCount = 10
        val itemsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            val threadIdx = t
            executor.submit {
                try {
                    for (i in 0 until itemsPerThread) {
                        val k = "key_" + threadIdx + "_" + i
                        val v = "value_" + threadIdx + "_" + i
                        server.executeTool(McpToolCallRequest("c", "save_memory", JSONObject().put("key", k).put("value", v).toString()))
                        server.executeTool(McpToolCallRequest("c", "get_memory", JSONObject().put("key", k).toString()))
                    }
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue(latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        val listRes = server.executeTool(McpToolCallRequest("final", "list_memories", "{}"))
        val listJson = JSONObject(listRes.content)
        assertEquals(threadCount * itemsPerThread, listJson.getInt("total_records"))
    }

    // ==========================================
    // 4. ClipboardMcpServer Tests
    // ==========================================

    @Test
    fun testClipboardFallbackMode() {
        val server = ClipboardMcpServer(context = null)
        val readEmpty = server.executeTool(McpToolCallRequest("c1", "get_clipboard_text", "{}"))
        assertEquals("[Portapapeles vacío]", readEmpty.content)

        val writeRes = server.executeTool(McpToolCallRequest("c2", "set_clipboard_text", JSONObject().put("text", "Secret_Auth_Token_123").toString()))
        assertFalse(writeRes.isError)

        val readBack = server.executeTool(McpToolCallRequest("c3", "get_clipboard_text", "{}"))
        assertEquals("Secret_Auth_Token_123", readBack.content)
    }

    // ==========================================
    // 5. PersonalData & Telephony SMS Architecture Validation
    // ==========================================

    @Test
    fun testAndroidProviderConstantsAndUrisValid() {
        // String constants are evaluated at compile time and preserved in bytecode
        assertEquals("display_name", ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        assertEquals("data1", ContactsContract.CommonDataKinds.Phone.NUMBER)

        assertEquals("title", CalendarContract.Events.TITLE)
        assertEquals("dtstart", CalendarContract.Events.DTSTART)
        assertEquals("description", CalendarContract.Events.DESCRIPTION)

        assertEquals("number", CallLog.Calls.NUMBER)
        assertEquals("type", CallLog.Calls.TYPE)
        assertEquals("date", CallLog.Calls.DATE)
        assertEquals("duration", CallLog.Calls.DURATION)

        // Note: In JVM stub android.jar, Uri fields are stubs unless executed on Android OS/Robolectric.
        // We verify the field declarations exist in the Android SDK classes via reflection:
        assertNotNull(ContactsContract.CommonDataKinds.Phone::class.java.getField("CONTENT_URI"))
        assertNotNull(CalendarContract.Events::class.java.getField("CONTENT_URI"))
        assertNotNull(CallLog.Calls::class.java.getField("CONTENT_URI"))
    }

    @Test
    fun testNullAndEmptyContextFallbackExecution() {
        val personalDataServer = PersonalDataMcpServer(null)
        val contactsRes = personalDataServer.executeTool(McpToolCallRequest("p1", "list_contacts", "{}"))
        assertFalse(contactsRes.isError)
        val jsonContacts = JSONObject(contactsRes.content)
        assertTrue(jsonContacts.has("contacts"))
        assertEquals("Modo emulado / Test sin contexto", jsonContacts.getString("note"))

        val calRes = personalDataServer.executeTool(McpToolCallRequest("p2", "list_calendar_events", "{}"))
        assertFalse(calRes.isError)
        val jsonCal = JSONObject(calRes.content)
        assertTrue(jsonCal.has("events"))
        assertEquals("Modo emulado / Test", jsonCal.getString("note"))

        val telephonyServer = TelephonySmsMcpServer(null)
        val callRes = telephonyServer.executeTool(McpToolCallRequest("t1", "get_call_log", "{}"))
        assertFalse(callRes.isError)
        val jsonCalls = JSONObject(callRes.content)
        assertTrue(jsonCalls.has("calls"))
        assertEquals("Modo emulado / Test", jsonCalls.getString("note"))

        val smsRes = telephonyServer.executeTool(McpToolCallRequest("t2", "read_sms_messages", "{}"))
        assertFalse(smsRes.isError)
        val jsonSms = JSONObject(smsRes.content)
        assertTrue(jsonSms.has("messages"))
        assertEquals("Modo emulado / Test", jsonSms.getString("note"))

        val sendRes = telephonyServer.executeTool(McpToolCallRequest("t3", "send_sms", JSONObject().put("phone_number", "+123456").put("message", "Hello").toString()))
        assertFalse(sendRes.isError)
        assertTrue(sendRes.content.contains("✅ [Modo Test] SMS enviado con éxito"))
    }

    @Test
    fun testSmsManagerModernAndroidCompatibilityCheck() {
        val getDefaultMethod = SmsManager::class.java.getMethod("getDefault")
        assertNotNull(getDefaultMethod)

        assertTrue(Modifier.isPublic(SmsManager::class.java.modifiers))

        val sendMethod = SmsManager::class.java.getMethod(
            "sendTextMessage",
            String::class.java,
            String::class.java,
            String::class.java,
            android.app.PendingIntent::class.java,
            android.app.PendingIntent::class.java
        )
        assertNotNull(sendMethod)
    }
}
