package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Sets the manual zoom now and, when "Remember zoom for each show" is on, saves it under the
 * title/show so the next episode opens with the same zoom. An unzoomed picture clears the entry.
 */
internal fun PlayerRuntimeController.changeVideoZoom(zoom: VideoZoom) {
    val normalized = VideoZoomPolicy.normalize(zoom)
    _uiState.update { it.copy(videoZoom = normalized) }
    val id = contentId?.takeIf { it.isNotBlank() } ?: return
    if (!rememberVideoZoomEnabled) return
    scope.launch { trackPreferenceDataStore.saveVideoZoom(id, normalized) }
}

/** The zoom saved for this title/show, if remembering is on (called while the player is set up). */
internal suspend fun PlayerRuntimeController.loadRememberedVideoZoom() {
    rememberVideoZoomEnabled = playerSettingsDataStore.playerSettings.first().rememberVideoZoom
    if (!rememberVideoZoomEnabled) return
    val id = contentId?.takeIf { it.isNotBlank() } ?: return
    val zoom = trackPreferenceDataStore.loadVideoZoom(id) ?: return
    _uiState.update { it.copy(videoZoom = zoom) }
}
