package com.codex.chat.adapter

import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

class ChatDiffUtilTest {

    private val comparator = ChatMessageDiffComparator

    @Test
    fun areItemsTheSame_mismo_id_es_verdadero() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.USER, content = "Hola")
        val m2 = ChatMessage(id = id, role = MessageRole.USER, content = "Hola modificado")
        assertTrue("Mismo ID debe considerarse el mismo item", comparator.areItemsTheSame(m1, m2))
    }

    @Test
    fun areItemsTheSame_distinto_id_es_falso() {
        val m1 = ChatMessage(role = MessageRole.USER, content = "Hola")
        val m2 = ChatMessage(role = MessageRole.USER, content = "Hola")
        assertFalse("Distinto ID debe considerarse items diferentes", comparator.areItemsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_contenido_identico_es_verdadero() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "Respuesta", reasoningContent = "Pensando")
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "Respuesta", reasoningContent = "Pensando")
        assertTrue("Mismo contenido debe considerarse idéntico", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_delta_de_streaming_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "Resp", isStreaming = true)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "Respuesta", isStreaming = true)
        assertFalse("Diferente contenido durante streaming debe considerarse distinto para redibujado", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_cambio_de_estado_thinking_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "Listo", isThinkingExpanded = false)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "Listo", isThinkingExpanded = true)
        assertFalse("Cambio de colapso/expansión de thinking debe requerir re-renderizado", comparator.areContentsTheSame(m1, m2))
    }
}
