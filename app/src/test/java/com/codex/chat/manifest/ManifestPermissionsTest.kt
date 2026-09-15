package com.codex.chat.manifest

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ManifestPermissionsTest {

    private val manifest: String by lazy {
        val f1 = File("src/main/AndroidManifest.xml")
        if (f1.exists()) f1.readText() else File("app/src/main/AndroidManifest.xml").readText()
    }

    @Test
    fun no_quedan_permisos_privilegiados_inutiles() {
        val prohibidos = listOf(
            "OVERRIDE_WIFI_CONFIG", "MANAGE_DOCUMENTS", "MANAGE_MEDIA",
            "CAPTURE_AUDIO_OUTPUT", "RECORD_BACKGROUND_AUDIO",
            "BLUETOOTH_PRIVILEGED", "ACCOUNT_MANAGER", "READ_CELL_BROADCASTS"
        )
        val presentes = prohibidos.filter { manifest.contains(it) }
        assertTrue(
            "Siguen declarados permisos que el sistema nunca concede: $presentes",
            presentes.isEmpty()
        )
    }

    @Test
    fun no_hay_cleartext_global() {
        assertFalse(
            "usesCleartextTraffic=true habilita HTTP contra CUALQUIER host",
            manifest.contains("usesCleartextTraffic=\"true\"")
        )
        assertTrue("Debe usarse una config de red acotada",
            manifest.contains("networkSecurityConfig"))
    }

    @Test
    fun solo_quedan_los_permisos_protegidos_justificados() {
        val permitidos = setOf(
            "WRITE_SETTINGS",                    // acceso especial via Settings
            "PACKAGE_USAGE_STATS",               // acceso especial via Settings
            "BIND_NOTIFICATION_LISTENER_SERVICE" // obligatorio en el <service>
        )
        val regex = Regex(
            """android:name="android\.permission\.([A-Z_]+)"[^>]*ProtectedPermissions"""
        )
        val encontrados = regex.findAll(manifest).map { it.groupValues[1] }.toSet()
        val sobrantes = encontrados - permitidos
        assertTrue(
            "Permisos protegidos sin justificar (no aportan nada y empeoran el perfil del APK): $sobrantes",
            sobrantes.isEmpty()
        )
    }
}
