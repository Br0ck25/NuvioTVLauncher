package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.youtube.YouTubeProgress
import com.nuvio.tv.youtube.YouTubeSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * YouTube Fork: videos started on the YouTube page, for a separate Continue Watching row.
 *
 * These never enter Nuvio's own WatchProgress store: they stay out of history, Trakt and Next Up.
 * The row only exists while the switch is on, and Nuvio's master Continue Watching switch still
 * applies.
 */
internal fun HomeViewModel.observeYouTubeContinueWatching() {
    viewModelScope.launch {
        combine(
            YouTubeSettings.homeRowChanges,
            YouTubeProgress.changes,
            layoutPreferenceDataStore.continueWatchingEnabled
        ) { _, _, continueWatchingEnabled ->
            continueWatchingEnabled && YouTubeSettings.continueWatchingOnHome(appContext)
        }
            .distinctUntilChanged()
            .map { enabled ->
                if (!enabled) return@map emptyList()
                YouTubeProgress.backfillMissingDetails(appContext)
                YouTubeProgress.continueWatching(appContext).map { entry ->
                    ContinueWatchingItem.InProgress(
                        progress = WatchProgress(
                            contentId = entry.contentId,
                            contentType = "youtube",
                            name = entry.title.ifBlank { appContext.getString(com.nuvio.tv.R.string.youtube_continue_watching_video) },
                            poster = entry.posterOrStill,
                            backdrop = null,
                            logo = null,
                            videoId = entry.contentId,
                            season = null,
                            episode = null,
                            episodeTitle = entry.channel,
                            position = entry.positionMs,
                            duration = entry.durationMs,
                            lastWatched = entry.lastWatched,
                            progressPercent = entry.percent.toFloat(),
                        )
                    )
                }
            }
            .flowOn(Dispatchers.IO)
            .collect { items ->
                _uiState.update { state ->
                    if (state.youtubeContinueWatchingItems == items) state
                    else state.copy(youtubeContinueWatchingItems = items)
                }
            }
    }
}
