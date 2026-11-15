package com.nuvio.tv.ui.screens.player.seekpreview

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val TAG = "SeekPreview"
private const val FrameMaxWidthPx = 360
private const val MaxCachedFrames = 100
private const val MinBucketMs = 1_000L
private const val MaxBucketMs = 5_000L
private const val BucketsPerTitle = 1_000L
private const val MaxInitialFailures = 3
private const val IdleReleaseMs = 60_000L

/** One preview picture, and the position of the video it shows. */
class SeekPreviewFrame(val positionMs: Long, val bitmap: ImageBitmap)

/**
 * Produces the picture shown above the seek bar while you scrub. Frames come from a second silent player, one at a time,
 * and the newest request always wins, so the picture trails the bar by a moment instead of queueing up.
 */
@Stable
class SeekPreviewController(
    private val context: Context,
    private val url: String,
    private val headers: Map<String, String>,
    private val releaseAfterScrub: Boolean = false,
    private val storyboard: MediaItemStoryboard? = null,
    private val storyboardVideoId: String? = null,
    private val keepAlive: Boolean = false,
) {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val decodeDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val requests = MutableStateFlow<Long?>(null)
    private val cache = LinkedHashMap<Long, ImageBitmap>()

    private var worker: Job? = null
    private var releaseJob: Job? = null
    private var consecutiveFailures = 0
    private var hasSucceeded = false

    @Volatile
    private var source: SeekPreviewFrameSource? = null
    private var sourceOpened = false

    var frame by mutableStateOf<SeekPreviewFrame?>(null)
        private set

    var isUnavailable by mutableStateOf(false)
        private set

    /** Asks for the picture at [positionMs] of a title [durationMs] long. */
    fun request(positionMs: Long, durationMs: Long) {
        if (isUnavailable || durationMs <= 0L) return
        releaseJob?.cancel()
        val bucket = bucketFor(positionMs.coerceIn(0L, durationMs), durationMs)
        val cached = cache.remove(bucket)?.also { cache[bucket] = it }
        if (cached != null) {
            frame = SeekPreviewFrame(bucket, cached)
        } else {
            nearestCached(bucket)?.let { frame = it }
        }
        requests.value = bucket
        if (worker == null) worker = scope.launch { decodeRequests() }
        // Storyboards hold no decoder, so they stay warm for the whole player session.
        if (!keepAlive) scheduleRelease()
    }

    /** Opens the hidden player ahead of a scrub, if nothing is being asked for already. */
    fun warmUp(positionMs: Long, durationMs: Long) {
        if (isUnavailable || durationMs <= 0L || source != null || requests.value != null) return
        request(positionMs, durationMs)
    }

    /** The scrub ended: hide the picture and let the hidden player go after a while. */
    fun endScrub() {
        frame = null
        requests.value = null
        if (keepAlive) return
        if (releaseAfterScrub) {
            releaseJob?.cancel()
            releaseConnection()
        } else scheduleRelease()
    }

    private fun scheduleRelease() {
        releaseJob?.cancel()
        releaseJob = scope.launch {
            delay(IdleReleaseMs)
            requests.value = null
            cache.clear()
            releaseConnection()
        }
    }

    private fun releaseConnection() {
        CoroutineScope(decodeDispatcher).launch {
            val active = source ?: return@launch
            source = null
            sourceOpened = false
            runCatching { active.close() }
        }
    }

    fun dispose() {
        scope.cancel()
        CoroutineScope(decodeDispatcher).launch {
            runCatching { source?.close() }
            source = null
        }
    }

    private suspend fun decodeRequests() {
        requests.filterNotNull().collect { bucket ->
            if (cache.containsKey(bucket) && source != null) return@collect
            val bitmap = try {
                withContext(decodeDispatcher) { decodeFrame(bucket) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                onFailure(bucket, error)
                return@collect
            }
            if (bitmap == null) {
                onFailure(bucket, null)
                return@collect
            }
            hasSucceeded = true
            consecutiveFailures = 0
            cache[bucket] = bitmap
            while (cache.size > MaxCachedFrames) cache.remove(cache.keys.first())
            val latest = requests.value
            if (latest == bucket) {
                frame = SeekPreviewFrame(bucket, bitmap)
            } else if (latest != null && frame?.positionMs != latest) {
                frame = nearestCached(latest) ?: SeekPreviewFrame(bucket, bitmap)
            }
        }
    }

    private fun decodeFrame(positionMs: Long): ImageBitmap? {
        if (!sourceOpened) {
            sourceOpened = true
            source = if (storyboard != null && storyboardVideoId != null) {
                openYouTubeStoryboardFrameSource(context, storyboard, storyboardVideoId)
            } else {
                openSeekPreviewFrameSource(context, url, headers)
            }
        }
        val active = source ?: throw UnsupportedSource
        return active.frameAt(positionMs, FrameMaxWidthPx)
    }

    private fun onFailure(positionMs: Long, error: Throwable?) {
        consecutiveFailures++
        Log.w(TAG, "frame at ${positionMs}ms failed: ${error?.let { "${it::class.simpleName}: ${it.message}" } ?: "no frame"}")
        if (error === UnsupportedSource || (!hasSucceeded && consecutiveFailures >= MaxInitialFailures)) {
            isUnavailable = true
            frame = null
            worker?.cancel()
            releaseConnection()
        }
    }

    private fun nearestCached(bucket: Long): SeekPreviewFrame? {
        var bestKey: Long? = null
        var bestDistance = Long.MAX_VALUE
        for (key in cache.keys) {
            val distance = abs(key - bucket)
            if (distance < bestDistance) {
                bestDistance = distance
                bestKey = key
            }
        }
        val key = bestKey ?: return null
        return cache[key]?.let { SeekPreviewFrame(key, it) }
    }

    private object UnsupportedSource : RuntimeException("no frame source for this stream")
}

private fun bucketFor(positionMs: Long, durationMs: Long): Long {
    val bucketMs = (durationMs / BucketsPerTitle).coerceIn(MinBucketMs, MaxBucketMs)
    return ((positionMs + bucketMs / 2) / bucketMs * bucketMs).coerceAtMost(durationMs)
}

/** The one controller for the stream being played, kept between the control bar and the quick seek bar so the hidden player stays warm. */
private object SharedSeekPreview {
    private data class Key(
        val url: String,
        val headers: Map<String, String>,
        val releaseAfterScrub: Boolean,
        val storyboard: MediaItemStoryboard?,
        val storyboardVideoId: String?,
        val keepAlive: Boolean,
    )

    private var controller: SeekPreviewController? = null
    private var key: Key? = null

    fun obtain(
        context: Context,
        url: String,
        headers: Map<String, String>,
        releaseAfterScrub: Boolean,
        storyboard: MediaItemStoryboard?,
        storyboardVideoId: String?,
        keepAlive: Boolean,
    ): SeekPreviewController {
        val wanted = Key(url, headers, releaseAfterScrub, storyboard, storyboardVideoId, keepAlive)
        controller?.takeIf { key == wanted }?.let { return it }
        controller?.dispose()
        return SeekPreviewController(
            context,
            url,
            headers,
            releaseAfterScrub,
            storyboard,
            storyboardVideoId,
            keepAlive,
        ).also {
            controller = it
            key = wanted
        }
    }

    fun dispose() {
        controller?.dispose()
        controller = null
        key = null
    }
}

/** Releases the shared preview player/cache when the player screen closes. */
internal fun disposeSeekPreview(context: Context? = null) {
    SharedSeekPreview.dispose()
    // A storyboard close deletes its own video folder; this also clears any folder left behind
    // by a previous player session (or a playback that never got as far as opening a source).
    context?.let { YouTubeStoryboardCache.cleanup(it) }
}

/** A preview controller for [url], or null when there is none to make. */
@Composable
fun rememberSeekPreviewController(
    url: String?,
    headers: Map<String, String>,
    releaseAfterScrub: Boolean = false,
    storyboard: MediaItemStoryboard? = null,
    storyboardVideoId: String? = null,
    keepAlive: Boolean = false,
): SeekPreviewController? {
    val context = LocalContext.current
    return remember(url, headers, releaseAfterScrub, storyboard, storyboardVideoId, keepAlive) {
        if (url == null) {
            // Turning previews off (or moving to a live stream) should not leave the hidden
            // player holding a decoder in the background.
            SharedSeekPreview.dispose()
            null
        } else {
            SharedSeekPreview.obtain(
                context.applicationContext,
                url,
                headers,
                releaseAfterScrub,
                storyboard,
                storyboardVideoId,
                keepAlive,
            )
        }
    }
}
