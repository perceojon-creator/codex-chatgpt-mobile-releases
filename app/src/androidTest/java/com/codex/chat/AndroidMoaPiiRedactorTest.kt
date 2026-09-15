package com.codex.chat

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import com.codex.chat.core.model.ModelInfo
import com.codex.chat.core.model.ReasoningEffort
import com.codex.chat.core.network.CodexPayloadBuilder
import com.codex.chat.core.security.MoaPiiRedactor
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidMoaPiiRedactorTest {

    @Test
    fun testRedactApiKeysAndTokens() {
        val rawInput = "OpenAI: sk-abcdef12345678901234567890123456, GitHub: ghp_1234567890abcdefghijklmnopqrstuvwxyz, Bearer 1234567890abcdef1234567890"
        val redacted = MoaPiiRedactor.redact(rawInput)

        assertFalse("No debe contener la clave OpenAI real", redacted.contains("sk-abcdef12345678901234567890123456"))
        assertTrue("Debe contener etiqueta [REDACTED OPENAI KEY]", redacted.contains("[REDACTED OPENAI KEY]"))
        assertFalse("No debe contener el token GitHub real", redacted.contains("ghp_1234567890abcdefghijklmnopqrstuvwxyz"))
        assertTrue("Debe contener etiqueta [REDACTED GITHUB TOKEN]", redacted.contains("[REDACTED GITHUB TOKEN]"))
        assertTrue("Debe contener Bearer [REDACTED TOKEN]", redacted.contains("Bearer [REDACTED TOKEN]"))
    }

    @Test
    fun testRedactPrivateKeysAndDbUris() {
        val privateKey = """-----BEGIN RSA PRIVATE KEY-----
MIIEowIBAAKCAQEA0Yq4J...
-----END RSA PRIVATE KEY-----"""
        val dbUri = "postgres://admin:supersecret@db.internal.net:5432/production_db"

        val raw = "Configuración:\n$privateKey\nDB: $dbUri"
        val redacted = MoaPiiRedactor.redact(raw)

        assertFalse("No debe contener la clave privada", redacted.contains("BEGIN RSA PRIVATE KEY"))
        assertTrue("Debe contener [REDACTED PRIVATE KEY]", redacted.contains("[REDACTED PRIVATE KEY]"))
        assertFalse("No debe contener la URI de base de datos", redacted.contains("supersecret"))
        assertTrue("Debe contener [REDACTED DB URI]", redacted.contains("[REDACTED DB URI]"))
    }

    @Test
    fun testRedactPiiEmailsAndPhones() {
        val raw = "Contacto urgente: ceo@corp-enterprise.com o al (555) 234-5678."
        val redacted = MoaPiiRedactor.redact(raw)

        assertFalse("No debe contener el correo", redacted.contains("ceo@corp-enterprise.com"))
        assertTrue("Debe contener [redacted email]", redacted.contains("[redacted email]"))
        assertFalse("No debe contener el teléfono", redacted.contains("(555) 234-5678"))
        assertTrue("Debe contener [redacted phone]", redacted.contains("[redacted phone]"))
    }

    @Test
    fun testMoaAdvisoryPerspectivesGeneration() {
        val task = "Deploy servicio con api_key: sk-12345678901234567890123456789012 y notificar a ops@dev.com"
        val perspectives = MoaPiiRedactor.generatePerspectives(task)

        assertEquals("Debe generar 3 perspectivas de revisión MoA", 3, perspectives.size)
        val roles = perspectives.map { it.role }
        assertTrue("Debe incluir systems_architect", roles.contains("systems_architect"))
        assertTrue("Debe incluir security_auditor", roles.contains("security_auditor"))
        assertTrue("Debe incluir performance_engineer", roles.contains("performance_engineer"))

        for (p in perspectives) {
            assertFalse("Los prompts MoA no deben filtrar la clave OpenAI", p.prompt.contains("sk-12345678901234567890123456789012"))
            assertTrue("Los prompts MoA deben contener la clave sanitizada", p.prompt.contains("[REDACTED OPENAI KEY]"))
            assertFalse("Los prompts MoA no deben filtrar el correo", p.prompt.contains("ops@dev.com"))
        }
    }

    @Test
    fun testMoaReportSynthesisConsensus() {
        val reports = listOf(
            "security_auditor" to "Detectado riesgo crítico de fuga en endpoint con token Bearer 1234567890abcdef1234567890.",
            "performance_engineer" to "Latencia p99 estimada en 450ms, sin bloqueos.",
            "systems_architect" to "Arquitectura desacoplada conforme a contratos."
        )

        val synthesis = MoaPiiRedactor.synthesizeReports(reports)

        assertEquals(3, synthesis.advisorCount)
        assertTrue("Debe detectar el riesgo de seguridad", synthesis.risksDetected.isNotEmpty())
        assertTrue("El riesgo debe indicar el rol de procedencia", synthesis.risksDetected[0].contains("[security_auditor]"))
        assertFalse("La síntesis no debe contener el token crudo", synthesis.recommendations.any { it.contains("1234567890abcdef1234567890") })
        assertTrue("La recomendación debe tener el token redactado", synthesis.recommendations.any { it.contains("Bearer [REDACTED TOKEN]") })
    }

    @Test
    fun testPayloadBuilderIntegrationWithPiiRedaction() {
        val model = ModelInfo(id = "gpt-5.6-sol", displayName = "Sol", provider = "Antigravity", supportsReasoning = true)
        val messages = listOf(
            ChatMessage(
                role = MessageRole.USER,
                content = "Por favor depura esta conexión con api_key: sk-99999999999999999999999999999999 y contacta a dev@studio.com"
            )
        )
        val grounding = "Resultado web: Servidor vulnerable en postgres://usr:pwd@db.internal:5432/app"

        val payload = CodexPayloadBuilder.buildChatCompletionPayload(
            model = model,
            effort = ReasoningEffort.LOW,
            messages = messages,
            webGrounding = grounding,
            redactSecrets = true
        )

        val msgs = payload.getJSONArray("messages")
        // Buscamos el mensaje de datos externos y el del usuario
        var foundGrounding = false
        var foundUser = false

        for (i in 0 until msgs.length()) {
            val m = msgs.getJSONObject(i)
            val role = m.getString("role")
            val content = m.getString("content")
            if (role == "user" && content.contains("<datos_externos fuente=\"busqueda_web\">")) {
                foundGrounding = true
                assertFalse("Web grounding no debe filtrar credenciales de BD", content.contains("usr:pwd"))
                assertTrue("Web grounding debe tener DB URI redactada", content.contains("[REDACTED DB URI]"))
            }
            if (role == "user" && !content.contains("<datos_externos")) {
                foundUser = true
                assertFalse("Mensaje de usuario no debe filtrar api_key OpenAI", content.contains("sk-99999999999999999999999999999999"))
                assertTrue("Mensaje de usuario debe contener clave redactada", content.contains("[REDACTED OPENAI KEY]"))
                assertFalse("Mensaje de usuario no debe filtrar correo", content.contains("dev@studio.com"))
                assertTrue("Mensaje de usuario debe contener correo redactado", content.contains("[redacted email]"))
            }
        }

        assertTrue("Debe encontrar bloque de grounding sanitizado", foundGrounding)
        assertTrue("Debe encontrar mensaje de usuario sanitizado", foundUser)
    }
}
