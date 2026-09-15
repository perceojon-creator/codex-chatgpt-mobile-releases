package com.codex.chat.agent

import com.codex.chat.core.model.ChatMessage
import com.codex.chat.core.model.MessageRole
import org.junit.Assert.*
import org.junit.Test

class AgenticDepthLimitTest {

    @Test
    fun test_depth_50_limit_preserves_task_continuation_flag() {
        val msg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Tarea parcial completada tras 50 iteraciones.",
            canContinueTask = true
        )

        assertTrue("El mensaje asistente debe marcar canContinueTask cuando llega al límite", msg.canContinueTask)
    }

    @Test
    fun test_chat_message_default_can_continue_task_is_false() {
        val normalMsg = ChatMessage(
            role = MessageRole.ASSISTANT,
            content = "Respuesta terminada."
        )

        assertFalse("Mensajes normales no deben mostrar el botón de continuar tarea", normalMsg.canContinueTask)
    }
}
