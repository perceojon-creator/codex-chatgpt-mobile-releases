package com.codex.chat.core.security

import com.codex.chat.BuildConfig
import java.nio.charset.StandardCharsets

/**
 * Almacén criptográfico en memoria y descifrador de claves predeterminadas del sistema.
 *
 * Evita la presencia de literales de claves API en texto plano ("sk-...", "e2b_...")
 * dentro de los archivos .class o las tablas de strings de DEX, frustrando la extracción
 * estática mediante herramientas forenses como `strings`, `jadx` o `apktool`.
 *
 * Si existen claves inyectadas en tiempo de compilación mediante BuildConfig (provenientes
 * de local.properties o variables de entorno del pipeline CI/CD), tienen prioridad absoluta.
 */
object SecureKeyVault {

    private val MASK = byteArrayOf(
        0x5A.toByte(), 0xA5.toByte(), 0xC3.toByte(), 0x3C.toByte(),
        0xF0.toByte(), 0x0F.toByte(), 0x96.toByte(), 0x69.toByte(),
        0x7E.toByte(), 0xE7.toByte(), 0xBD.toByte(), 0xDB.toByte()
    )

    // Clave APInex ofuscada mediante máscara multi-byte (longitud 53)
    private val MASKED_APINEX = byteArrayOf(
        41, -50, -18, 93, -128, 119, -92, 10, 26, -45, -120, -19,
        108, -64, -94, 11, -62, 105, -90, 95, 24, -127, -118, -30,
        110, -63, -10, 12, -107, 105, -12, 95, 78, -126, -118, -19,
        56, -106, -12, 11, -59, 60, -96, 92, 26, -41, -113, -71,
        59, -110, -13, 14, -107
    )

    // Clave B.AI ofuscada mediante máscara multi-byte (longitud 35)
    private val MASKED_BAI = byteArrayOf(
        41, -50, -18, 13, -56, 57, -26, 16, 9, -120, -51, -67,
        44, -61, -12, 85, -63, 118, -2, 89, 72, -109, -45, -17,
        99, -99, -89, 81, -128, 104, -32, 31, 17, -124, -119
    )

    // Clave Local Codex Proxy ofuscada (longitud 71)
    private val MASKED_CODEX_LOCAL = byteArrayOf(
        41, -50, -18, 95, -128, 110, -69, 80, 24, -41, -114, -18,
        109, -61, -15, 93, -111, 107, -95, 11, 31, -33, -115, -72,
        105, -111, -6, 88, -56, 62, -81, 89, 73, -127, -113, -30,
        56, -100, -91, 93, -58, 58, -16, 8, 75, -123, -124, -71,
        56, -110, -11, 94, -57, 59, -14, 95, 77, -43, -37, -21,
        56, -58, -91, 89, -61, 106, -81, 90, 74, -42, -37
    )

    // Clave E2B predeterminada ofuscada (longitud 44)
    private val MASKED_E2B = byteArrayOf(
        63, -105, -95, 99, -63, 63, -82, 93, 31, -124, -113, -22,
        57, -100, -9, 8, -60, 62, -13, 10, 79, -127, -40, -20,
        60, -108, -10, 88, -64, 57, -14, 92, 71, -46, -114, -72,
        104, -112, -11, 4, -110, 57, -13, 12
    )

    private fun unmask(payload: ByteArray): String {
        val out = ByteArray(payload.size)
        val maskLen = MASK.size
        for (i in payload.indices) {
            val m = MASK[i % maskLen].toInt()
            val p = payload[i].toInt()
            out[i] = (p xor m).toByte()
        }
        return String(out, StandardCharsets.UTF_8)
    }

    /** Retorna la clave API para el perfil APInex Free */
    fun getApinexKey(): String {
        val buildOverride = getBuildField("OVERRIDE_APINEX_KEY")
        if (!buildOverride.isNullOrBlank()) return buildOverride
        return unmask(MASKED_APINEX)
    }

    /** Retorna la clave API para el perfil B.AI */
    fun getBaiKey(): String {
        val buildOverride = getBuildField("OVERRIDE_BAI_KEY")
        if (!buildOverride.isNullOrBlank()) return buildOverride
        return unmask(MASKED_BAI)
    }

    /** Retorna la clave API para el proxy local en PC */
    fun getCodexLocalKey(): String {
        val buildOverride = getBuildField("OVERRIDE_CODEX_LOCAL_KEY")
        if (!buildOverride.isNullOrBlank()) return buildOverride
        return unmask(MASKED_CODEX_LOCAL)
    }

    /** Retorna la clave predeterminada para el cliente E2B Cloud */
    fun getE2bDefaultKey(): String {
        val buildOverride = getBuildField("OVERRIDE_E2B_KEY")
        if (!buildOverride.isNullOrBlank()) return buildOverride
        return unmask(MASKED_E2B)
    }

    private fun getBuildField(fieldName: String): String? {
        return try {
            val field = BuildConfig::class.java.getField(fieldName)
            field.get(null) as? String
        } catch (_: Throwable) {
            null
        }
    }
}
