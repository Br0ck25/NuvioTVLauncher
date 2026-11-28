package com.nuvio.tv.livetv.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nuvio.tv.data.local.TrackPreferenceDataStore
import com.nuvio.tv.ui.screens.player.VideoZoom
import com.nuvio.tv.ui.screens.player.VideoZoomAxis
import com.nuvio.tv.ui.screens.player.VideoZoomPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Manual zoom for Live TV: width, height and position of the picture, kept for each channel so a channel that
 * doesn't fill the screen stays adjusted the next time it is watched. The same zoom model and saved-zoom store as
 * Nuvio's movie player, with the channel's key (prefixed) as the id.
 */
@HiltViewModel
class LiveVideoZoomViewModel @Inject constructor(
    private val store: TrackPreferenceDataStore
) : ViewModel() {
    private val _zoom = MutableStateFlow(VideoZoom.IDENTITY)
    val zoom: StateFlow<VideoZoom> = _zoom.asStateFlow()

    private var channelKey: String? = null
    private var loadJob: Job? = null
    private var saveJob: Job? = null

    /** The channel on screen changed: show its saved zoom (none: the plain picture). */
    fun open(key: String?) {
        if (key == channelKey) return
        channelKey = key
        loadJob?.cancel()
        saveJob?.cancel()
        _zoom.value = VideoZoom.IDENTITY
        if (key == null) return
        loadJob = viewModelScope.launch {
            val saved = runCatching { store.loadVideoZoom(storeId(key)) }.getOrNull() ?: return@launch
            _zoom.value = saved
        }
    }

    fun adjust(axis: VideoZoomAxis, steps: Int) {
        change(VideoZoomPolicy.adjust(_zoom.value, axis, steps))
    }

    fun reset() = change(VideoZoom.IDENTITY)

    private fun change(next: VideoZoom) {
        loadJob?.cancel()
        _zoom.update { VideoZoomPolicy.normalize(next) }
        val key = channelKey ?: return
        saveJob?.cancel()
        saveJob = viewModelScope.launch {
            // Held-down keys step quickly; save once they settle.
            delay(400)
            runCatching { store.saveVideoZoom(storeId(key), _zoom.value) }
        }
    }

    private fun storeId(channelKey: String) = "livetv:$channelKey"
}
