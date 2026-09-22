package com.codex.chat.ui

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class BottomSheetTilesLayoutTest {

    @Test
    fun testNormalBottomSheetHasAllActionTiles() {
        val file = File("src/main/res/layout/bottom_sheet_chatgpt_normal.xml")
        assertTrue("bottom_sheet_chatgpt_normal.xml debe existir", file.exists())
        val text = file.readText()
        assertTrue("Debe incluir actionNormalAttachDoc", text.contains("actionNormalAttachDoc"))
        assertTrue("Debe incluir actionNormalAttachImage", text.contains("actionNormalAttachImage"))
        assertTrue("Debe incluir actionNormalWebSearch", text.contains("actionNormalWebSearch"))
        assertTrue("Debe incluir actionNormalConnectors", text.contains("actionNormalConnectors"))
        assertTrue("Debe incluir actionNormalSubagents", text.contains("actionNormalSubagents"))
        assertTrue("Debe incluir actionNormalMcpServers", text.contains("actionNormalMcpServers"))
        assertTrue("Debe usar bg_sheet_tile", text.contains("bg_sheet_tile"))
    }
}
