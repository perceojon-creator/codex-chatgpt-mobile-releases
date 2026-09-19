package com.codex.chat.core.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

/**
 * Servicio de captura de notificaciones con redaccion de datos sensibles.
 *
 * Auditoria v1.0.79 SEC-4: la version anterior capturaba el titulo y texto RAW
 * de TODAS las notificaciones. Ahora:
 * 1. Los paquetes de autenticadores y bancos se descartan por completo.
 * 2. El texto de las notificaciones permitidas pasa por NotificationRedactor antes
 *    de almacenarse, sustituyendo los codigos OTP/2FA por [REDACTADO].
 */
class CodexNotificationListenerService : NotificationListenerService() {

    companion object {
        data class CapturedNotification(
            val packageName: String,
            val title: String,
            val text: String,
            val postTime: Long
        )

        private val recentNotifications = ConcurrentLinkedDeque<CapturedNotification>()
        private const val MAX_STORED = 25

        fun getRecentNotifications(limit: Int = 10): List<CapturedNotification> {
            return recentNotifications.toList().take(limit)
        }

        fun clearNotifications() {
            recentNotifications.clear()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null) return
        try {
            val pkg = sbn.packageName ?: "Desconocido"

            // SEC-4: descartar sin excepcion los paquetes sensibles.
            if (NotificationRedactor.isPackageBlocked(pkg)) return

            val extras = sbn.notification?.extras
            val rawTitle = extras?.getCharSequence("android.title")?.toString() ?: ""
            val rawText  = extras?.getCharSequence("android.text")?.toString()  ?: ""

            // SEC-4: redactar codigos OTP/2FA del texto antes de almacenar.
            val title = NotificationRedactor.redact(rawTitle)
            val text  = NotificationRedactor.redact(rawText)

            if (title.isNotBlank() || text.isNotBlank()) {
                recentNotifications.addFirst(
                    CapturedNotification(
                        packageName = pkg,
                        title = title,
                        text = text,
                        postTime = sbn.postTime
                    )
                )
                while (recentNotifications.size > MAX_STORED) {
                    recentNotifications.removeLast()
                }
            }
        } catch (e: Throwable) {
            // Non-fatal
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Optional tracking
    }
}
