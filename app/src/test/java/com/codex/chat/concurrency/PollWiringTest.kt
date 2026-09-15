package com.codex.chat.concurrency

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PollWiringTest {
    private val fuente: String by lazy {
        val f = File("src/main/java/com/codex/chat/MainActivity.kt")
        if (f.exists()) f.readText()
        else File("app/src/main/java/com/codex/chat/MainActivity.kt").readText()
    }

    @Test
    fun MainActivity_usa_PollToken_y_no_el_flag_antiguo() {
        assertTrue("MainActivity debe usar PollToken",
            fuente.contains("PollToken"))
        assertFalse("codexPollActive quedo obsoleto: sustituir por el token",
            fuente.contains("codexPollActive"))
    }

    @Test
    fun no_queda_la_secuencia_de_reinicio_insegura() {
        val normalizado = fuente.replace(Regex("""\s+"""), " ")
        assertFalse(
            "Secuencia false->true: resucita el poller anterior",
            normalizado.contains("codexPollActive = false") &&
            normalizado.contains("codexPollActive = true")
        )
    }

    @Test
    fun onComplete_comprueba_identidad_antes_de_anular_activeCall() {
        val n = fuente.replace(Regex("""\s+"""), " ")
        val guardas = Regex("""if \(activeCall === currentStreamCall\)""").findAll(n).count()
        assertTrue(
            "Debe haber guarda de identidad en onComplete Y en onError (encontradas: $guardas)",
            guardas >= 2
        )
    }
}
