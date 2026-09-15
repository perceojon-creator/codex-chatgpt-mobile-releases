package com.codex.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.filter.PromptNoiseType
import com.codex.chat.core.filter.TrivialPromptFilter
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTrivialPromptFilterTest {

    @Test
    fun testRealDevice_EmptyAndWhitespaceFiltering() {
        val decisionEmpty = TrivialPromptFilter.evaluate("")
        assertTrue(decisionEmpty.isBlocked)
        assertEquals(PromptNoiseType.EMPTY_OR_WHITESPACE, decisionEmpty.noiseType)

        val decisionSpaces = TrivialPromptFilter.evaluate("   \t  \n  ")
        assertTrue(decisionSpaces.isBlocked)
        assertEquals(PromptNoiseType.EMPTY_OR_WHITESPACE, decisionSpaces.noiseType)
    }

    @Test
    fun testRealDevice_RapidDuplicateRejection() {
        val prompt = "Implementar arquitectura limpia en Kotlin"
        val now = System.currentTimeMillis()

        // Primer envío válido
        val first = TrivialPromptFilter.evaluate(
            prompt = prompt,
            lastPrompt = null,
            lastTimestampMs = 0L,
            currentTimestampMs = now
        )
        assertFalse(first.isBlocked)
        assertEquals(PromptNoiseType.VALID, first.noiseType)

        // Segundo envío idéntico 300 ms después (rebote accidental del botón de enviar)
        val second = TrivialPromptFilter.evaluate(
            prompt = prompt,
            lastPrompt = prompt,
            lastTimestampMs = now,
            currentTimestampMs = now + 300L
        )
        assertTrue(second.isBlocked)
        assertEquals(PromptNoiseType.RAPID_DUPLICATE, second.noiseType)

        // Tercer envío idéntico tras expirar la ventana de debounce (2000 ms después)
        val third = TrivialPromptFilter.evaluate(
            prompt = prompt,
            lastPrompt = prompt,
            lastTimestampMs = now,
            currentTimestampMs = now + 2000L
        )
        assertFalse(third.isBlocked)
        assertEquals(PromptNoiseType.VALID, third.noiseType)
    }

    @Test
    fun testRealDevice_PunctuationAndSymbolNoiseFiltering() {
        val punctuationSamples = listOf(".", "...", "???", "!?!?", ",,,,", " - ", " ; ")
        for (sample in punctuationSamples) {
            val decision = TrivialPromptFilter.evaluate(sample)
            assertTrue("Sample '$sample' must be blocked as punctuation noise", decision.isBlocked)
            assertEquals(PromptNoiseType.PUNCTUATION_ONLY, decision.noiseType)
        }
    }

    @Test
    fun testRealDevice_RepetitiveCharacterFiltering() {
        val repetitiveSamples = listOf("aaaaaa", "zzzzz", "111111", "xxxx")
        for (sample in repetitiveSamples) {
            val decision = TrivialPromptFilter.evaluate(sample)
            assertTrue("Sample '$sample' must be blocked as repetitive noise", decision.isBlocked)
            assertEquals(PromptNoiseType.REPETITIVE_CHARACTERS, decision.noiseType)
        }
    }

    @Test
    fun testRealDevice_TrivialAcknowledgments() {
        val acks = listOf("ok", "OK", "vale", "gracias", "thanks", "thx")
        for (ack in acks) {
            val decision = TrivialPromptFilter.evaluate(ack)
            assertFalse(decision.isBlocked)
            assertEquals(PromptNoiseType.TRIVIAL_ACKNOWLEDGMENT, decision.noiseType)
            assertNotNull(decision.instantResponse)
            assertTrue(decision.instantResponse!!.length > 3)
        }
    }

    @Test
    fun testRealDevice_ValidPromptsPassThroughCleanly() {
        val validPrompts = listOf(
            "¿Cuál es la diferencia entre FTS4 y FTS5 en SQLite Android?",
            "Optimiza este algoritmo de ordenamiento en tiempo lineal",
            "Crea un servicio REST con Ktor y WebSockets"
        )

        for (p in validPrompts) {
            val decision = TrivialPromptFilter.evaluate(p)
            assertFalse(decision.isBlocked)
            assertEquals(PromptNoiseType.VALID, decision.noiseType)
            assertNull(decision.instantResponse)
            assertEquals(p.trim(), decision.sanitizedPrompt)
        }
    }

    @Test
    fun testRealDevice_SanitizerStripsZeroWidthChars() {
        val dirty = "\u200BHola mundo\uFEFF con espacios\u200C"
        val clean = TrivialPromptFilter.sanitize(dirty)
        assertFalse(clean.contains("\u200B"))
        assertFalse(clean.contains("\uFEFF"))
        assertFalse(clean.contains("\u200C"))
        assertEquals("Hola mundo con espacios", clean)
    }
}
