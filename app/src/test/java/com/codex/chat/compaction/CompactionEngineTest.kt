package com.codex.chat.compaction

import com.codex.chat.core.compaction.CompactionConstants
import com.codex.chat.core.compaction.CompactionEngine
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompactionEngineTest {

    private val mockSummaryText = """
        ## Primary Request and Intent
        - User asked to build context compaction.

        ## Key Technical Concepts
        - DSH Apex parity, 90% threshold.

        ## Files and Code
        - CompactionEngine.kt.

        ## Errors and Fixes
        - (none)

        ## Pending Jobs
        - (none)

        ## Current Work
        - Unit testing.

        ## Next Step
        - Build verification.

        ## Critical Context
        - Pixel emulator API 35.
    """.trimIndent()

    @Test
    fun testSuccessfulCompactionSynthesizesCheckpointAtZeroIndex() {
        val engine = CompactionEngine(
            summarizerCallOverride = { _, _, _ -> mockSummaryText }
        )
        val model = ModelInfo(id = "gpt-5.6-terra", displayName = "Terra", provider = "DeepSeek", contextWindow = 128_000)

        val longCodeBlock = "val x = 10; println(x);\n".repeat(50)
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hola quiero crear una app:\n$longCodeBlock"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Claro, aquí tienes la arquitectura inicial:\n$longCodeBlock"),
            ChatMessage(role = MessageRole.USER, content = "Ahora añade soporte para base de datos:\n$longCodeBlock"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Base de datos configurada con Room:\n$longCodeBlock"),
            ChatMessage(role = MessageRole.USER, content = "Último turno: ¿cómo compilo?"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Usa ./gradlew assembleDebug.")
        )

        val result = engine.compact(
            messages = messages,
            model = model,
            force = true
        )

        assertTrue(result.success)
        assertNotNull(result.checkpointMessage)
        // First message must be the synthesized checkpoint
        assertEquals(MessageRole.USER, result.compactedMessages.first().role)
        assertTrue(result.compactedMessages.first().content.contains(CompactionConstants.SUMMARY_OPEN_TAG))
        assertTrue(result.tokensSaved > 0)
        // Retained tail must follow
        assertTrue(result.compactedMessages.size < messages.size)
    }

    @Test
    fun testCompactionRejectsWhenBelowThresholdWithoutForce() {
        val engine = CompactionEngine(
            summarizerCallOverride = { _, _, _ -> mockSummaryText }
        )
        // 1M model with low token messages
        val model = ModelInfo(id = "gpt-5.6-sol", displayName = "Sol", provider = "Antigravity", contextWindow = 1_048_576)
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Hola"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Hola"),
            ChatMessage(role = MessageRole.USER, content = "¿Cómo estás?"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Muy bien")
        )

        val result = engine.compact(
            messages = messages,
            model = model,
            force = false
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("umbral de compactación"))
        assertEquals(messages.size, result.compactedMessages.size)
    }

    @Test
    fun testSummarizerFailureRecoversGracefullyWithoutCorruptingMessages() {
        val engine = CompactionEngine(
            summarizerCallOverride = { _, _, _ -> throw RuntimeException("Proxy network timeout") }
        )
        val model = ModelInfo(id = "gpt-5.6-terra", displayName = "Terra", provider = "DeepSeek", contextWindow = 128_000)
        val messages = listOf(
            ChatMessage(role = MessageRole.USER, content = "Mensaje 1"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Respuesta 1"),
            ChatMessage(role = MessageRole.USER, content = "Mensaje 2"),
            ChatMessage(role = MessageRole.ASSISTANT, content = "Respuesta 2")
        )

        val result = engine.compact(
            messages = messages,
            model = model,
            force = true
        )

        assertFalse(result.success)
        assertTrue(result.error!!.contains("Proxy network timeout"))
        // Original messages remain intact
        assertEquals(messages.size, result.compactedMessages.size)
    }
}