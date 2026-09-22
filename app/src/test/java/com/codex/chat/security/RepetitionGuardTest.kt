package com.codex.chat.security

import com.codex.chat.core.security.RepetitionGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RepetitionGuardTest {

    @Test
    fun testShortMessageReturnsFalse() {
        val shortClean = "Este es un mensaje corto sin bucles ni repeticiones."
        assertFalse(RepetitionGuard.isRepetitionDominated(shortClean))
    }

    @Test
    fun testLongCleanMessageReturnsFalse() {
        val longClean = """
            Kotlin es un lenguaje de programación de tipado estático que corre sobre la máquina virtual
            de Java y también puede ser compilado a código fuente de JavaScript o usar la infraestructura
            de LLVM. Su desarrollo principal es de un equipo de programadores de JetBrains con base en San Petersburgo, Rusia.
            El nombre viene de la isla de Kotlin, cerca de San Petersburgo.
            Kotlin ha sido diseñado con la interoperabilidad con código Java en mente, facilitando la adopción gradual.
            Tiene características modernas como funciones de extensión, corrutinas para concurrencia estructurada,
            expresiones when exhaustivas, data classes inmutables y smart casts automáticos en el compilador.
        """.trimIndent()
        assertTrue(longClean.length >= RepetitionGuard.MIN_FRAGMENT_LENGTH)
        assertFalse(RepetitionGuard.isRepetitionDominated(longClean))
    }

    @Test
    fun testDegenerateLineRepetitionReturnsTrue() {
        val repetitiveLine = "Por favor indícame cómo proceder con la siguiente instrucción operativa.\n"
        val repeatedText = repetitiveLine.repeat(15) // > 1000 chars
        assertTrue(repeatedText.length >= RepetitionGuard.MIN_FRAGMENT_LENGTH)
        assertTrue(RepetitionGuard.isRepetitionDominated(repeatedText))
    }

    @Test
    fun testDegenerateWindowRepetitionReturnsTrue() {
        // 60-character block repeated 10 times >= 600 chars
        val block = "1234567890abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWX"
        assertEquals(60, block.length)
        val loopText = block.repeat(10)
        assertTrue(loopText.length >= RepetitionGuard.MIN_FRAGMENT_LENGTH)
        assertTrue(RepetitionGuard.isRepetitionDominated(loopText))
    }
}
