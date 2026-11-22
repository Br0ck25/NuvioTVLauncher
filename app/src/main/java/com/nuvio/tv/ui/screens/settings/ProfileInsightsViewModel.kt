package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/** Numbers about what this profile has watched, worked out from the watch history already on the device. */
data class ProfileInsights(
    val continueWatching: Int = 0,
    val inProgress: Int = 0,
    val moviesWatched: Int = 0,
    val episodesWatched: Int = 0,
    val seriesWatched: Int = 0,
    val libraryItems: Int = 0,
    /** Series with the most watched episodes, as (title, episodes). */
    val topSeries: List<Pair<String, Int>> = emptyList(),
    /** The latest things watched, newest first. */
    val recent: List<String> = emptyList(),
    val loaded: Boolean = false
)

@HiltViewModel
class ProfileInsightsViewModel @Inject constructor(
    watchProgressRepository: WatchProgressRepository,
    libraryRepository: LibraryRepository
) : ViewModel() {

    val insights: StateFlow<ProfileInsights> = combine(
        watchProgressRepository.allProgress,
        watchProgressRepository.continueWatching,
        watchProgressRepository.watchedItems,
        libraryRepository.libraryItems
    ) { progress, continueWatching, watched, library ->
        val movies = watched.filter { it.contentType == "movie" }.map { it.contentId }.toSet()
        val episodes = watched
            .filter { it.contentType != "movie" && it.season != null && it.episode != null }
            .distinctBy { Triple(it.contentId, it.season, it.episode) }
        val titleById = watched.associate { it.contentId to it.title }
        val topSeries = episodes.groupBy { it.contentId }
            .map { (id, list) -> (titleById[id] ?: id) to list.size }
            .sortedByDescending { it.second }
            .take(3)
        ProfileInsights(
            continueWatching = continueWatching.size,
            inProgress = progress.map { it.contentId }.toSet().size,
            moviesWatched = movies.size,
            episodesWatched = episodes.size,
            seriesWatched = episodes.map { it.contentId }.toSet().size,
            libraryItems = library.size,
            topSeries = topSeries,
            recent = progress.sortedByDescending { it.lastWatched }.map { it.name }.distinct().take(3),
            loaded = true
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ProfileInsights())
}
