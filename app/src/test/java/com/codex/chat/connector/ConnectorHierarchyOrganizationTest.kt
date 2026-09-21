package com.codex.chat.connector

import com.codex.chat.core.connector.ConnectorProvider
import org.junit.Assert.*
import org.junit.Test

class ConnectorHierarchyOrganizationTest {

    @Test
    fun testAllRequiredConnectorsAreRegistered() {
        val providers = ConnectorProvider.entries
        val ids = providers.map { it.id }

        assertTrue("Debe incluir google_flow", ids.contains("google_flow"))
        assertTrue("Debe incluir google_drive", ids.contains("google_drive"))
        assertTrue("Debe incluir gmail", ids.contains("gmail"))
        assertTrue("Debe incluir github", ids.contains("github"))
        assertTrue("Debe incluir vibes", ids.contains("vibes"))
    }

    @Test
    fun testGoogleFlowIsActiveAndOthersAreUpcoming() {
        assertTrue("Google Flow debe estar disponible", ConnectorProvider.GOOGLE_FLOW.isAvailable)
        assertFalse("Google Drive debe ser próximamente", ConnectorProvider.GOOGLE_DRIVE.isAvailable)
        assertFalse("Gmail debe ser próximamente", ConnectorProvider.GMAIL.isAvailable)
        assertFalse("GitHub debe ser próximamente", ConnectorProvider.GITHUB.isAvailable)
        assertFalse("Vibes debe ser próximamente", ConnectorProvider.VIBES.isAvailable)
    }

    @Test
    fun testCategoriesAreProperlyAssigned() {
        assertEquals("Imagen y Video", ConnectorProvider.GOOGLE_FLOW.category)
        assertEquals("Productividad y Nube", ConnectorProvider.GOOGLE_DRIVE.category)
        assertEquals("Productividad y Nube", ConnectorProvider.GMAIL.category)
        assertEquals("Desarrollo y Código", ConnectorProvider.GITHUB.category)
        assertEquals("Diseño y Prototipado", ConnectorProvider.VIBES.category)
    }

    @Test
    fun testFromIdResolvesCorrectly() {
        assertEquals(ConnectorProvider.GOOGLE_DRIVE, ConnectorProvider.fromId("google_drive"))
        assertEquals(ConnectorProvider.GMAIL, ConnectorProvider.fromId("gmail"))
        assertEquals(ConnectorProvider.GITHUB, ConnectorProvider.fromId("github"))
        assertEquals(ConnectorProvider.VIBES, ConnectorProvider.fromId("vibes"))
        assertEquals(ConnectorProvider.GOOGLE_FLOW, ConnectorProvider.fromId("unknown_fallback"))
    }
}