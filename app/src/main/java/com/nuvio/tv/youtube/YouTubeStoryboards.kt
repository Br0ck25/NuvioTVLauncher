package com.nuvio.tv.youtube

import android.util.Log
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemStoryboard
import com.liskovsoft.youtubeapi.formatbuilders.storyboard.YouTubeStoryParser
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItemStoryboard
import com.liskovsoft.youtubeapi.videoinfo.V2.VideoInfoService
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The storyboard spec that came back with a video's playback info. The spec is just sprite-sheet
 * URLs and tile geometry; the images themselves are fetched on demand by the seek preview.
 *
 * The playback client used for the actual stream (VISIONOS in practice) does not always include
 * storyboards. In that case [ensure] asks the older MediaServiceCore video-info path, which also
 * repairs missing storyboards from its HLS response. That request runs in the background, once per
 * video, so it never delays playback.
 */
object YouTubeStoryboards {
    private const val TAG = "YouTubeStoryboards"
    private const val MAX_ENTRIES = 32
    private const val LEGACY_FETCH_DELAY_MS = 1_000L

    private val entries = ConcurrentHashMap<String, MediaItemStoryboard>()
    private val attempted = ConcurrentHashMap.newKeySet<String>()
    private val fetching = ConcurrentHashMap<String, Boolean>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _version = MutableStateFlow(0)
    /** Increments whenever a storyboard arrives or is removed, so the player can recompose. */
    val version: StateFlow<Int> = _version.asStateFlow()

    fun put(videoId: String, storyboard: MediaItemStoryboard?) {
        if (storyboard == null) {
            entries.remove(videoId)
            _version.value++
            return
        }
        if (entries.size >= MAX_ENTRIES) entries.clear()
        entries[videoId] = storyboard
        attempted.remove(videoId)
        _version.value++
        Log.i(TAG, "storyboard ready video=$videoId")
    }

    fun get(videoId: String): MediaItemStoryboard? = entries[videoId]

    fun clear(videoId: String) {
        entries.remove(videoId)
        attempted.remove(videoId)
        _version.value++
    }

    /**
     * Fetches a missing storyboard once, in the background. Safe to call from composition: it
     * returns immediately and never repeats the request for the same video in this session.
     */
    fun ensure(videoId: String) {
        if (entries.containsKey(videoId)) return
        if (!attempted.add(videoId)) return
        if (fetching.putIfAbsent(videoId, true) != null) return
        scope.launch {
            try {
                // Give playback a moment to start before the extra video-info request.
                delay(LEGACY_FETCH_DELAY_MS)
                val storyboard = fetchLegacy(videoId)
                Log.i(TAG, "legacy storyboard video=$videoId available=${storyboard != null}")
                if (storyboard != null) put(videoId, storyboard)
            } finally {
                fetching.remove(videoId)
            }
        }
    }

    private fun fetchLegacy(videoId: String): MediaItemStoryboard? = runCatching {
        val videoInfo = VideoInfoService.instance().getVideoInfo(videoId, null) ?: return@runCatching null
        val spec = videoInfo.storyboardSpec ?: return@runCatching null
        val parser = YouTubeStoryParser.from(spec)
        parser.setSegmentDurationUs(videoInfo.segmentDurationUs)
        parser.setStartSegmentNum(videoInfo.startSegmentNum)
        val story = parser.extractStory() ?: return@runCatching null
        YouTubeMediaItemStoryboard.from(story)
    }.onFailure { Log.w(TAG, "legacy storyboard failed video=$videoId", it) }.getOrNull()
}
