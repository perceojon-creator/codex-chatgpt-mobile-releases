package com.codex.chat.core.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.ConcurrentLinkedDeque

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
            val extras = sbn.notification?.extras
            val title = extras?.getCharSequence("android.title")?.toString() ?: ""
            val text = extras?.getCharSequence("android.text")?.toString() ?: ""

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
