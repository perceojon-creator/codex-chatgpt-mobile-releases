package com.codex.chat.core.security

import com.codex.chat.BuildConfig

/**
 * Resolución de claves API para los perfiles de proveedor.
 *
 * Auditoria v1.0.79 SEC-5: la versión anterior embarcaba 4 claves API reales
 * ofuscadas con XOR. Un atacante con jadx podia recuperarlas en minutos.
 *
 * Contrato actual (fail-closed):
 * - Si BuildConfig contiene un campo OVERRIDE_*_KEY con valor no nulo y no
 *   vacío, se devuelve ese valor. Es la ruta normal en un build legítimo.
 * - Si el campo no existe o está vacío, se devuelve cadena vacía.
 *   El llamador debe tratar una cadena vacía como 'clave no configurada'
 *   y rechazar la petición o pedir al usuario que configure la clave.
 *
 * Para inyectar claves en CI/CD, define en local.properties o en las
 * variables de entorno del pipeline:
 *   OVERRIDE_APINEX_KEY=sk-...
 *   OVERRIDE_BAI_KEY=...
 *   OVERRIDE_CODEX_LOCAL_KEY=...
 *   OVERRIDE_E2B_KEY=e2b_...
 * y expónlas en build.gradle.kts como campos de BuildConfig.
 */
object SecureKeyVault {

    fun getApinexKey(): String = getBuildField("OVERRIDE_APINEX_KEY").orEmpty()

    fun getBaiKey(): String = getBuildField("OVERRIDE_BAI_KEY").orEmpty()

    fun getCodexLocalKey(): String = getBuildField("OVERRIDE_CODEX_LOCAL_KEY").orEmpty()

    fun getE2bDefaultKey(): String = getBuildField("OVERRIDE_E2B_KEY").orEmpty()

    private fun getBuildField(fieldName: String): String? {
        return try {
            val field = BuildConfig::class.java.getField(fieldName)
            field.get(null) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
