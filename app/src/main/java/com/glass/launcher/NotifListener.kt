package com.glass.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Gives the launcher the notification count and list (needs "notification access"). */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null
        var onChange: (() -> Unit)? = null

        fun current(): List<StatusBarNotification> =
            runCatching { instance?.activeNotifications?.filter { !it.isOngoing } ?: emptyList() }
                .getOrDefault(emptyList())

        val connected get() = instance != null

        fun clear(sbn: StatusBarNotification) { runCatching { instance?.cancelNotification(sbn.key) } }
        fun clearAll() { runCatching { instance?.cancelAllNotifications() } }
    }

    override fun onListenerConnected() { instance = this; onChange?.invoke() }
    override fun onListenerDisconnected() { instance = null; onChange?.invoke() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { onChange?.invoke() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { onChange?.invoke() }
}
