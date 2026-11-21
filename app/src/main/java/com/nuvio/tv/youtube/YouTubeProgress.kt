package com.nuvio.tv.youtube

import android.content.Context
import android.util.Log
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/** One video you started on the YouTube page, with enough detail for a Home card. */
data class YouTubeHistoryItem(
    val videoId: String,
    val title: String,
    val channel: String?,
    val poster: String?,
    val positionMs: Long,
    val durationMs: Long,
    val lastWatched: Long,
) {
    val contentId: String get() = YouTubeProgress.ID_PREFIX + videoId

    val percent: Int
        get() = if (durationMs <= 0L) 0 else ((positionMs * 100) / durationMs).toInt().coerceIn(0, 100)

    /** Near the end counts as watched, so it leaves Continue Watching like any other title. */
    val isFinished: Boolean
        get() = durationMs > 0L && (
            percent >= 95 ||
                (durationMs > 120_000L && positionMs >= durationMs - YouTubeProgress.FINISHED_MARGIN_MS)
            )

    val posterOrStill: String
        get() = poster?.takeIf { it.isNotBlank() }
            ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
}

/**
 * Watch progress for YouTube videos.
 *
 * Nuvio's player calls [record] as a video plays. That keeps a local copy (for the bar on each
 * card, signed in or not) and, when signed in, tells YouTube too, so the video shows up in your
 * YouTube history and carries its watched bar on your other devices.
 *
 * The local copy also holds the title, channel and thumbnail, which is what the optional
 * Continue Watching row on Home reads. It never becomes a normal WatchProgress entry: YouTube
 * stays out of Nuvio's history, Trakt and Next Up.
 */
object YouTubeProgress {
    private const val TAG = "YouTubeProgress"
    private const val PREFS = "youtube_progress"
    const val ID_PREFIX = "youtube:"

    /** Each report to YouTube is a few requests; once every this often is plenty. */
    private const val REPORT_EVERY_MS = 20_000L

    /** A video this close to its end counts as watched. */
    internal const val FINISHED_MARGIN_MS = 20_000L

    private const val KEY_POSITION = "p"
    private const val KEY_DURATION = "d"
    private const val KEY_LAST_WATCHED = "at"
    private const val KEY_TITLE = "t"
    private const val KEY_CHANNEL = "c"
    private const val KEY_POSTER = "i"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "yt-progress").apply { isDaemon = true } }
    private val lastReported = ConcurrentHashMap<String, Long>()

    private val _changes = MutableStateFlow(0)

    /** One backfill per process; a failure must not retry on every progress tick. */
    @Volatile private var backfillAttempted = false
    @Volatile private var backfillAtMs = 0L
    private const val BACKFILL_COOLDOWN_MS = 10L * 60L * 1000L

    /** Bumped whenever a video's saved place or details change; Home rebuilds its row from it. */
    val changes: StateFlow<Int> = _changes

    fun isYouTubeId(contentId: String?) = contentId?.startsWith(ID_PREFIX) == true

    /** Called by the player with where it's got to. Safe to call often. */
    fun record(
        context: Context,
        contentId: String?,
        positionMs: Long,
        durationMs: Long,
        title: String? = null,
        channel: String? = null,
        poster: String? = null,
    ) {
        val videoId = contentId?.removePrefix(ID_PREFIX)?.takeIf { it.isNotBlank() } ?: return
        if (positionMs < 1000) return
        val app = context.applicationContext
        store(app, videoId, positionMs, durationMs, title, channel, poster)

        val now = android.os.SystemClock.elapsedRealtime()
        val last = lastReported[videoId] ?: 0L
        // Always report the end of a video; otherwise keep to the interval.
        val finished = durationMs > 0 && positionMs >= durationMs - 5_000L
        if (!finished && now - last < REPORT_EVERY_MS) return
        lastReported[videoId] = now

        worker.execute {
            runCatching {
                val service = YouTubeAccountScopes.engine(app, AccountScope.ONETUBE)
                if (!service.signInService.isSigned) return@execute
                service.mediaItemService.updateHistoryPosition(videoId, positionMs / 1000f)
                Log.i(TAG, "reported $videoId at ${positionMs / 1000}s")
            }.onFailure { Log.w(TAG, "couldn't report $videoId", it) }
        }
    }

    private fun store(
        context: Context,
        videoId: String,
        positionMs: Long,
        durationMs: Long,
        title: String?,
        channel: String?,
        poster: String?,
    ) {
        runCatching {
            val prefs = prefs(context)
            val previous = parse(videoId, prefs.getString(videoId, null))
            val savedDuration = durationMs.takeIf { it > 0L } ?: previous?.durationMs ?: 0L
            val json = JSONObject().apply {
                put(KEY_POSITION, positionMs)
                put(KEY_DURATION, savedDuration)
                put(KEY_LAST_WATCHED, System.currentTimeMillis())
                (title?.takeIf { it.isNotBlank() } ?: previous?.title)
                    ?.let { put(KEY_TITLE, it) }
                (channel?.takeIf { it.isNotBlank() } ?: previous?.channel)
                    ?.let { put(KEY_CHANNEL, it) }
                (poster?.takeIf { it.isNotBlank() } ?: previous?.poster)
                    ?.let { put(KEY_POSTER, it) }
            }
            prefs.edit().putString(videoId, json.toString()).apply()
            _changes.value = _changes.value + 1
        }.onFailure { Log.w(TAG, "couldn't save $videoId locally", it) }
    }

    /** How much of the video you've watched, 0 to 100, from this device. */
    fun localPercent(context: Context, videoId: String?): Int {
        if (videoId == null) return 0
        val entry = runCatching {
            parse(videoId, prefs(context.applicationContext).getString(videoId, null))
        }.getOrNull() ?: return 0
        return entry.percent
    }

    /** Everything with a saved place, newest first. */
    fun history(context: Context, limit: Int = 60): List<YouTubeHistoryItem> {
        val prefs = runCatching {
            prefs(context.applicationContext)
        }.getOrNull() ?: return emptyList()
        return prefs.all.asSequence()
            .mapNotNull { (id, value) -> parse(id, value as? String) }
            .sortedByDescending { it.lastWatched }
            .take(limit)
            .toList()
    }

    /** The videos that belong in Continue Watching: started, not finished, with a real length. */
    fun continueWatching(context: Context, limit: Int = 30): List<YouTubeHistoryItem> =
        history(context, limit = limit * 2)
            .filter { it.durationMs > 0L && it.positionMs >= 5_000L && !it.isFinished }
            .take(limit)

    /**
     * Fills in title/channel for videos saved by an older version, which kept only the place.
     * One request each, at most a handful, and the result is cached in the same store.
     */
    fun backfillMissingDetails(context: Context) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (backfillAttempted && now - backfillAtMs < BACKFILL_COOLDOWN_MS) return
        val missing = history(context)
            .filter { it.title.isBlank() || it.durationMs <= 0L }
            .take(12)
        if (missing.isEmpty()) {
            backfillAttempted = true
            return
        }
        backfillAttempted = true
        backfillAtMs = now
        GlobalPreferences.instance(context.applicationContext)
        for (entry in missing) {
            val meta = runCatching {
                YouTubeAccountScopes.engine(context, AccountScope.ONETUBE)
                    .mediaItemService.getMetadata(entry.videoId)
            }.getOrNull() ?: continue
            store(
                context = context,
                videoId = entry.videoId,
                positionMs = entry.positionMs,
                durationMs = entry.durationMs.takeIf { it > 0L } ?: meta.durationMs,
                title = meta.title,
                channel = meta.author,
                poster = "https://i.ytimg.com/vi/${entry.videoId}/hqdefault.jpg",
            )
        }
    }

    // Per profile: each profile's watch positions stay its own (profile 1 shares the old file name).
    private fun prefs(context: Context) =
        com.nuvio.tv.core.profile.ActiveProfile.prefs(context.applicationContext, PREFS)

    private fun parse(videoId: String, raw: String?): YouTubeHistoryItem? {
        if (raw.isNullOrBlank()) return null
        val json = runCatching { JSONObject(raw) }.getOrNull()
        if (json != null) {
            val position = json.optLong(KEY_POSITION, 0L)
            val duration = json.optLong(KEY_DURATION, 0L)
            if (position <= 0L) return null
            return YouTubeHistoryItem(
                videoId = videoId,
                title = json.optString(KEY_TITLE).trim(),
                channel = json.optString(KEY_CHANNEL).trim().takeIf { it.isNotEmpty() },
                poster = json.optString(KEY_POSTER).trim().takeIf { it.isNotEmpty() },
                positionMs = position,
                durationMs = duration,
                lastWatched = json.optLong(KEY_LAST_WATCHED, 0L),
            )
        }
        // Older versions stored "position,duration" only.
        val parts = raw.split(',')
        val position = parts.getOrNull(0)?.toLongOrNull() ?: return null
        val duration = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        if (position <= 0L) return null
        return YouTubeHistoryItem(
            videoId = videoId,
            title = "",
            channel = null,
            poster = null,
            positionMs = position,
            durationMs = duration,
            lastWatched = 0L,
        )
    }
}
