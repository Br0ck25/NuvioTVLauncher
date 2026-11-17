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
        passthrough = settings.forceOpticalPassthrough && settings.decoderPriority != 0,
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
    val subtitlesMustBeOff =
        subtitleLanguageTargets().isEmpty() && !isUserExplicitSubtitleSelection
    if (subtitlesMustBeOff && subtitles.any { it.isSelected }) {
        // VLC can honour a Matroska track's default flag even after the media option;
        // keep turning off any track it selects while the setting is None.
        runCatching { view.selectSubtitleTrackById(-1) }
        Log.i(PlayerRuntimeController.TAG, "VLC subtitles disabled by settings")
    }
    Log.i(PlayerRuntimeController.TAG, "VLC tracks audio=${audio.size} selected=${audio.indexOfFirst { it.isSelected }} subtitles=${subtitles.size}")
    _uiState.update { current ->
        current.copy(
            audioTracks = audio,
            subtitleTracks = subtitles,
            selectedAudioTrackIndex = audio.indexOfFirst { it.isSelected },
            selectedSubtitleTrackIndex = if (subtitlesMustBeOff) -1 else subtitles.indexOfFirst { it.isSelected },
            selectedAddonSubtitle = if (subtitlesMustBeOff) null else current.selectedAddonSubtitle
        )
    }

    // Apply the user's preferred audio/subtitle languages once per media. The flags are set
    // before selecting so the track-change callbacks cannot loop back into this block.
    if (!vlcAudioPreferenceApplied && audio.isNotEmpty()) {
        vlcAudioPreferenceApplied = true
        val preferred = audio.indexOfFirst { track ->
            mpvPreferredAudioLanguages.any { target -> audioTrackMatchesLanguage(track, target) }
        }
        if (preferred >= 0) {
            if (!audio[preferred].isSelected) selectAudioTrack(preferred)
        } else {
            // Tracks may still be arriving one ESAdded event at a time.
            vlcAudioPreferenceApplied = false
        }
    }

    if (!vlcSubtitlePreferenceApplied && subtitles.isNotEmpty()) {
        vlcSubtitlePreferenceApplied = true
        hasScannedTextTracksOnce = true
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        val targets = subtitleLanguageTargets()
        if (targets.isNotEmpty() && subtitles.none { track ->
                targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(track.language, target) }
            }) {
            // The preferred language may arrive in a later ESAdded event.
            vlcSubtitlePreferenceApplied = false
        }
    }
}
