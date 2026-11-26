package com.glass.launcher

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/** Gives the launcher the notification count and list (needs "notification access"). */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null
        var onChange: (() -> Unit)? = null

        /** 0 important, 1 critical only, 2 all, 3 none (Settings › Notifications) */
        @Volatile var mode: Int = 2

        private fun passes(sbn: StatusBarNotification): Boolean {
            if (mode == 2) return true
            if (mode == 3) return false
            val imp = runCatching {
                val r = Ranking()
                if (instance?.currentRanking?.getRanking(sbn.key, r) == true) r.importance else 3
            }.getOrDefault(3)
            val cat = sbn.notification.category
            val critical = imp >= 4 || cat in setOf(android.app.Notification.CATEGORY_ALARM, android.app.Notification.CATEGORY_CALL,
                android.app.Notification.CATEGORY_ERROR)
            return if (mode == 1) critical else critical || imp >= 3
        }

        /** hide empty system notices (see junk) */
        @Volatile var hideJunk: Boolean = true

        /**
         * Notices that only look new: Google TV's "Notification Center – Notification Center" (title = text,
         * a click does nothing) and "… is running in the background" service notes.
         */
        private fun junk(sbn: StatusBarNotification): Boolean {
            val n = sbn.notification
            val ex = n.extras
            val title = ex?.getCharSequence(android.app.Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
            val text = ex?.getCharSequence(android.app.Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
            if (title.isEmpty() && text.isEmpty()) return true
            if (title.equals(text, ignoreCase = true) && n.contentIntent == null) return true
            if (n.contentIntent == null && (n.actions == null || n.actions.isEmpty())) return true
            if (n.category == android.app.Notification.CATEGORY_SERVICE) return true
            if ((n.flags and android.app.Notification.FLAG_FOREGROUND_SERVICE) != 0) return true
            val low = text.lowercase()
            if (low.contains("running in the background") || low.contains("läuft im hintergrund")) return true
            return false
        }

        /** apps whose notifications the user hid from the bell */
        @Volatile var muted: Set<String> = emptySet()

        /**
         * Only real, clearable notifications: no "ongoing" ones, none that can't be deleted
         * (like the permanent Google TV one), no group headers, nothing from hidden apps.
         */
        fun current(): List<StatusBarNotification> =
            runCatching {
                instance?.activeNotifications?.filter {
                    !it.isOngoing && it.isClearable && it.packageName !in muted &&
                        (it.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY) == 0 && passes(it) &&
                        !(hideJunk && junk(it))
                } ?: emptyList()
            }.getOrDefault(emptyList())

        val connected get() = instance != null

        fun clear(sbn: StatusBarNotification) { runCatching { instance?.cancelNotification(sbn.key) } }
        fun clearAll() { runCatching { instance?.cancelAllNotifications() } }
    }

    override fun onListenerConnected() { instance = this; onChange?.invoke() }
    override fun onListenerDisconnected() { instance = null; onChange?.invoke() }
    override fun onNotificationPosted(sbn: StatusBarNotification?) { onChange?.invoke() }
    override fun onNotificationRemoved(sbn: StatusBarNotification?) { onChange?.invoke() }
}
