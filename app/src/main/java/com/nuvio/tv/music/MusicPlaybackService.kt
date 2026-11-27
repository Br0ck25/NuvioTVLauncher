package com.nuvio.tv.music

import android.app.Notification
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
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaStyleNotificationHelper
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Keeps Music playing when the music screens are not on screen.
 *
 * This deliberately is not a MediaSessionService: Media3's service waits for a player state
 * change before calling startForeground(), and on newer Android versions a startForegroundService()
 * call that isn't followed quickly enough kills the process. A plain foreground service can call
 * startForeground() immediately, every time it is started, and still expose the MediaSession for
 * hardware buttons and the system's media controls.
 */
@UnstableApi
class MusicPlaybackService : Service() {
    companion object {
        private const val CHANNEL_ID = "nuvio_music_playback"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_TOGGLE = "com.nuvio.tv.music.TOGGLE"
        private const val ACTION_NEXT = "com.nuvio.tv.music.NEXT"
        private const val ACTION_PREVIOUS = "com.nuvio.tv.music.PREVIOUS"
        private const val ACTION_STOP = "com.nuvio.tv.music.STOP"

        @Volatile
        var running = false
            private set

        /** Starts the playback service. Safe to call before the first track plays. */
        fun start(context: Context) {
            if (running) return // the service already exists and follows the player state
            val app = context.applicationContext
            val intent = Intent(app, MusicPlaybackService::class.java)
            runCatching { ContextCompat.startForegroundService(app, intent) }
        }

        /** Stops playback, clears its saved queue, and removes the foreground notification. */
        fun stopAndClear(context: Context) {
            val app = context.applicationContext
            if (running) {
                val intent = Intent(app, MusicPlaybackService::class.java).setAction(ACTION_STOP)
                if (runCatching { ContextCompat.startForegroundService(app, intent) }.isSuccess) return
            }
            MusicPlayer.stop()
            app.getSystemService(NotificationManager::class.java)?.cancel(NOTIFICATION_ID)
        }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        MusicPlayer.init(this)
        createChannel()
        // Satisfy the foreground start immediately; the notification is refined as tracks change.
        startForeground(NOTIFICATION_ID, buildNotification())
        notificationJob = scope.launch {
            MusicPlayer.state
                .map { Triple(it.song?.videoId, it.isPlaying, it.error) }
                .distinctUntilChanged()
                .collect {
                    if (MusicPlayer.state.value.song == null) return@collect
                    startForeground(NOTIFICATION_ID, buildNotification())
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // A repeated startForegroundService() still expects a startForeground() call, even when
        // the service is already running. Do it before anything else.
        startForeground(NOTIFICATION_ID, buildNotification())
        when (intent?.action) {
            ACTION_TOGGLE -> MusicPlayer.toggle()
            ACTION_NEXT -> MusicPlayer.next()
            ACTION_PREVIOUS -> MusicPlayer.previous()
            ACTION_STOP -> {
                MusicPlayer.stop()
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return START_NOT_STICKY
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!MusicPlayer.isPlayingNow) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        running = false
        notificationJob?.cancel()
        scope.cancel()
        MusicPlayer.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            withAppLocale().getString(R.string.music_playback_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = withAppLocale().getString(R.string.music_playback_channel_description)
            setShowBadge(false)
            enableVibration(false)
            setSound(null, null)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val state = MusicPlayer.state.value
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_music_notification)
            .setContentTitle(state.song?.title ?: "Nuvio Music")
            .setContentText(state.song?.artist ?: "Ready to play")
            .setContentIntent(openAppIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP, 4))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(state.isPlaying)
            .addAction(
                android.R.drawable.ic_media_previous,
                "Previous",
                serviceIntent(ACTION_PREVIOUS, 1)
            )
            .addAction(
                if (state.isPlaying) android.R.drawable.ic_media_pause
                else android.R.drawable.ic_media_play,
                if (state.isPlaying) "Pause" else "Play",
                serviceIntent(ACTION_TOGGLE, 2)
            )
            .addAction(
                android.R.drawable.ic_media_next,
                "Next",
                serviceIntent(ACTION_NEXT, 3)
            )

        MusicPlayer.mediaSession?.let { session ->
            builder.setStyle(
                MediaStyleNotificationHelper.MediaStyle(session)
                    .setShowActionsInCompactView(0, 1, 2)
            )
        }
        return builder.build()
    }

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

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, MusicPlaybackService::class.java).setAction(action)
        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
