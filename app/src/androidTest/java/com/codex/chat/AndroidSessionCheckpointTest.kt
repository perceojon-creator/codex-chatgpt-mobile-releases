package com.codex.chat

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.checkpoint.SessionCheckpointStore
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidSessionCheckpointTest {

    private lateinit var store: SessionCheckpointStore

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        store = SessionCheckpointStore(context, "test_codex_checkpoints.db")
    }

    @Test
    fun testSaveAndGetLatestCheckpoint() {
        val sessionId = "session_test_" + System.currentTimeMillis()
        val summary = "Configuración inicial de base de datos completada exitosamente."
        val facts = listOf("El usuario prefiere Kotlin sobre Java", "Servidor proxy en 192.168.1.6:8317")
        val goals = listOf("Implementar pruebas unitarias", "Compilar APK")
        val files = listOf("com/codex/chat/MainActivity.kt", "com/codex/chat/SettingsManager.kt")

        val id = store.saveCheckpoint(sessionId, summary, facts, goals, files)
        assertTrue("El ID retornado debe ser mayor a 0", id > 0)

        val latest = store.getLatestCheckpoint(sessionId)
        assertNotNull("El checkpoint debe existir", latest)
        assertEquals(sessionId, latest!!.sessionId)
        assertEquals(summary, latest.summary)
        assertEquals(2, latest.facts.size)
        assertEquals("El usuario prefiere Kotlin sobre Java", latest.facts[0])
        assertEquals(2, latest.goals.size)
        assertEquals(2, latest.filesTouched.size)
        assertEquals("com/codex/chat/MainActivity.kt", latest.filesTouched[0])
    }

    @Test
    fun testMultipleCheckpointsOrder() {
        val sessionId = "session_multi_" + System.currentTimeMillis()

        store.saveCheckpoint(sessionId, "Paso 1: Análisis", listOf("Hecho 1"), listOf("Meta 1"), emptyList())
        Thread.sleep(10)
        store.saveCheckpoint(sessionId, "Paso 2: Implementación", listOf("Hecho 2"), listOf("Meta 2"), emptyList())
        Thread.sleep(10)
        store.saveCheckpoint(sessionId, "Paso 3: Verificación final", listOf("Hecho 3"), listOf("Meta 3"), emptyList())

        val latest = store.getLatestCheckpoint(sessionId)
        assertNotNull(latest)
        assertEquals("Paso 3: Verificación final", latest!!.summary)

        val all = store.listCheckpoints(sessionId, 10)
        assertEquals(3, all.size)
        assertEquals("Paso 3: Verificación final", all[0].summary)
        assertEquals("Paso 2: Implementación", all[1].summary)
        assertEquals("Paso 1: Análisis", all[2].summary)
    }

    @Test
    fun testDeleteCheckpoints() {
        val sessionId = "session_delete_" + System.currentTimeMillis()
        store.saveCheckpoint(sessionId, "Temporal", emptyList(), emptyList(), emptyList())
        assertNotNull(store.getLatestCheckpoint(sessionId))

        val deleted = store.deleteCheckpointsForSession(sessionId)
        assertEquals(1, deleted)
        assertNull(store.getLatestCheckpoint(sessionId))
    }

    @Test
    fun testPruneOldCheckpoints() {
        val sessionId = "session_prune_" + System.currentTimeMillis()
        for (i in 1..7) {
            store.saveCheckpoint(sessionId, "Checkpoint $i", emptyList(), emptyList(), emptyList())
            Thread.sleep(5)
        }

        assertEquals(7, store.listCheckpoints(sessionId, 10).size)

        // Podar manteniendo solo los 3 más recientes
        store.pruneOldCheckpoints(sessionId, keepPerSession = 3)

        val remaining = store.listCheckpoints(sessionId, 10)
        assertEquals(3, remaining.size)
        assertEquals("Checkpoint 7", remaining[0].summary)
    }
}
