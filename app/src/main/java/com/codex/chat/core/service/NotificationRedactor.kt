package com.codex.chat.core.service

import java.util.regex.Pattern

/**
 * Redactor de datos sensibles en notificaciones.
 *
 * Auditoria v1.0.79 SEC-4: CodexNotificationListenerService capturaba el titulo y
 * texto RAW de todas las notificaciones, incluidas las de 2FA, OTP bancario y
 * autenticadores. Un modelo comprometido podia pedir get_captured_notifications y
 * leer un codigo de verificacion activo.
 *
 * Este redactor (1) bloquea los paquetes mas sensibles en la lista negra y (2) sustituye
 * los codigos numericos cortos en el texto por [REDACTADO] antes de almacenarlos.
 */
object NotificationRedactor {

    /** Paquetes cuyas notificaciones contienen datos de autenticacion que NUNCA deben capturarse. */
    val BLOCKED_PACKAGES: Set<String> = setOf(
        // Autenticadores OTP
        "com.google.android.apps.authenticator2",
        "com.authy.authy",
        "com.microsoft.authenticator",
        "org.fedorahosted.freeotp",
        "com.lastpass.authenticator",
        // Bancos espanoles principales
        "es.bancosantander.apps",
        "es.lacaixa.mobile.android.newwap",
        "com.bbva.bbvacontigo",
        "es.openbank.mobile",
        "es.bancosabadell.android.mobilebanking",
        "es.bankia.wallet",
        "com.bankinter.launcher",
        "es.cotesa.menvam",
        // Mensajeria con frecuencia usada para 2FA por SMS
        "com.whatsapp",
        "org.telegram.messenger",
        "com.android.mms",
        "com.google.android.apps.messaging"
    )

    /**
     * Palabras clave que indican contexto OTP/2FA en el texto completo del mensaje.
     * Si alguna de estas palabras aparece en el texto, todos los numeros de 4-8 digitos
     * se consideran candidatos a ser redactados.
     */
    private val OTP_CONTEXT_PATTERN: Pattern = Pattern.compile(
        """(?i)\b(?:code|codigo|c[oó]digo|otp|pin|clave|token|verification|verificaci[oó]n|autenticaci[oó]n|authentication|2fa|two.factor)\b"""
    )

    /**
     * Patron para "keyword: number" con proximidad directa (keyword seguida inmediatamente de numero).
     * Captura el grupo 1 con el numero.
     */
    private val OTP_KEYWORD_DIRECT_PATTERN: Pattern = Pattern.compile(
        """(?i)(?:code|codigo|c[oó]digo|otp|pin|clave|token|verification|verificaci[oó]n)\s*[:\-]?\s*(\d{4,8})(?!\d)"""
    )

    /**
     * Patron para numero de 6-8 digitos al inicio del mensaje, caracteristico de SMS 2FA.
     */
    private val OTP_LEADING_NUMBER_PATTERN: Pattern = Pattern.compile(
        """^(\d{6,8})(?=\s|$)"""
    )

    /**
     * Patron que detecta un numero de 4-8 digitos aislado (no pegado a mas digitos).
     * Se usa cuando el texto en su conjunto tiene contexto OTP.
     */
    private val ISOLATED_NUMBER_PATTERN: Pattern = Pattern.compile(
        """(?<![\d+])(\d{4,8})(?!\d)"""
    )

    /**
     * Verifica si un paquete debe ser bloqueado completamente.
     * @return true si las notificaciones de este paquete no deben capturarse.
     */
    fun isPackageBlocked(packageName: String): Boolean =
        packageName.lowercase() in BLOCKED_PACKAGES

    /**
     * Redacta los codigos OTP/2FA del texto de la notificacion.
     * @return el texto con los codigos sensibles sustituidos por [REDACTADO].
     */
    fun redact(text: String): String {
        if (text.isBlank()) return text

        // Paso 1: "keyword: NNNN" con proximidad directa — siempre redactar.
        var result = OTP_KEYWORD_DIRECT_PATTERN.matcher(text).replaceAll { mr ->
            mr.group(0).replace(mr.group(1) ?: return@replaceAll mr.group(0), "[REDACTADO]")
        }

        // Paso 2: numero de 6-8 digitos al inicio del mensaje (SMS 2FA tipico).
        result = OTP_LEADING_NUMBER_PATTERN.matcher(result).replaceAll { mr ->
            mr.group(0).replace(mr.group(1) ?: return@replaceAll mr.group(0), "[REDACTADO]")
        }

        // Paso 3: si el texto contiene contexto OTP, redactar todos los numeros de 4-8 digitos aislados.
        if (OTP_CONTEXT_PATTERN.matcher(result).find()) {
            result = ISOLATED_NUMBER_PATTERN.matcher(result).replaceAll { mr ->
                "[REDACTADO]"
            }
        }

        return result
    }
}
