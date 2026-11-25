package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.flow.update

internal fun PlayerRuntimeController.isUsingVlcEngine(): Boolean {
    return currentInternalPlayerEngine == InternalPlayerEngine.VLC_PLAYER
}

/**
 * The player composable can appear before or after the stream is resolved. Keep the surface and
 * the media load in sync the same way the MPV path does.
 */
internal fun PlayerRuntimeController.attachVlcView(view: NuvioVlcSurfaceView?) {
    Log.i(PlayerRuntimeController.TAG, "attachVlcView view=${view != null} prepared=$vlcMediaLoadPrepared url=${currentStreamUrl.isNotBlank()}")
    if (vlcView === view) return
    if (view == null) {
        // The player screen is leaving. Release the native player here: the composable's
        // onDispose runs before the controller's own release, so dropping the view without
        // stopping it used to leak a MediaPlayer and its decoder on every VLC exit.
        vlcView?.releasePlayer()
        vlcView = null
        return
    }
    vlcView = view

    var lastLoggedState: NuvioVlcSurfaceView.State? = null
    view.onStateChanged = { state ->
        // TimeChanged fires several times a second; only log meaningful transitions.
        val previous = lastLoggedState
        if (previous == null ||
            previous.isPlaying != state.isPlaying ||
            previous.isBuffering != state.isBuffering ||
            previous.isLive != state.isLive ||
            previous.error != state.error
        ) {
            Log.i(PlayerRuntimeController.TAG, "VLC state $state")
        }
        lastLoggedState = state
        if (isUsingVlcEngine()) {
            _uiState.update { current ->
                current.copy(
                    isPlaying = state.isPlaying,
                    isBuffering = state.isBuffering,
                    isLive = state.isLive,
                    playbackEnded = state.isEnded,
                    error = state.error ?: current.error
                )
            }
        }
    }
    view.onTracksChanged = { updateVlcAvailableTracks() }

    if (!isUsingVlcEngine()) return
    if (currentStreamUrl.isBlank()) return
    if (!vlcMediaLoadPrepared) return
    if (vlcInitializationInProgress) return

    runCatching {
        vlcAudioPreferenceApplied = false
        vlcSubtitlePreferenceApplied = false
        vlcEndReachedHandled = false
        vlcTracksSignature = 0
        vlcAudioTrackCount = 0
        vlcSubtitleTrackCount = 0
        view.setSevenBandEqualizerProfile(
            com.nuvio.tv.core.audio.SevenBandEqualizerSettings.current(
                com.nuvio.tv.core.audio.EqualizerOwner.PLAYBACK
            )
        )
        val startPosition = resolvePendingInitialResumePosition().takeIf { it > 0L } ?: 0L
        view.setMedia(
            currentStreamUrl,
            currentHeaders,
            startPosition,
            audioSlaveUrl,
            config = vlcMediaConfig()
        )
        view.setAspectMode(_uiState.value.effectiveAspectMode)
        view.setAudioDelayMs(_uiState.value.audioDelayMs)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        if (startPosition > 0L) {
            clearPendingInitialResumePosition()
            _uiState.update { it.copy(pendingSeekPosition = null) }
            updatePlaybackTimeline(currentPosition = startPosition)
        }
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "VLC surface attach failed: ${error.message}", error)
        _uiState.update {
            it.copy(
                error = error.message ?: context.getString(R.string.player_error_mpv_surface_failed),
                showLoadingOverlay = false,
                isBuffering = false
            )
        }
    }
}

internal fun PlayerRuntimeController.initializeVlcPlayer(
    url: String,
    headers: Map<String, String>,
    allowEngineFailover: Boolean = true
) {
    Log.i(PlayerRuntimeController.TAG, "initializeVlcPlayer view=${vlcView != null} url=${currentStreamUrl.isNotBlank()}")
    vlcMediaLoadPrepared = true
    _exoPlayer?.release()
    _exoPlayer = null
    trackSelector = null
    releaseMediaSession()
    notifyAudioSessionUpdate(false)

    val view = vlcView
    if (view == null) {
        setLoadingStatus(
            phase = "vlc_waiting_surface",
            message = context.getString(R.string.player_loading_building),
            showOverlay = true
        )
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null
            )
        }
        return
    }

    runCatching {
        setLoadingStatus(
            phase = "vlc_starting",
            message = context.getString(R.string.player_loading_starting),
            showOverlay = true
        )
        vlcAudioPreferenceApplied = false
        vlcSubtitlePreferenceApplied = false
        vlcEndReachedHandled = false
        vlcTracksSignature = 0
        vlcAudioTrackCount = 0
        vlcSubtitleTrackCount = 0
        val initialResumePosition = resolvePendingInitialResumePosition().takeIf { it > 0L } ?: 0L
        playbackAnalyticsDiagnostics.setStartupStartPosition(initialResumePosition)
        view.setMedia(
            url,
            headers,
            initialResumePosition,
            audioSlaveUrl,
            config = vlcMediaConfig()
        )
        view.setAspectMode(_uiState.value.effectiveAspectMode)
        view.setAudioDelayMs(_uiState.value.audioDelayMs)
        view.setSubtitleDelayMs(_uiState.value.subtitleDelayMs)
        if (initialResumePosition > 0L) {
            clearPendingInitialResumePosition()
            _uiState.update { it.copy(pendingSeekPosition = null) }
            updatePlaybackTimeline(currentPosition = initialResumePosition)
        }
        view.setPlaybackSpeed(_uiState.value.playbackSpeed)
        _uiState.update {
            it.copy(
                isBuffering = true,
                isPlaying = false,
                showLoadingOverlay = it.loadingOverlayEnabled,
                error = null,
                audioTracks = emptyList(),
                subtitleTracks = emptyList(),
                videoQualities = emptyList(),
                selectedVideoQualityHeight = 0,
                selectedAudioTrackIndex = -1,
                selectedSubtitleTrackIndex = -1,
                chapters = emptyList(),
                showChaptersPanel = false
            )
        }
        clearChapterSkipState()
        recomputeMergedSkipIntervals()
        cancelPauseOverlay()
        startProgressUpdates()
        startWatchProgressSaving()
        scheduleHideControls()
        emitScrobbleStart()
    }.onFailure { error ->
        Log.e(PlayerRuntimeController.TAG, "libVLC initialize failed: ${error.message}", error)
        val technical = error.message?.trim()?.takeIf { it.isNotEmpty() }
        val explanation = context.getString(R.string.player_error_mpv_playback_failed)
        val detailedError = if (technical == null || technical.equals(explanation, ignoreCase = true)) {
            explanation
        } else {
            "$explanation\n\n$technical"
        }
        if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError, allowEngineFailover)) {
            return@onFailure
        }
        cancelNextEpisodeAutoPlayOnFatalError()
        _uiState.update {
            it.copy(
                error = detailedError,
                showLoadingOverlay = false,
                isBuffering = false,
                playbackEnded = false,
                postPlayMode = null
            )
        }
    }
}

internal fun PlayerRuntimeController.releaseVlcPlayer() {
    vlcView?.releasePlayer()
}

/**
 * Maps the app's playback settings onto the libVLC media options it actually supports.
 * Tunneling and the Media3 DV/HDR filters have no libVLC equivalent and remain engine-specific.
 */
private fun PlayerRuntimeController.vlcMediaConfig(): NuvioVlcSurfaceView.MediaConfig {
    val settings = currentPlayerSettingsForReport
    // ExoPlayer's "buffer before playback" is a start threshold, not a network cache. Handing
    // its 5 s default straight to libVLC adds that delay to every open, so keep VLC's own
    // modest default and only honour a user who lowered it.
    val cacheMs = settings.bufferSettings.bufferForPlaybackMs.coerceIn(1_000, 1_500)
    val liveCacheMs = settings.bufferSettings.bufferForPlaybackAfterRebufferMs.coerceIn(1_000, 1_500)
    return NuvioVlcSurfaceView.MediaConfig(
        networkCachingMs = cacheMs,
        liveCachingMs = liveCacheMs,
        hardwareDecoding = settings.decoderPriority != 2,
        passthrough = settings.forceOpticalPassthrough && settings.decoderPriority != 0 &&
            !com.nuvio.tv.core.audio.SevenBandEqualizerSettings.current(
                com.nuvio.tv.core.audio.EqualizerOwner.PLAYBACK
            ).isEnabled,
        audioChannels = if (settings.effectiveDownmixEnabled) {
            settings.audioOutputChannels.channelCount
        } else {
            null
        },
        subtitlesDisabled = settings.subtitleStyle.preferredLanguage.trim().equals("none", ignoreCase = true) &&
            !isUserExplicitSubtitleSelection
    )
}

internal fun PlayerRuntimeController.updateVlcAvailableTracks() {
    val view = vlcView ?: return
    if (!isUsingVlcEngine()) return
    val audio = view.audioTrackInfos()
    val subtitles = view.subtitleTrackInfos()
    val selectedAudioIndex = audio.indexOfFirst { it.isSelected }
    val selectedSubtitleIndex = subtitles.indexOfFirst { it.isSelected }

    // VLC sends an ESAdded/ESSelected event for every track while a file opens. Rebuilding the
    // UI state and re-applying preferences for each one caused a feedback loop (especially with
    // "subtitles off" and a file carrying dozens of subtitle tracks), so skip unchanged lists.
    val signature = audio.hashCode() * 31 +
        subtitles.hashCode() * 17 +
        selectedAudioIndex * 3 +
        selectedSubtitleIndex
    if (signature == vlcTracksSignature && vlcAudioPreferenceApplied && vlcSubtitlePreferenceApplied) {
        return
    }
    vlcTracksSignature = signature

    if (audio.size != vlcAudioTrackCount) {
        vlcAudioTrackCount = audio.size
        vlcAudioPreferenceApplied = false
    }
    if (subtitles.size != vlcSubtitleTrackCount) {
        vlcSubtitleTrackCount = subtitles.size
        vlcSubtitlePreferenceApplied = false
    }
    if (audio.isEmpty()) vlcAudioPreferenceApplied = true
    if (subtitles.isEmpty()) vlcSubtitlePreferenceApplied = true

    val subtitlesMustBeOff =
        subtitleLanguageTargets().isEmpty() && !isUserExplicitSubtitleSelection
    val selectedSubtitleId = subtitles.firstOrNull { it.isSelected }?.trackId?.toIntOrNull() ?: -1
    if (subtitlesMustBeOff && selectedSubtitleId >= 0) {
        // VLC can honour a Matroska track's default flag even after the media option.
        // Only a real subtitle track (id >= 0) needs switching off; the "Off" entry itself
        // has id -1 and is already selected, which used to make this call repeat forever.
        runCatching { view.selectSubtitleTrackById(-1) }
        Log.i(PlayerRuntimeController.TAG, "VLC subtitles disabled by settings")
    }
    Log.i(PlayerRuntimeController.TAG, "VLC tracks audio=${audio.size} selected=$selectedAudioIndex subtitles=${subtitles.size}")
    _uiState.update { current ->
        current.copy(
            audioTracks = audio,
            subtitleTracks = subtitles,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = if (subtitlesMustBeOff) -1 else selectedSubtitleIndex,
            selectedAddonSubtitle = if (subtitlesMustBeOff) null else current.selectedAddonSubtitle
        )
    }

    // Apply the user's preferred languages once per media. A changed track count re-arms the
    // preference so a track that arrives in a later ESAdded event is still picked up.
    if (!vlcAudioPreferenceApplied && audio.isNotEmpty()) {
        vlcAudioPreferenceApplied = true
        val preferred = audio.indexOfFirst { track ->
            mpvPreferredAudioLanguages.any { target -> audioTrackMatchesLanguage(track, target) }
        }
        if (preferred >= 0 && !audio[preferred].isSelected) selectAudioTrack(preferred)
    }

    if (!vlcSubtitlePreferenceApplied && subtitles.isNotEmpty()) {
        vlcSubtitlePreferenceApplied = true
        if (!subtitlesMustBeOff) {
            hasScannedTextTracksOnce = true
            tryAutoSelectPreferredSubtitleFromAvailableTracks()
        }
    }
}
