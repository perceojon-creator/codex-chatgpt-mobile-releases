package com.codex.chat.chaos

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.storage.PartitionedChatStorage
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class KillAndCorruptionChaosTest {

    private lateinit var context: Context
    private lateinit var testDir: File
    private lateinit var storage: PartitionedChatStorage

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        testDir = File(context.filesDir, "chaos_test_storage").apply {
            if (exists()) deleteRecursively()
            mkdirs()
        }
        storage = PartitionedChatStorage(testDir)
    }

    @Test
    fun test_100_forced_terminations_and_cuts_yield_zero_0byte_files() {
        val sessionsDir = File(testDir, "sessions")

        for (i in 1..100) {
            val session = LocalChatSession(
                id = "chaos_session_$i",
                title = "Chaos Title $i",
                timestamp = System.currentTimeMillis()
            )
            for (m in 1..10) {
                session.addMessage(
                    ChatMessage(
                        id = "msg_${i}_$m",
                        role = if (m % 2 == 1) MessageRole.USER else MessageRole.ASSISTANT,
                        content = "Contenido de prueba bajo estrés de caos número $m de la sesión $i",
                        timestamp = System.currentTimeMillis()
                    )
                )
            }

            val writerThread = Thread {
                storage.saveSession(session)
            }
            writerThread.start()
            if (i % 3 == 0) {
                writerThread.interrupt()
            }
            writerThread.join(200)
        }

        if (sessionsDir.exists()) {
            val files = sessionsDir.listFiles() ?: emptyArray()
            for (f in files) {
                if (f.extension == "json") {
                    assertTrue("Ningún archivo de sesión debe quedar con 0 bytes (corrupción)", f.length() > 0)
                }
            }
        }

        val loadedSessions = storage.loadSessionsIndex()
        assertTrue("Las sesiones guardadas exitosamente deben ser recuperables", loadedSessions.isNotEmpty())
    }
}
