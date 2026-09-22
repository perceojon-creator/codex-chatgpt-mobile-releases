package com.codex.chat.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MinimalistConnectorsSheetTest {

    @Test
    fun testConnectorsSheetHasOnlyGoogleFlowAndRealTimeCredits() {
        val file = File("src/main/res/layout/bottom_sheet_connectors.xml")
        assertTrue("bottom_sheet_connectors.xml debe existir", file.exists())
        val text = file.readText()

        // 1. Debe incluir Google Flow con su interruptor
        assertTrue("Debe incluir Google Flow", text.contains("Google Flow"))
        assertTrue("Debe incluir switchGoogleFlowActive", text.contains("switchGoogleFlowActive"))

        // 2. Debe incluir el medidor de créditos en tiempo real
        assertTrue("Debe incluir tvLiveCreditsValue", text.contains("tvLiveCreditsValue"))
        assertTrue("Debe incluir btnRefreshCredits", text.contains("btnRefreshCredits"))

        // 3. Ya NO debe tener paneles innecesarios ni formularios de prueba manual
        assertFalse("No debe tener formulario etImagePrompt", text.contains("etImagePrompt"))
        assertFalse("No debe tener formulario etVideoPrompt", text.contains("etVideoPrompt"))
        assertFalse("No debe tener matriz de precios expandible", text.contains("layoutPricingDetails"))
        assertFalse("No debe tener tarjetas de conectores futuros no usados", text.contains("cardConnectorDrive"))
    }
}
