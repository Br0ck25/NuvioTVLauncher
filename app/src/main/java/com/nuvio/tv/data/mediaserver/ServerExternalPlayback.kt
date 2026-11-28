package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.player.ExternalPlayerResult
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Media-server items opened in an external player (Settings → Playback → Player: External or Ask every time).
 *
 * The internal player reports start, progress and stop to the server through [ServerPlayback]. An external player
 * can't be watched, but it does hand back where it stopped ([ExternalPlayerResult]): that is reported here as a start
 * and a stop at that position, so the server keeps resume and watched state in step.
 *
 * Kept apart from Nuvio One's files (see LAUNCHER_RULES.md): the stream screen's launch and MainActivity's result
 * handler each make one call into this.
 */
class ServerExternalPlayback(private val serverPlayback: ServerPlayback) {
    @Volatile private var pendingUrl: String? = null

    /** An external player was started on [url] (the prepared server link); ignored for anything that isn't a server item. */
    fun launched(url: String?) {
        pendingUrl = url?.takeIf { serverPlayback.isServerSource(it) }
    }

    /** The external player came back. No result (many players give none) closes nothing: there is no position to report. */
    fun onResult(result: ExternalPlayerResult?) {
        val url = pendingUrl ?: return
        pendingUrl = null
        if (result == null) return
        // A player that only says "finished" gives no position: the end of the item is where it stopped.
        val duration = result.durationMs?.takeIf { it > 0L }
        val position = if (!result.endedByUser && result.positionMs <= 0L && duration != null) duration else result.positionMs
        if (position <= 0L) return
        serverPlayback.onPlaybackSnapshot(url, position, isPlaying = true, isLoading = false, isEnded = false)
        serverPlayback.onPlaybackSnapshot(url, position, isPlaying = false, isLoading = false, isEnded = true)
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface Access {
        fun serverPlayback(): ServerPlayback
    }

    companion object {
        @Volatile private var instance: ServerExternalPlayback? = null

        fun get(context: Context): ServerExternalPlayback = instance ?: synchronized(this) {
            instance ?: ServerExternalPlayback(
                EntryPointAccessors.fromApplication(context.applicationContext, Access::class.java).serverPlayback()
            ).also { instance = it }
        }
    }
}
