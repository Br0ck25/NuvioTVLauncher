package com.nuvio.tv.core.notification

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class LauncherNotificationItem(
    val key: String,
    val packageName: String,
    val appName: String,
    val appIcon: ImageBitmap? = null,
    val title: String,
    val text: String,
    val postTime: Long,
    val isClearable: Boolean,
    val contentIntent: PendingIntent? = null
)

class LauncherNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "NotificationListener"
        private const val PREFS_NAME = "nuvio_dismissed_notifications"
        private const val PREFS_KEY_SIGNATURES = "dismissed_signatures"

        @Volatile
        var instance: LauncherNotificationListenerService? = null
            private set

        private val _notifications = MutableStateFlow<List<LauncherNotificationItem>>(emptyList())
        val notifications: StateFlow<List<LauncherNotificationItem>> = _notifications.asStateFlow()

        private val dismissedSignatures = java.util.Collections.synchronizedSet(mutableSetOf<String>())

        private fun signatureFor(key: String, title: String, text: String): String {
            return "$key|$title|$text"
        }

        fun isAccessGranted(context: Context): Boolean {
            return runCatching {
                NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
            }.getOrDefault(false)
        }

        fun openAccessSettings(context: Context) {
            runCatching {
                val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            }
        }
    }

    private fun loadDismissedSignatures() {
        runCatching {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val saved = prefs.getStringSet(PREFS_KEY_SIGNATURES, emptySet()).orEmpty()
            synchronized(dismissedSignatures) {
                dismissedSignatures.addAll(saved)
            }
        }
    }

    private fun saveDismissedSignatures() {
        runCatching {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val copy = synchronized(dismissedSignatures) { dismissedSignatures.toSet() }
            prefs.edit().putStringSet(PREFS_KEY_SIGNATURES, copy).apply()
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        instance = this
        loadDismissedSignatures()
        Log.d(TAG, "Notification listener connected")
        refreshActiveNotifications()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        if (instance == this) {
            instance = null
        }
        Log.d(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        sbn ?: return
        refreshActiveNotifications()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        super.onNotificationRemoved(sbn)
        sbn ?: return
        synchronized(dismissedSignatures) {
            dismissedSignatures.removeAll { it.startsWith("${sbn.key}|") }
        }
        saveDismissedSignatures()
        refreshActiveNotifications()
    }

    fun refreshActiveNotifications() {
        runCatching {
            val sbns = activeNotifications ?: return
            val pm = packageManager
            val activeKeys = sbns.map { it.key }.toSet()
            synchronized(dismissedSignatures) {
                dismissedSignatures.removeAll { sig ->
                    val key = sig.substringBefore('|')
                    key !in activeKeys
                }
            }
            val items = sbns.mapNotNull { sbn ->
                val notif = sbn.notification ?: return@mapNotNull null
                val extras = notif.extras
                val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString()
                    ?: extras?.getCharSequence(Notification.EXTRA_TITLE_BIG)?.toString()
                    ?: ""
                val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString()
                    ?: extras?.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()
                    ?: ""

                if (title.isBlank() && text.isBlank()) return@mapNotNull null

                val appName = runCatching {
                    val appInfo = pm.getApplicationInfo(sbn.packageName, 0)
                    pm.getApplicationLabel(appInfo).toString()
                }.getOrDefault(sbn.packageName)

                val effectiveTitle = title.ifBlank { appName }
                val sig = signatureFor(sbn.key, effectiveTitle, text)
                val isDismissed = synchronized(dismissedSignatures) { sig in dismissedSignatures }
                if (isDismissed) return@mapNotNull null

                val appIcon = runCatching {
                    pm.getApplicationIcon(sbn.packageName).toBitmap(width = 64, height = 64).asImageBitmap()
                }.getOrNull()

                LauncherNotificationItem(
                    key = sbn.key,
                    packageName = sbn.packageName,
                    appName = appName,
                    appIcon = appIcon,
                    title = effectiveTitle,
                    text = text,
                    postTime = sbn.postTime,
                    isClearable = sbn.isClearable,
                    contentIntent = notif.contentIntent
                )
            }.sortedByDescending { it.postTime }
            _notifications.value = items
        }.onFailure { e ->
            Log.w(TAG, "Error refreshing notifications", e)
        }
    }

    fun dismiss(key: String) {
        runCatching {
            val currentItem = _notifications.value.firstOrNull { it.key == key }
            if (currentItem != null) {
                synchronized(dismissedSignatures) {
                    dismissedSignatures.add(signatureFor(currentItem.key, currentItem.title, currentItem.text))
                }
                saveDismissedSignatures()
            }
            runCatching { cancelNotification(key) }
            _notifications.update { list -> list.filterNot { it.key == key } }
        }
    }

    fun dismissAll() {
        runCatching {
            val currentItems = _notifications.value
            synchronized(dismissedSignatures) {
                currentItems.forEach { item ->
                    dismissedSignatures.add(signatureFor(item.key, item.title, item.text))
                }
            }
            saveDismissedSignatures()
            runCatching { cancelAllNotifications() }
            _notifications.value = emptyList()
        }
    }
}
