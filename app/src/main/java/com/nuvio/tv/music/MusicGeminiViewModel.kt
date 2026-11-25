package com.nuvio.tv.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.model.Song
import com.nuvio.tv.core.profile.ProfileManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MusicGeminiUiState(
    val profileId: Int = -1,
    val configured: Boolean = false,
    val enabled: Boolean = true,
    val model: String = GeminiSettings.DEFAULT_MODEL,
    val shareTopArtists: Boolean = true,
    val shareLiked: Boolean = true,
    val shareRecents: Boolean = true,
    val sharePlaylists: Boolean = true,
    val generating: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val tracks: List<Song> = emptyList(),
    val albums: List<MusicCuratedMix> = emptyList(),
    val lastGeneratedAtMs: Long = 0L,
)

@HiltViewModel
class MusicGeminiViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val profileManager: ProfileManager,
    private val keyStore: GeminiKeyStore,
) : ViewModel() {
    private val _state = MutableStateFlow(MusicGeminiUiState())
    val state: StateFlow<MusicGeminiUiState> = _state.asStateFlow()

    private var generateJob: Job? = null

    init {
        MusicPlayer.init(context)
        MusicGeminiSettings.init(context)
        viewModelScope.launch {
            profileManager.activeProfileId.collectLatest { profileId -> load(profileId) }
        }
    }

    private fun load(profileId: Int) {
        MusicGeminiSettings.init(context)
        val settings = MusicGeminiSettings.load(profileId)
        val cached = MusicGeminiCache.load(context, profileId)
        _state.value = MusicGeminiUiState(
            profileId = profileId,
            configured = keyStore.read(profileId) != null,
            enabled = settings.enabled,
            model = settings.model,
            shareTopArtists = settings.shareTopArtists,
            shareLiked = settings.shareLiked,
            shareRecents = settings.shareRecents,
            sharePlaylists = settings.sharePlaylists,
            tracks = cached?.tracks.orEmpty(),
            albums = cached?.albums.orEmpty(),
            lastGeneratedAtMs = settings.lastGeneratedAtMs,
        )
    }

    fun saveKey(key: String) {
        val profileId = currentProfileId() ?: return
        if (key.isBlank()) {
            _state.value = _state.value.copy(error = "Paste a Gemini API key first.")
            return
        }
        keyStore.write(profileId, key)
        _state.value = _state.value.copy(configured = true, error = null, notice = "Gemini key saved.")
    }

    fun removeKey() {
        val profileId = currentProfileId() ?: return
        keyStore.write(profileId, null)
        MusicGeminiCache.clear(context, profileId)
        _state.value = _state.value.copy(
            configured = false,
            tracks = emptyList(),
            albums = emptyList(),
            error = null,
            notice = "Gemini key removed.",
        )
    }

    fun setEnabled(enabled: Boolean) {
        val profileId = currentProfileId() ?: return
        MusicGeminiSettings.update(profileId) { it.copy(enabled = enabled) }
        _state.value = _state.value.copy(enabled = enabled)
        if (enabled && _state.value.tracks.isEmpty() && _state.value.albums.isEmpty() &&
            _state.value.configured
        ) {
            generate()
        }
    }

    fun setModel(model: String) {
        val profileId = currentProfileId() ?: return
        MusicGeminiSettings.update(profileId) { it.copy(model = model) }
        _state.value = _state.value.copy(model = model)
    }

    fun setShareTopArtists(value: Boolean) = updateShare { it.copy(shareTopArtists = value) }
    fun setShareLiked(value: Boolean) = updateShare { it.copy(shareLiked = value) }
    fun setShareRecents(value: Boolean) = updateShare { it.copy(shareRecents = value) }
    fun setSharePlaylists(value: Boolean) = updateShare { it.copy(sharePlaylists = value) }

    private fun updateShare(change: (GeminiSettings) -> GeminiSettings) {
        val profileId = currentProfileId() ?: return
        val next = MusicGeminiSettings.update(profileId, change)
        _state.value = _state.value.copy(
            shareTopArtists = next.shareTopArtists,
            shareLiked = next.shareLiked,
            shareRecents = next.shareRecents,
            sharePlaylists = next.sharePlaylists,
        )
    }

    fun generate() {
        if (_state.value.generating) return
        val profileId = currentProfileId() ?: return
        val key = keyStore.read(profileId)
        if (key.isNullOrBlank()) {
            _state.value = _state.value.copy(error = "Add a Gemini API key first.")
            return
        }
        val settings = MusicGeminiSettings.load(profileId)
        generateJob?.cancel()
        generateJob = viewModelScope.launch {
            _state.value = _state.value.copy(generating = true, error = null, notice = null)
            val result = withContext(Dispatchers.IO) {
                MusicGeminiRepository.generate(key, settings)
            }
            result.fold(
                onSuccess = { picks ->
                    MusicGeminiCache.save(context, profileId, picks)
                    MusicGeminiSettings.markGenerated(profileId, "")
                    _state.value = _state.value.copy(
                        generating = false,
                        tracks = picks.tracks,
                        albums = picks.albums,
                        lastGeneratedAtMs = System.currentTimeMillis(),
                        notice = when {
                            picks.tracks.isEmpty() && picks.albums.isEmpty() ->
                                "Gemini didn't return anything playable this time."
                            picks.unmatched > 0 ->
                                "${picks.tracks.size + picks.albums.size} suggestions ready; " +
                                    "${picks.unmatched} couldn't be found."
                            else -> "Gemini suggestions updated."
                        },
                    )
                },
                onFailure = { error ->
                    _state.value = _state.value.copy(
                        generating = false,
                        error = error.message ?: "Couldn't get Gemini suggestions.",
                    )
                },
            )
        }
    }

    fun clearSuggestions() {
        val profileId = currentProfileId() ?: return
        MusicGeminiCache.clear(context, profileId)
        _state.value = _state.value.copy(tracks = emptyList(), albums = emptyList(), notice = "Suggestions cleared.")
    }

    private fun currentProfileId(): Int? =
        profileManager.activeProfileId.value.takeIf { it > 0 }
}
