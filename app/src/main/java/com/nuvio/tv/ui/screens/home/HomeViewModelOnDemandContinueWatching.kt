package com.nuvio.tv.ui.screens.home

import androidx.lifecycle.viewModelScope
import com.nuvio.tv.livetv.ondemand.OnDemandDatabase
import com.nuvio.tv.livetv.ondemand.VodHomeIds
import com.nuvio.tv.livetv.ondemand.VodKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The On Demand titles you started, for their own Continue Watching row on Nuvio's Home.
 *
 * On Demand playback stores progress under the `nuviovod:<uid>` content id. Nuvio's normal
 * Continue Watching pipeline cannot resolve those ids through addons, so this maps them back
 * to the provider's own items instead (and the normal row leaves them to this one).
 */
internal fun HomeViewModel.observeOnDemandContinueWatching() {
    viewModelScope.launch {
        combine(
            watchProgressRepository.continueWatching,
            combine(
                liveTvPreferences.settings
                    .map { it.onDemandEnabled && it.onDemandHomeRowEnabled }
                    .distinctUntilChanged(),
                // Nuvio's own master switch for Continue Watching applies to this row too.
                layoutPreferenceDataStore.continueWatchingEnabled
            ) { onDemandEnabled, continueWatchingEnabled ->
                onDemandEnabled && continueWatchingEnabled
            },
            onDemandRepository.hasContent
        ) { progress, enabled, hasContent ->
            Triple(progress, enabled, hasContent)
        }
            .transform { (progress, enabled, hasContent) ->
                if (!enabled || !hasContent) {
                    emit(emptyList<ContinueWatchingItem>())
                    return@transform
                }
                val onDemandProgress = progress
                    .asSequence()
                    .filter { VodHomeIds.isOnDemandContentId(it.contentId) }
                    .sortedByDescending { it.lastWatched }
                    // One card per title; a series keeps its latest episode.
                    .distinctBy { it.contentId }
                val items = buildList {
                    for (entry in onDemandProgress) {
                        val uid = VodHomeIds.uidOfProgress(entry.contentId) ?: continue
                        // Gone from the provider's catalog: leave it out instead of a dead card.
                        val item = onDemandRepository.itemByUid(uid) ?: continue
                        val type = if (item.kind == VodKind.SERIES) "series" else "movie"
                        add(
                            ContinueWatchingItem.InProgress(
                                progress = entry.copy(
                                    name = OnDemandDatabase.displayTitle(item.name),
                                    poster = entry.poster ?: item.icon,
                                    contentType = type
                                ),
                                episodeDescription = entry.episodeTitle,
                                originalPoster = item.icon
                            )
                        )
                    }
                }
                emit(items)
            }
            .flowOn(Dispatchers.IO)
            .collect { items ->
                _uiState.update { state ->
                    if (state.onDemandContinueWatchingItems == items) state
                    else state.copy(onDemandContinueWatchingItems = items)
                }
            }
    }
}
