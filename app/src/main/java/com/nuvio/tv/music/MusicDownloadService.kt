package com.nuvio.tv.music

import android.app.Notification
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.nuvio.tv.R

/** Keeps the Media3 download queue alive while the viewer browses elsewhere. */
@UnstableApi
class MusicDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.music_downloads_channel_name,
    R.drawable.ic_music_notification
) {
    override fun getDownloadManager(): DownloadManager =
        MusicDownloads.downloadManager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(
        downloads: MutableList<Download>,
        notMetRequirements: Int
    ): Notification {
        val helper = DownloadNotificationHelper(this, CHANNEL_ID)
        return helper.buildProgressNotification(
            this,
            R.drawable.ic_music_notification,
            null,
            null,
            downloads,
            notMetRequirements
        )
    }

    private companion object {
        const val CHANNEL_ID = "music_downloads"
        const val NOTIFICATION_ID = 7302
    }
}
