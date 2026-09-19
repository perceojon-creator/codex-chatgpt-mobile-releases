package com.codex.chat.core.update

/**
 * Extrae metadatos verificables del cuerpo de texto de un GitHub Release.
 *
 * El script de empaquetado (bump_and_build.py) publica en las notas del release
 * el SHA-256 y el versionCode reales del artefacto. Este parser los recupera para
 * que AppUpdateManager pueda verificar la descarga antes de instalarla.
 *
 * Fail-closed: cualquier formato inesperado devuelve null, y el llamante debe
 * tratar null como "no verificable" y abortar la instalacion.
 */
object ReleaseMetadataParser {

    // El grupo de 64 hex debe estar delimitado por la derecha: evita capturar
    // los 64 primeros caracteres de una cadena mas larga (p.ej. 65 caracteres).
    private val SHA256_PATTERN =
        Regex("""(?i)sha-?256\s*[:=]\s*([A-Fa-f0-9]{64})(?![A-Fa-f0-9])""")

    private val VERSION_CODE_PATTERN =
        Regex("""(?i)version[_\s-]?code\s*[:=]\s*(\d{1,9})""")

    /** SHA-256 en minusculas, o null si no aparece o es invalido. */
    fun extractSha256(releaseBody: String): String? {
        if (releaseBody.isBlank()) return null
        val m = SHA256_PATTERN.find(releaseBody) ?: return null
        return m.groupValues[1].lowercase()
    }

    /** versionCode declarado por el publicador, o null si no aparece. */
    fun extractVersionCode(releaseBody: String): Int? {
        if (releaseBody.isBlank()) return null
        val m = VERSION_CODE_PATTERN.find(releaseBody) ?: return null
        return m.groupValues[1].toIntOrNull()
    }
}
