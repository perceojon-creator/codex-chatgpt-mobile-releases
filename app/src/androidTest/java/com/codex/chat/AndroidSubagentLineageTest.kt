package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.subagent.SubagentLineageStore
import com.codex.chat.core.subagent.SubagentStatus
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSubagentLineageTest {

    private lateinit var store: SubagentLineageStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = SubagentLineageStore(context, "test_codex_lineage.db")
    }

    @Test
    fun testRecordSubagentStartAndCompletion() {
        val parentSessionId = "session_parent_" + System.currentTimeMillis()
        val subagentId = "sub_child_101"
        val name = "CodeAuditorSubagent"
        val desc = "Auditoría estática de vulnerabilidades"
        val prompt = "Analiza el archivo MainActivity.kt buscando memory leaks"

        val rowId = store.recordSubagentStart(parentSessionId, subagentId, name, desc, prompt)
        assertTrue(rowId > 0)

        val record = store.getSubagent(subagentId)
        assertNotNull(record)
        assertEquals(parentSessionId, record!!.parentSessionId)
        assertEquals(subagentId, record.subagentId)
        assertEquals(SubagentStatus.RUNNING, record.status)
        assertNull(record.resultOutput)

        // Completar subagente
        val output = "Auditoría finalizada: 0 memory leaks detectados."
        val updated = store.recordSubagentCompletion(subagentId, SubagentStatus.COMPLETED, output)
        assertTrue(updated)

        val completedRecord = store.getSubagent(subagentId)
        assertNotNull(completedRecord)
        assertEquals(SubagentStatus.COMPLETED, completedRecord!!.status)
        assertEquals(output, completedRecord.resultOutput)
    }

    @Test
    fun testListSubagentsForSession() {
        val parentSessionId = "session_fanout_" + System.currentTimeMillis()

        store.recordSubagentStart(parentSessionId, "sub_a", "Subagent A", "Task A", "Prompt A")
        store.recordSubagentStart(parentSessionId, "sub_b", "Subagent B", "Task B", "Prompt B")
        store.recordSubagentCompletion("sub_a", SubagentStatus.COMPLETED, "Done A")
        store.recordSubagentCompletion("sub_b", SubagentStatus.FAILED, "Error timeout B")

        val list = store.listSubagentsForSession(parentSessionId)
        assertEquals(2, list.size)
        assertEquals("Subagent A", list[0].name)
        assertEquals(SubagentStatus.COMPLETED, list[0].status)
        assertEquals("Subagent B", list[1].name)
        assertEquals(SubagentStatus.FAILED, list[1].status)
    }

    @Test
    fun testDeleteLineageForSession() {
        val parentSessionId = "session_del_" + System.currentTimeMillis()
        store.recordSubagentStart(parentSessionId, "sub_del_1", "Worker", "Desc", "Prompt")
        assertEquals(1, store.listSubagentsForSession(parentSessionId).size)

        val deleted = store.deleteLineageForSession(parentSessionId)
        assertEquals(1, deleted)
        assertTrue(store.listSubagentsForSession(parentSessionId).isEmpty())
    }
}
