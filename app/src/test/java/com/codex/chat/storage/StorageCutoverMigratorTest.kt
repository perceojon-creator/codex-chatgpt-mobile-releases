package com.codex.chat.storage

import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class StorageCutoverMigratorTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_migrator_does_not_overwrite_newer_session_written_during_migration() {
        val root = tempFolder.newFolder("migration_test")
        val legacyFile = File(root, "chatgpt_local_history.json")
        legacyFile.writeText("""
            [
              {"id": "session-A", "title": "Old A", "messages": [{"id": "m1", "role": "user", "content": "Old Msg"}]},
              {"id": "session-B", "title": "Old B", "messages": [{"id": "m2", "role": "user", "content": "Old Msg B"}]}
            ]
        """.trimIndent())

        val storage = PartitionedChatStorage(root)

        // Simular escritura en caliente del usuario antes de que el migrador alcance la sesión A:
        val activeSession = LocalChatSession(id = "session-A", title = "Newer A")
        activeSession.addMessage(ChatMessage(id = "mNew", role = MessageRole.USER, content = "New Hot Message"))
        storage.saveSession(activeSession)

        val migrator = StorageCutoverMigrator(root, storage)
        val stats = migrator.migrateSync()

        assertEquals(1, stats.migratedCount) // Solo session-B debió migrarse
        assertEquals(1, stats.skippedCount)  // session-A se omitió para no pisar el mensaje nuevo

        val sessionA = storage.loadSession("session-A")
        assertNotNull(sessionA)
        assertEquals("New Hot Message", sessionA!!.getMessagesSnapshot()[0].content)

        assertTrue(File(root, "chatgpt_local_history.json.bak").exists())
        assertTrue(migrator.isCutoverComplete())
    }
}
