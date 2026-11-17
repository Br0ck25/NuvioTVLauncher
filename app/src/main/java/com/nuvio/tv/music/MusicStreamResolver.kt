package com.nuvio.tv.music

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.MimeTypes
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import com.nuvio.tv.youtube.AccountScope
import com.nuvio.tv.youtube.YouTubeAccountScopes
import com.nuvio.tv.youtube.YouTubeMpdServer
import java.util.concurrent.ConcurrentHashMap

/** A stream URL for one track, resolved through the YouTube engine this app already ships. */
data class ResolvedMusicStream(
    val videoId: String,
    val url: String,
    val headers: Map<String, String> = emptyMap(),
    val mimeType: String? = null,
    /** The addon that served this stream, when it came from one; null for YouTube. */
    val sourceName: String? = null,
    val lookedUpAt: Long = SystemClock.elapsedRealtime()
) {
    /** YouTube's stream links stop working after a few hours. */
    val isFresh: Boolean get() = SystemClock.elapsedRealtime() - lookedUpAt < 20 * 60 * 1000L
}

/**
 * Resolves audio streams for the Music section.
 *
 * The section's catalogue and search results come from BitChord's InnerTube client (the files
 * under `com.music.bitchord`, ported under GPL-3.0). Playback stays on Nuvio's own YouTube
 * engine so the app keeps one sign-in, one set of client fallbacks and the same stream handling
 * as the YouTube section.
 */
object MusicStreamResolver {
    private const val TAG = "MusicStream"
    private const val MAX_CLIENT_SWITCHES = 5
    /** Addon URLs are signed by their server; assume a shorter life than YouTube's. */
    private const val ADDON_STREAM_TTL_MS = 4 * 60 * 1000L

    /**
     * Clients whose plain stream URLs usually answer 403. The engine steps through the others
     * when one of these comes back without playable formats.
     */
    private val BROKEN_CLIENTS = setOf(
        "WEB", "WEB_CREATOR", "WEB_REMIX", "WEB_EMBEDDED_PLAYER", "MWEB",
        "ANDROID_VR", "iOS", "TVHTML5_SIMPLY_EMBEDDED_PLAYER"
    )

    private val cache = ConcurrentHashMap<String, ResolvedMusicStream>()

    @Volatile
    private var initialized = false

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        // MediaServiceCore reads its prefs through this singleton, so it must exist first.
        GlobalPreferences.instance(app)
        runCatching { com.nuvio.tv.youtube.YouTubeContentRegion.apply(app) }
        runCatching {
            // VLC's adaptive HLS path crashes on YouTube manifests; same guard as the YouTube tab.
            MediaServiceData.instance().setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, false)
        }
        YouTubeMpdServer.attach(app.cacheDir)
        initialized = true
    }

    /** A cached, still-valid stream for [videoId], if there is one. */
    fun cached(videoId: String): ResolvedMusicStream? =
        cache[videoId]?.takeIf { stream ->
            val ttl = if (MusicAddonRegistry.isAddonTrack(videoId)) {
                ADDON_STREAM_TTL_MS
            } else {
                20 * 60 * 1000L
            }
            SystemClock.elapsedRealtime() - stream.lookedUpAt < ttl
        }

    /** Blocking; call from an IO dispatcher. */
    fun resolve(videoId: String): ResolvedMusicStream {
        cached(videoId)?.let { return it }
        if (MusicAddonRegistry.isAddonTrack(videoId)) return resolveAddon(videoId)
        val context = appContext ?: error("MusicStreamResolver.init() has not been called")
        // Music has its own YouTube sign-in. Make the engine hold Music's profiles before asking
        // for the stream; otherwise it resolves with whichever profile OneTube used last.
        val manager = YouTubeAccountScopes.engine(context, AccountScope.MUSIC)

        runCatching { manager.resetPlaybackClient() }
        var info = manager.mediaItemService.getFormatInfoObserve(videoId).blockingFirst()
        var attempts = 0
        while (!playable(info) && attempts++ < MAX_CLIENT_SWITCHES) {
            Log.i(TAG, "client ${info.clientInfo?.clientName} gave no playable formats for $videoId; switching")
            runCatching { manager.switchNextClientNow() }
            info = manager.mediaItemService.getFormatInfoObserve(videoId).blockingFirst()
        }
        if (info.isUnplayable) {
            error(friendlyReason(info.playabilityReason))
        }

        val localDashUrl = dashUrl(videoId, info)
        val hlsUrl = if (localDashUrl == null) info.hlsManifestUrl else null
        val url = localDashUrl
            ?: hlsUrl
            ?: info.dashManifestUrl
            ?: info.urlFormats?.firstOrNull()?.url
            ?: error("The music service didn't return an audio stream for this track.")
        val mimeType = when {
            localDashUrl != null || url == info.dashManifestUrl -> "application/dash+xml"
            hlsUrl != null || url.contains(".m3u8", ignoreCase = true) -> "application/x-mpegURL"
            url.contains(".m4a", ignoreCase = true) || url.contains(".mp4", ignoreCase = true) ->
                "audio/mp4"
            url.contains(".webm", ignoreCase = true) -> "audio/webm"
            else -> null
        }

        val headers = info.clientInfo?.userAgent?.takeIf { it.isNotBlank() }
            ?.let { mapOf("User-Agent" to it) }
            .orEmpty()

        Log.i(
            TAG,
            "resolved $videoId client=${info.clientInfo?.clientName} " +
                "dash=${info.containsDashFormats()} hls=${info.containsHlsUrl()}"
        )
        val resolved = ResolvedMusicStream(videoId, url, headers, mimeType)
        if (cache.size >= 64) cache.clear()
        cache[videoId] = resolved
        return resolved
    }

    /** Drops every cached stream; used when playback keeps failing. */
    fun clearCache() = cache.clear()

    /**
     * Asks the addon that produced this row for its stream. The addon owns the URL and we cache
     * it exactly like a YouTube resolution, so repeated plays of the same row are free.
     */
    private fun resolveAddon(videoId: String): ResolvedMusicStream {
        val stream = MusicAddonRegistry.resolveStream(videoId).getOrThrow()
        val resolved = ResolvedMusicStream(
            videoId = videoId,
            url = stream.url,
            mimeType = stream.resolvedMimeType(),
            sourceName = stream.sourceName
        )
        if (cache.size >= 64) cache.clear()
        cache[videoId] = resolved
        return resolved
    }

    private fun playable(info: MediaItemFormatInfo): Boolean {
        if (info.isUnplayable) return false
        if (!info.isLive && info.clientInfo?.clientName in BROKEN_CLIENTS) return false
        if (info.isLive) {
            return info.containsHlsUrl() || !info.dashManifestUrl.isNullOrBlank() || info.containsUrlFormats()
        }
        return info.containsDashFormats()
    }

    /**
     * Nuvio's local DASH manifest: it is generated by the engine, held by [YouTubeMpdServer] and
     * served over loopback, which is the only way to hand ExoPlayer a manifest too large for a
     * route.
     */
    private fun dashUrl(videoId: String, info: MediaItemFormatInfo): String? {
        if (!info.containsDashFormats()) return null
        val mpd = runCatching { info.createMpdStream()?.use { it.readBytes() } }.getOrNull()
        if (mpd == null || mpd.isEmpty()) return null
        // A dynamic manifest needs an availability time the DASH engine doesn't get; leave live
        // audio to HLS.
        if (mpd.toString(Charsets.UTF_8).contains("type=\"dynamic\"")) return null
        return runCatching { YouTubeMpdServer.publish(videoId, mpd, info.visitorCookie) }.getOrNull()
    }

    private fun friendlyReason(reason: String?): String {
        val r = reason.orEmpty().lowercase()
        return when {
            "age" in r || "confirm your age" in r || "inappropriate" in r ->
                "This track is age-restricted and can't be played here."
            "country" in r || "region" in r || "location" in r ->
                "This track isn't available in your country."
            "private" in r -> "This track is private."
            "copyright" in r -> "This track was removed over a copyright claim."
            "removed" in r || "terminated" in r || "no longer available" in r || "unavailable" in r ->
                "This track has been removed or isn't available."
            "sign in" in r -> "Sign in to play this track."
            reason.isNullOrBlank() -> "This track can't be played."
            else -> reason
        }
    }
}
