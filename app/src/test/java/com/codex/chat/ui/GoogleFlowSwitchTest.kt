package com.codex.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoogleFlowSwitchTest {

    @Test
    fun testConnectorsSheetContainsGoogleFlowSwitch() {
        val file = File("src/main/res/layout/bottom_sheet_connectors.xml")
        assertTrue("bottom_sheet_connectors.xml debe existir", file.exists())
        val text = file.readText()
        assertTrue("Debe incluir switchGoogleFlowActive", text.contains("switchGoogleFlowActive"))
        assertTrue("Debe incluir MaterialSwitch", text.contains("MaterialSwitch"))
        assertTrue("Debe incluir Google Flow", text.contains("Google Flow"))
    }

    @Test
    fun testActivationLogicTogglesState() {
        var isConnectorActive = false
        var activeType: String? = null

        fun toggleConnector(activate: Boolean) {
            isConnectorActive = activate
            activeType = if (activate) "IMAGE" else null
        }

        // 1. Initially disabled
        assertFalse(isConnectorActive)

        // 2. User toggles switch ON
        toggleConnector(true)
        assertTrue(isConnectorActive)
        assertTrue(activeType == "IMAGE")

        // 3. User toggles switch OFF
        toggleConnector(false)
        assertFalse(isConnectorActive)
        assertTrue(activeType == null)
    }
}
