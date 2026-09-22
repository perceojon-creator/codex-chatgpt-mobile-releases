package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EmptyStateLayoutTest {
    @Test
    fun testEmptyStateLayoutExistsAndHasStarterChips() {
        val file = File("src/main/res/layout/layout_chat_empty_state.xml")
        assertTrue("layout_chat_empty_state.xml debe existir", file.exists())
        val text = file.readText()
        assertTrue("Debe contener chipCreateImage", text.contains("chipCreateImage"))
        assertTrue("Debe contener chipWebSearch", text.contains("chipWebSearch"))
        assertTrue("Debe contener chipWriteCode", text.contains("chipWriteCode"))
        assertTrue("Debe contener chipBrainstorm", text.contains("chipBrainstorm"))
    }
}
