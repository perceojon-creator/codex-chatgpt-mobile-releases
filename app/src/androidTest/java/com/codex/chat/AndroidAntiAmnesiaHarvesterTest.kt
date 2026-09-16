package com.codex.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.codex.chat.core.harvest.AntiAmnesiaHarvester
import com.codex.chat.core.mcp.server.MemorySqliteStore
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AndroidAntiAmnesiaHarvesterTest {

    private lateinit var store: MemorySqliteStore
    private lateinit var harvester: AntiAmnesiaHarvester
    private lateinit var repository: LocalChatRepository

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase("mcp_memory.sqlite")
        File(context.filesDir, "mcp_memory.json").delete()
        File(context.filesDir, "chatgpt_local_history.json").delete()

        store = MemorySqliteStore.getInstance(context)
        harvester = AntiAmnesiaHarvester(context, store)
        repository = LocalChatRepository(context)
    }

    @Test
    fun testRealDevice_AntiAmnesia_Harvesting_And_Compaction() {
        val session = LocalChatSession(
            id = UUID.randomUUID().toString(),
            title = "Sesión de Arquitectura Apex"
        )

        // Simular historial largo con datos críticos en los primeros turnos
        session.addMessage(
            ChatMessage(
                role = MessageRole.USER,
                content = "Hola, me llamo Alexander y soy arquitecto de sistemas en DeepSeek."
            )
        )
        session.addMessage(
            ChatMessage(
                role = MessageRole.ASSISTANT,
                content = "¡Hola Alexander! ¿En qué arquitectura trabajaremos hoy?"
            )
        )
        session.addMessage(
            ChatMessage(
                role = MessageRole.USER,
                content = "Prefiero respuestas técnicas con percentiles p99 y benchmarks empíricos."
            )
        )
        session.addMessage(
            ChatMessage(
                role = MessageRole.USER,
                content = "Recuerda que todas las llamadas a herramientas deben ser concurrentes."
            )
        )
        session.addMessage(
            ChatMessage(
                role = MessageRole.USER,
                content = "Stack: Kotlin con DeepSeek Harness y SQLite FTS4 WAL activo."
            )
        )
        session.addMessage(
            ChatMessage(
                role = MessageRole.USER,
                content = "Decisión: Usar modo PTC con batching de hasta 10 herramientas por turno."
            )
        )

        // Agregar turnos adicionales para superar el umbral de prueba (150 tokens)
        for (i in 1..8) {
            session.addMessage(
                ChatMessage(
                    role = MessageRole.USER,
                    content = "Turno de prueba $i: Analizando métricas de rendimiento y desglosando la traza de ejecución del kernel con múltiples detalles de depuración continua y registros de eventos detallados."
                )
            )
            session.addMessage(
                ChatMessage(
                    role = MessageRole.ASSISTANT,
                    content = "Confirmado turno $i. Los eventos de ejecución se han procesado satisfactoriamente sin anomalías detectadas en el recolector de latencia."
                )
            )
        }

        val tokensBefore = harvester.calculateSessionTokens(session.messages)
        assertTrue("Tokens iniciales deben ser significativos ($tokensBefore)", tokensBefore > 200)

        // 1. Validar que shouldCompact detecta la necesidad de compactación
        assertTrue(harvester.shouldCompact(session.messages, thresholdTokens = 150))

        // 2. Ejecutar compactación anti-amnesia con umbral bajo para testing
        val result = harvester.harvestAndCompact(session, thresholdTokens = 150, targetTokens = 100)

        assertNotNull(result)
        assertTrue("Debe haber podado mensajes antiguos", result.prunedMessageCount > 0)
        assertTrue("Tokens posteriores deben ser menores", result.tokensAfter < result.tokensBefore)
        assertTrue("Debe haber cosechado al menos 3 hechos", result.harvestedFacts.size >= 3)

        // 3. Verificar que el ancla de contexto se insertó como primer mensaje
        val firstMessage = session.getMessagesSnapshot().first()
        assertTrue(firstMessage.content.contains("Ancla de Contexto Pre-Compactación"))
        assertTrue(firstMessage.content.contains("preferencias") || firstMessage.content.contains("directivas"))

        // 4. Verificar que los hechos cosechados fueron persistidos en SQLite y son buscables con FTS4
        val searchIdentity = store.searchFts5("Alexander", 5)
        assertTrue("Alexander debe encontrarse en FTS4 SQLite", searchIdentity.length() > 0)

        val searchPtc = store.searchFts5("PTC batching", 5)
        assertTrue("PTC batching debe encontrarse en FTS4 SQLite", searchPtc.length() > 0)

        // 5. Integración con LocalChatRepository: compactSessionAntiAmnesia
        repository.saveSession(session)
        val loaded = repository.getSession(session.id)
        assertNotNull(loaded)
        assertEquals(session.messageCount, loaded!!.messageCount)
    }
}
