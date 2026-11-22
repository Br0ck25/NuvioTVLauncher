package com.nuvio.tv.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MusicLyricsViewModel @Inject constructor() : ViewModel() {
    sealed interface State {
        data object Idle : State
        data object Loading : State
        data class Ready(val lyrics: MusicLyricsRepository.Lyrics) : State
        data class Unavailable(val message: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedVideoId: String? = null

    fun load(song: Song?) {
        if (song == null) {
            loadJob?.cancel()
            loadedVideoId = null
            _state.value = State.Idle
            return
        }
        if (song.videoId == loadedVideoId && _state.value is State.Ready) return
        loadedVideoId = song.videoId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.value = State.Loading
            val result = runCatching { MusicLyricsRepository.lyrics(song) }.getOrNull()
            _state.value = if (result != null) {
                State.Ready(result)
            } else {
                State.Unavailable("No lyrics found for this track.")
            }
        }
    }
}
