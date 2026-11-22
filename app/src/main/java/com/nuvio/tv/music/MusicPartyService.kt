package com.nuvio.tv.music

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale

/**
 * Keeps the process alive while a listening party is open.
 *
 * The party server lives in the app process; without a foreground service Android may freeze
 * the app when the viewer switches screens and the phone page would stop responding.
 */
class MusicPartyService : Service() {
    companion object {
        private const val CHANNEL_ID = "music_party"
        private const val NOTIFICATION_ID = 7303
        private const val ACTION_STOP = "com.nuvio.tv.music.party.STOP"

        fun start(context: Context) {
            val app = context.applicationContext
            val intent = Intent(app, MusicPartyService::class.java)
            runCatching { ContextCompat.startForegroundService(app, intent) }
        }

        fun stop(context: Context) {
            val app = context.applicationContext
            runCatching { app.stopService(Intent(app, MusicPartyService::class.java)) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            MusicPartyManager.stop()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.music_party_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = withAppLocale().getString(R.string.music_party_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = NotificationCompat.Builder(this, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_music_notification)
        .setContentTitle(withAppLocale().getString(R.string.music_party_notification_title))
        .setContentText(withAppLocale().getString(R.string.music_party_notification_text))
        .setContentIntent(openAppIntent())
        .setOngoing(true)
        .setSilent(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
        .addAction(
            android.R.drawable.ic_menu_close_clear_cancel,
            "End party",
            PendingIntent.getService(
                this,
                1,
                Intent(this, MusicPartyService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )
        .build()

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
