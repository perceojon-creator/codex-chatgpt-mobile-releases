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

    // Fase 4 – Task 16: campos nuevos en el comparador (antes faltaban, AsyncListDiffer los necesita)

    @Test
    fun areContentsTheSame_cambio_de_estado_tool_expanded_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", isToolExpanded = false)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", isToolExpanded = true)
        assertFalse("Expandir/colapsar bloque de herramienta debe requerir re-renderizado", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_cambio_de_durationMs_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", durationMs = 100L)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", durationMs = 200L)
        assertFalse("Cambio en durationMs debe refrescar las metricas", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_cambio_de_completionTokens_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", completionTokens = 50)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", completionTokens = 100)
        assertFalse("Cambio en completionTokens debe refrescar las metricas", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_cambio_de_tokensPerSecond_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", tokensPerSecond = 30.0)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", tokensPerSecond = 45.5)
        assertFalse("Cambio en tokensPerSecond debe refrescar las metricas", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_cambio_de_canContinueTask_es_falso() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", canContinueTask = false)
        val m2 = ChatMessage(id = id, role = MessageRole.ASSISTANT, content = "OK", canContinueTask = true)
        assertFalse("Activar canContinueTask debe mostrar el boton de continuar", comparator.areContentsTheSame(m1, m2))
    }

    @Test
    fun areContentsTheSame_todos_los_campos_iguales_es_verdadero() {
        val id = UUID.randomUUID().toString()
        val m1 = ChatMessage(
            id = id, role = MessageRole.ASSISTANT, content = "Completo",
            reasoningContent = "Pensando", isStreaming = false,
            isThinkingExpanded = true, isToolExpanded = false,
            durationMs = 500L, thinkingDurationMs = 100L, generationDurationMs = 400L,
            completionTokens = 80, tokensPerSecond = 40.0, canContinueTask = true
        )
        val m2 = ChatMessage(
            id = id, role = MessageRole.ASSISTANT, content = "Completo",
            reasoningContent = "Pensando", isStreaming = false,
            isThinkingExpanded = true, isToolExpanded = false,
            durationMs = 500L, thinkingDurationMs = 100L, generationDurationMs = 400L,
            completionTokens = 80, tokensPerSecond = 40.0, canContinueTask = true
        )
        assertTrue("Todos los campos iguales debe ser verdadero", comparator.areContentsTheSame(m1, m2))
    }
}
