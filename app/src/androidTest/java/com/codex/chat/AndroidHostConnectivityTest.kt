package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.network.CodexApiClient
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class AndroidHostConnectivityTest {

    @Test
    fun testHostConnectivity() {
        val client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

        // 1. Test 10.0.2.2:8317
        var success10 = false
        var error10: String? = null
        try {
            val req10 = Request.Builder().url("http://10.0.2.2:8317/v1/models").build()
            client.newCall(req10).execute().use { resp ->
                success10 = resp.isSuccessful
            }
        } catch (e: Exception) {
            error10 = e.message
        }

        // 2. Test 192.168.1.6:8317
        var success192 = false
        var error192: String? = null
        try {
            val req192 = Request.Builder().url("http://192.168.1.6:8317/v1/models").build()
            client.newCall(req192).execute().use { resp ->
                success192 = resp.isSuccessful
            }
        } catch (e: Exception) {
            error192 = e.message
        }

        println("AndroidHostConnectivityTest: 10.0.2.2 -> success=$success10 (err=$error10), 192.168.1.6 -> success=$success192 (err=$error192)")
        assertTrue("At least one host route must succeed (10.0.2.2: $error10, 192.168.1.6: $error192)", success10 || success192)
    }
}
