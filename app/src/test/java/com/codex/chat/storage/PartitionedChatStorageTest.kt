package com.codex.chat.storage

import com.codex.chat.LocalChatSession
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PartitionedChatStorageTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun test_save_and_load_session_partitioned() {
        val root = tempFolder.newFolder("chat_storage")
        val storage = PartitionedChatStorage(root)

        val session = LocalChatSession(id = "test-session-1", title = "Particionado")
        session.addMessage(ChatMessage(id = "m1", role = MessageRole.USER, content = "Hola"))
        session.addMessage(ChatMessage(id = "m2", role = MessageRole.ASSISTANT, content = "Mundo"))

        storage.saveSession(session)

        val loaded = storage.loadSession("test-session-1")
        assertNotNull("La sesión cargada no debe ser nula", loaded)
        assertEquals("test-session-1", loaded!!.id)
        assertEquals(2, loaded.messageCount)
        assertEquals("Hola", loaded.getMessagesSnapshot()[0].content)
        assertEquals("Mundo", loaded.getMessagesSnapshot()[1].content)

        val index = storage.loadSessionsIndex()
        assertEquals(1, index.size)
        assertEquals("test-session-1", index[0].id)
        assertEquals("Particionado", index[0].title)
        assertEquals(2, index[0].messageCount)
    }

    @Test
    fun test_delete_session_removes_file_and_updates_index() {
        val root = tempFolder.newFolder("chat_storage_del")
        val storage = PartitionedChatStorage(root)

        val session = LocalChatSession(id = "to-delete", title = "Eliminar")
        session.addMessage(ChatMessage(id = "m1", role = MessageRole.USER, content = "Test"))
        storage.saveSession(session)

        assertTrue(storage.hasSession("to-delete"))
        storage.deleteSession("to-delete")
        assertFalse(storage.hasSession("to-delete"))
        assertNull(storage.loadSession("to-delete"))
        assertTrue(storage.loadSessionsIndex().isEmpty())
    }
}
