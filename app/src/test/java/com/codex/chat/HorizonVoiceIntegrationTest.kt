package com.codex.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HorizonVoiceIntegrationTest {
    @Test
    fun testVoiceDialogLayoutExistsWithHorizonOrbView() {
        val file = File("src/main/res/layout/layout_horizon_voice_dialog.xml")
        assertTrue("layout_horizon_voice_dialog.xml debe existir", file.exists())
        val content = file.readText()
        assertTrue("Debe incluir HorizonOrbView", content.contains("HorizonOrbView"))
        assertTrue("Debe incluir btnCloseVoiceMode", content.contains("btnCloseVoiceMode"))
    }
}
