package com.nuvio.tv.music

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.SystemClock
import android.view.KeyEvent
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.session.MediaSession
import androidx.media3.session.SessionResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.data.model.durationMillis
import com.music.bitchord.data.YtMusicRepository
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The Music section's player: one audio-only ExoPlayer owned by [MusicPlaybackService] and
 * observed directly by the music screens.
 *
 * The queue is kept here rather than in ExoPlayer because each track's stream URL has to be
 * resolved first. The player holds one item at a time and the queue decides what comes next.
 */
enum class RepeatMode { OFF, ALL, ONE }

/** Result of adding a song to the queue from outside the Music screens. */
enum class QueueAddResult { ADDED, STARTED, ALREADY_QUEUED, INVALID }

@UnstableApi
object MusicPlayer {
    data class State(
        val song: Song? = null,
        val queue: List<Song> = emptyList(),
        val index: Int = -1,
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = false,
        val positionMs: Long = 0L,
        val durationMs: Long = 0L,
        val repeatMode: RepeatMode = RepeatMode.OFF,
        val shuffle: Boolean = false,
        val error: String? = null,
        val sleepTimerMinutes: Int? = null,
        val sleepTimerRemainingMs: Long? = null,
        val videoAvailable: Boolean = false,
        val videoLoading: Boolean = false,
        val videoSong: Song? = null
    ) {
        val upNext: List<Song>
            get() = if (queue.isEmpty() || index < 0) emptyList()
            else queue.drop(index + 1).take(40)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var appContext: Context? = null
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null
    private var trackSelector: DefaultTrackSelector? = null
    private var audioSessionId: Int = C.AUDIO_SESSION_ID_UNSET
    private var resolutionJob: Job? = null
    private var prefetchJob: Job? = null
    private var videoJob: Job? = null
    private var tickerJob: Job? = null
    private var fadeJob: Job? = null
    private var fadeOutStarted = false
    @Volatile private var fastProgress = false
    private var sleepTimerEndsAt: Long? = null
    /** The queue before shuffle; used to restore the original order. */
    private var originalQueue: List<Song> = emptyList()
    private var resolvedVideoSong: Song? = null
    private var videoPreparedFor: String? = null

    /** Playhead to restore when the persisted queue is started again. */
    private var resumeVideoId: String? = null
    private var resumePositionMs: Long = 0L
    /** Prepare the next track without starting audio (used while its music video is open). */
    private var startPaused = false

    /** Headers for the track currently being resolved, read when each data source is opened. */
    @Volatile
    private var currentHeaders: Map<String, String> = emptyMap()

    val mediaSession: MediaSession? get() = session
    val isPlayingNow: Boolean get() = player?.isPlaying == true

    /** Lyrics and the seek bar run at 5 Hz while the player screen is open. */
    fun setFastProgress(enabled: Boolean) {
        fastProgress = enabled
    }

    /** Safe to call from the service and from the screens; only builds the player once. */
    @Synchronized
    fun init(context: Context) {
        if (player != null) return
        val app = context.applicationContext
        appContext = app
        MusicLibrary.init(app)
        MusicStreamResolver.init(app)
        MusicSettings.init(app)
        MusicDownloads.init(app)
        MusicWebDav.init(app)
        MusicScrobbler.init(app)
        MusicAddonRegistry.init(app)
        MusicNavidrome.init(app)
        MusicNavidromeSync.init(app)
        restoreQueue(app)

        val selector = DefaultTrackSelector(app).apply {
            setParameters(
                buildUponParameters()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .setMaxAudioBitrate(MusicSettings.audioQuality.value.maxKbps * 1000)
            )
        }
        trackSelector = selector

        val audioManager = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioSessionId = audioManager.generateAudioSessionId()

        val httpFactory = OkHttpDataSource.Factory(
            PlayerPlaybackNetworking.createHttpClient()
        ).apply {
            setUserAgent(
                "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            )
        }
        // Every data source reads the current track's headers, since they change from one
        // YouTube client fallback to the next.
        val headerAwareFactory = object : DataSource.Factory {
            override fun createDataSource(): DataSource {
                val dataSource = httpFactory.createDataSource()
                val headers = currentHeaders
                if (dataSource is HttpDataSource) {
                    headers.forEach { (name, value) ->
                        dataSource.setRequestProperty(name, value)
                    }
                }
                return dataSource
            }
        }

        val exoPlayer = ExoPlayer.Builder(app)
            .setTrackSelector(selector)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(
                    MusicDownloads.cacheDataSourceFactory(
                        DefaultDataSource.Factory(app, headerAwareFactory)
                    )
                )
            )
            .build()
            .apply {
                setAudioSessionId(audioSessionId)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                setHandleAudioBecomingNoisy(true)
                setWakeMode(C.WAKE_MODE_NETWORK)
                setSkipSilenceEnabled(MusicSettings.skipSilence.value)
                setPlaybackSpeed(MusicSettings.playbackSpeed.value)
                addListener(playerListener)
            }

        player = exoPlayer
        session = MediaSession.Builder(app, exoPlayer)
            .setCallback(mediaSessionCallback)
            .build()
        MusicEqualizer.attach(audioSessionId)
        MusicEqualizer.apply(MusicSettings.equalizerPreset.value)
        ensureTicker()
    }

    /** Plays [songs] from [startIndex]. The list becomes the up-next queue. */
    fun playQueue(songs: List<Song>, startIndex: Int = 0) {
        if (player == null) appContext?.let { init(it) }
        val playable = songs.filter { it.videoId.isNotBlank() }
        if (playable.isEmpty()) return
        resumeVideoId = null
        resumePositionMs = 0L
        originalQueue = playable
        val safeStart = startIndex.coerceIn(0, playable.lastIndex)
        val shuffled = _state.value.shuffle
        val queue = if (shuffled) {
            val picked = playable[safeStart]
            listOf(picked) + playable.filterNot { it.videoId == picked.videoId }.shuffled()
        } else {
            playable
        }
        _state.update {
            it.copy(
                queue = queue,
                index = if (shuffled) 0 else safeStart,
                error = null
            )
        }
        playCurrent()
    }

    fun toggle() {
        if (player == null) appContext?.let { init(it) }
        val p = player ?: return
        when {
            p.isPlaying -> p.pause()
            p.playbackState == Player.STATE_ENDED || p.mediaItemCount == 0 -> playCurrent()
            else -> p.play()
        }
    }

    /**
     * Stops audio without throwing away the queue. Canceling an in-flight resolve matters:
     * without it, leaving the app while a track is buffering would still start playback when
     * the stream comes back.
     */
    fun pause() {
        resolutionJob?.cancel()
        prefetchJob?.cancel()
        fadeJob?.cancel()
        fadeOutStarted = false
        player?.volume = 1f
        player?.pause()
        _state.update { it.copy(isBuffering = false, isPlaying = false) }
        persistQueue()
    }

    /**
     * The Music section was closed. Music follows the user only when they opted in;
     * otherwise pause it here (the queue and position are kept for the next visit).
     */
    fun onLeaveMusicScreen() {
        if (MusicSettings.keepPlayingWhileBrowsing.value) return
        if (_state.value.isPlaying || _state.value.isBuffering) pause()
    }

    /** A full-screen video is starting: never play music over it. */
    fun pauseForVideo() {
        if (_state.value.isPlaying || _state.value.isBuffering) pause()
    }

    fun next(auto: Boolean = false) {
        val current = _state.value
        if (current.queue.isEmpty()) return
        // Repeat-one only applies when the track ends on its own; pressing Next still moves on.
        if (auto && current.repeatMode == RepeatMode.ONE) {
            player?.seekTo(0)
            player?.play()
            _state.update { it.copy(positionMs = 0L) }
            persistQueue()
            return
        }
        when {
            current.index < current.queue.lastIndex -> {
                _state.update { it.copy(index = it.index + 1) }
                playCurrent()
            }
            current.repeatMode == RepeatMode.ALL -> {
                _state.update { it.copy(index = 0) }
                playCurrent()
            }
            auto -> {
                val seed = current.song
                if (MusicSettings.autoplay.value && seed != null &&
                    seed.localUri == null && seed.videoId.isNotBlank() &&
                    !MusicAddonRegistry.isAddonTrack(seed.videoId)
                ) {
                    _state.update { it.copy(isBuffering = true) }
                    startRadio(seed)
                } else {
                    player?.pause()
                    _state.update { it.copy(isPlaying = false, positionMs = it.durationMs) }
                    persistQueue()
                }
            }
            else -> {
                _state.update { it.copy(index = 0) }
                playCurrent()
            }
        }
    }

    fun previous() {
        val p = player
        if (p != null && p.currentPosition > 3_000L) {
            p.seekTo(0)
            persistQueue()
            return
        }
        val current = _state.value
        if (current.queue.isEmpty()) return
        if (current.index > 0) {
            _state.update { it.copy(index = it.index - 1) }
            playCurrent()
        } else {
            p?.seekTo(0)
        }
    }

    /**
     * Jumps to a track already in the queue. [startPositionMs] is where playback begins;
     * [play] false prepares the track without starting its audio (used while its video plays).
     */
    fun jumpTo(index: Int, startPositionMs: Long = 0L, play: Boolean = true) {
        val current = _state.value
        if (index !in current.queue.indices) return
        val song = current.queue[index]
        val target = startPositionMs.coerceAtLeast(0L)
        // Already prepared: seek in place. This is the video-to-audio hand-back, where
        // re-resolving the stream would leave a gap between the two players.
        val p = player
        if (p != null && index == current.index && current.song?.videoId == song.videoId &&
            current.error == null && p.mediaItemCount > 0
        ) {
            runCatching { p.seekTo(target) }
            if (play) {
                if (!p.isPlaying) {
                    p.play()
                    startFadeIn()
                }
            } else if (p.isPlaying) {
                p.pause()
            }
            return
        }
        _state.update { it.copy(index = index) }
        resumeVideoId = song.videoId
        resumePositionMs = target
        startPaused = !play
        playCurrent()
    }

    /** Maps a moment in [videoId] back to the current audio track for a seamless switch. */
    fun audioPositionForVideo(videoId: String, videoPositionMs: Long): Long {
        val audio = _state.value.song ?: return videoPositionMs.coerceAtLeast(0L)
        val map = YtMusicRepository.versionTimeMap(videoId, audio.videoId)
            ?: return videoPositionMs.coerceAtLeast(0L)
        return map.map(videoPositionMs.coerceAtLeast(0L))
    }

    /**
     * Appends a song to the shared queue, starting playback when nothing is queued yet.
     * Used by Party Mode, where additions arrive from phones rather than the TV screen.
     */
    fun enqueue(song: Song): QueueAddResult {
        if (song.videoId.isBlank()) return QueueAddResult.INVALID
        if (player == null) appContext?.let { init(it) }
        val current = _state.value
        if (current.queue.any { it.videoId == song.videoId }) {
            return QueueAddResult.ALREADY_QUEUED
        }
        if (current.queue.isEmpty() || current.index !in current.queue.indices) {
            playQueue(listOf(song))
            return QueueAddResult.STARTED
        }
        val queue = current.queue + song
        _state.update { it.copy(queue = queue) }
        originalQueue = if (originalQueue.isEmpty()) queue else originalQueue + song
        persistQueue()
        return QueueAddResult.ADDED
    }

    /** Removes an upcoming track; the playing track cannot be removed this way. */
    fun removeUpcoming(videoId: String): Boolean {
        val current = _state.value
        val at = current.queue.indexOfFirst { it.videoId == videoId }
        if (at < 0 || at == current.index) return false
        val queue = current.queue.toMutableList().apply { removeAt(at) }
        val newIndex = if (at < current.index) current.index - 1 else current.index
        _state.update { it.copy(queue = queue, index = newIndex) }
        originalQueue = originalQueue.filterNot { it.videoId == videoId }
        persistQueue()
        return true
    }

    /** Drops everything after the current track, keeping playback untouched. */
    fun clearUpcoming() {
        val current = _state.value
        val song = current.queue.getOrNull(current.index) ?: return
        _state.update { it.copy(queue = listOf(song), index = 0) }
        originalQueue = listOf(song)
        persistQueue()
    }

    fun seekTo(positionMs: Long) {
        player?.seekTo(positionMs.coerceAtLeast(0L))
        _state.update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
        persistQueue()
    }

    fun setRepeatMode(mode: RepeatMode) {
        _state.update { it.copy(repeatMode = mode) }
        persistQueue()
    }

    /** Off -> all -> one -> off, for the single remote button. */
    fun cycleRepeat() {
        val next = when (_state.value.repeatMode) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.update { it.copy(repeatMode = next) }
        persistQueue()
    }

    /**
     * Shuffles the rest of the queue while keeping the current track. Turning it off restores
     * the order the queue was started in.
     */
    fun setShuffle(enabled: Boolean) {
        val current = _state.value
        val song = current.song
        if (song == null) {
            _state.update { it.copy(shuffle = enabled) }
            return
        }
        if (enabled == current.shuffle) return
        if (enabled) {
            val source = originalQueue.ifEmpty { current.queue }
            val rest = source.filterNot { it.videoId == song.videoId }.shuffled()
            _state.update {
                it.copy(shuffle = true, queue = listOf(song) + rest, index = 0)
            }
        } else {
            val restored = originalQueue.ifEmpty { current.queue }
            val at = restored.indexOfFirst { it.videoId == song.videoId }.coerceAtLeast(0)
            _state.update {
                it.copy(shuffle = false, queue = restored, index = at)
            }
        }
        prefetchNext()
        persistQueue()
    }

    fun setAudioQuality(quality: MusicAudioQuality) {
        MusicSettings.setAudioQuality(quality)
        applyAudioQuality(quality)
    }

    fun setAutoplay(enabled: Boolean) {
        MusicSettings.setAutoplay(enabled)
    }

    fun setSkipSilence(enabled: Boolean) {
        MusicSettings.setSkipSilence(enabled)
        player?.setSkipSilenceEnabled(enabled)
    }

    fun setPlaybackSpeed(speed: Float) {
        val safe = speed.coerceIn(0.5f, 2.0f)
        MusicSettings.setPlaybackSpeed(safe)
        player?.setPlaybackSpeed(safe)
    }

    fun setFadeSeconds(seconds: Int) {
        MusicSettings.setFadeSeconds(seconds)
        fadeJob?.cancel()
        fadeOutStarted = false
        player?.volume = 1f
    }

    fun setEqualizerPreset(preset: MusicEqualizerPreset) {
        MusicSettings.setEqualizerPreset(preset)
        MusicEqualizer.apply(preset)
    }

    /** [minutes] null or zero clears the timer; otherwise playback pauses when it expires. */
    fun setSleepTimer(minutes: Int?) {
        if (minutes == null || minutes <= 0) {
            sleepTimerEndsAt = null
            _state.update {
                it.copy(sleepTimerMinutes = null, sleepTimerRemainingMs = null)
            }
        } else {
            sleepTimerEndsAt = SystemClock.elapsedRealtime() + minutes * 60_000L
            _state.update {
                it.copy(
                    sleepTimerMinutes = minutes,
                    sleepTimerRemainingMs = minutes * 60_000L
                )
            }
        }
    }

    fun startRadio(seed: Song) {
        if (seed.videoId.isBlank() || MusicAddonRegistry.isAddonTrack(seed.videoId)) return
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                YtMusicRepository.radio(seed.videoId)
            }
            result.fold(
                onSuccess = { songs ->
                    if (songs.isEmpty()) {
                        _state.update { it.copy(error = "No radio tracks were found.") }
                    } else {
                        playQueue(songs, 0)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(error = error.message ?: "Couldn't start the radio.")
                    }
                }
            )
        }
    }

    /** Looks up the official video for the current track once Now Playing is open. */
    fun prepareVideoForCurrentTrack() {
        val song = _state.value.song ?: return
        if (videoPreparedFor == song.videoId) return
        videoJob?.cancel()
        videoPreparedFor = song.videoId
        resolvedVideoSong = null
        _state.update {
            it.copy(videoAvailable = false, videoLoading = false, videoSong = null)
        }
        if (song.localUri != null ||
            song.videoId.startsWith("local:") ||
            song.videoId.startsWith("webdav:") ||
            song.videoId.startsWith("navidrome:") ||
            MusicAddonRegistry.isAddonTrack(song.videoId)
        ) {
            return
        }
        if (song.isVideo) {
            resolvedVideoSong = song
            _state.update { it.copy(videoAvailable = true, videoSong = song) }
            return
        }
        _state.update { it.copy(videoLoading = true) }
        videoJob = scope.launch {
            val video = withContext(Dispatchers.IO) {
                runCatching { YtMusicRepository.resolveVideo(song) }.getOrNull()
            }
            if (_state.value.song?.videoId != song.videoId) return@launch
            resolvedVideoSong = video
            _state.update {
                it.copy(
                    videoAvailable = video != null,
                    videoLoading = false,
                    videoSong = video
                )
            }
        }
    }

    /** Where to start the video so it lands on the moment the song is at. */
    fun videoStartPositionMs(): Long {
        val current = _state.value
        val song = current.song ?: return 0L
        val video = resolvedVideoSong ?: return current.positionMs
        val map = YtMusicRepository.versionTimeMap(song.videoId, video.videoId)
        return map?.map(current.positionMs) ?: current.positionMs
    }

    fun stop() {
        resolutionJob?.cancel()
        prefetchJob?.cancel()
        videoJob?.cancel()
        fadeJob?.cancel()
        fadeOutStarted = false
        sleepTimerEndsAt = null
        player?.volume = 1f
        player?.stop()
        player?.clearMediaItems()
        _state.value = State()
        persistQueue()
    }

    fun release() {
        persistQueue()
        resolutionJob?.cancel()
        prefetchJob?.cancel()
        videoJob?.cancel()
        tickerJob?.cancel()
        fadeJob?.cancel()
        tickerJob = null
        sleepTimerEndsAt = null
        MusicEqualizer.release()
        runCatching { session?.release() }
        session = null
        runCatching { player?.release() }
        player = null
        trackSelector = null
        audioSessionId = C.AUDIO_SESSION_ID_UNSET
        _state.value = State()
    }

    // ---- playback ----

    private fun playCurrent() {
        val song = _state.value.queue.getOrNull(_state.value.index) ?: return
        val p = player ?: return
        val paused = startPaused
        startPaused = false
        videoJob?.cancel()
        resolvedVideoSong = null
        videoPreparedFor = null
        if (resumeVideoId != null && resumeVideoId != song.videoId) {
            resumeVideoId = null
            resumePositionMs = 0L
        }
        resolutionJob?.cancel()
        prefetchJob?.cancel()
        // Stop the previous track straight away; otherwise it keeps playing while the new
        // stream is looked up, which reads as the wrong song being selected.
        runCatching { p.stop() }
        resolutionJob = scope.launch {
            _state.update {
                it.copy(
                    song = song,
                    isBuffering = true,
                    isPlaying = false,
                    positionMs = 0L,
                    durationMs = song.durationMillis(),
                    error = null,
                    videoAvailable = false,
                    videoLoading = false,
                    videoSong = null
                )
            }
            persistQueue()
            // Local files skip YouTube lookup entirely.
            val localUri = song.localUri
            if (!localUri.isNullOrBlank()) {
                currentHeaders = MusicWebDav.headersFor(localUri)
                appContext?.let { MusicPlaybackService.start(it) }
                p.setMediaItem(song.toMediaItem(localUri))
                p.prepare()
                applyResumePosition(p, song)
                if (paused) p.playWhenReady = false else {
                    p.play()
                    startFadeIn()
                    MusicLibrary.recordPlayed(song)
                }
                _state.update { it.copy(isBuffering = false) }
                prefetchNext()
                return@launch
            }
            // A completed download is a cache-backed copy, so this also works with no network.
            val downloaded = MusicDownloads.downloadedMediaItem(song)
            if (downloaded != null) {
                currentHeaders = emptyMap()
                appContext?.let { MusicPlaybackService.start(it) }
                p.setMediaItem(downloaded)
                p.prepare()
                applyResumePosition(p, song)
                if (paused) p.playWhenReady = false else {
                    p.play()
                    startFadeIn()
                    MusicLibrary.recordPlayed(song)
                }
                _state.update { it.copy(isBuffering = false) }
                prefetchNext()
                return@launch
            }
            val result = resolveTrackStream(song)
            result.fold(
                onSuccess = { stream ->
                    currentHeaders = stream.headers
                    appContext?.let { MusicPlaybackService.start(it) }
                    p.setMediaItem(song.toMediaItem(stream.url, stream.mimeType))
                    p.prepare()
                    applyResumePosition(p, song)
                    if (paused) p.playWhenReady = false else {
                        p.play()
                        startFadeIn()
                        MusicLibrary.recordPlayed(song)
                    }
                    _state.update { it.copy(isBuffering = false) }
                    prefetchNext()
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = false,
                            error = error.message ?: "This track couldn't be played."
                        )
                    }
                }
            )
        }
    }

    /**
     * Resolves a YouTube row, optionally preferring a matching addon recording.
     *
     * The addon search runs beside the YouTube lookup and only gets a short window: if it has not
     * found a confident match by then, YouTube starts as usual. Setting is off by default.
     */
    private suspend fun resolveTrackStream(song: Song): Result<ResolvedMusicStream> =
        withContext(Dispatchers.IO) {
            if (!MusicSettings.preferAddonSources.value ||
                song.isVideo ||
                MusicAddonRegistry.isAddonTrack(song.videoId)
            ) {
                return@withContext runCatching { MusicStreamResolver.resolve(song.videoId) }
            }
            // Keep both lookups on the player's scope: cancelling a slow addon search must not
            // make the playback coroutine wait for its blocking HTTP call to unwind.
            val youtube = scope.async(Dispatchers.IO) {
                runCatching { MusicStreamResolver.resolve(song.videoId) }
            }
            val addon = scope.async(Dispatchers.IO) {
                runCatching { MusicAddonRegistry.substitute(song) }.getOrNull()
            }
            val substitute = withTimeoutOrNull(2_500L) { addon.await() }
            if (substitute != null) {
                youtube.cancel()
                Result.success(
                    ResolvedMusicStream(
                        videoId = song.videoId,
                        url = substitute.url,
                        mimeType = substitute.resolvedMimeType()
                    )
                )
            } else {
                addon.cancel()
                youtube.await()
            }
        }

    private fun applyAudioQuality(quality: MusicAudioQuality) {
        val selector = trackSelector ?: return
        selector.setParameters(
            selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setMaxAudioBitrate(quality.maxKbps * 1000)
                .build()
        )
    }

    /** Ramps the new track up when a fade transition is selected; otherwise leaves it at full. */
    private fun startFadeIn() {
        fadeJob?.cancel()
        fadeOutStarted = false
        val p = player ?: return
        val seconds = MusicSettings.fadeSeconds.value
        if (seconds <= 0) {
            p.volume = 1f
            return
        }
        p.volume = 0f
        fadeJob = scope.launch {
            val steps = seconds * 20
            for (step in 0..steps) {
                player?.volume = step / steps.toFloat()
                delay(50L)
            }
            player?.volume = 1f
        }
    }

    /** Starts the fade-out once the current track is inside the chosen transition window. */
    private fun maybeFadeOut() {
        val p = player ?: return
        val seconds = MusicSettings.fadeSeconds.value
        if (seconds <= 0 || !p.isPlaying) return
        val duration = p.duration
        if (duration <= 0L) return
        val remaining = duration - p.currentPosition
        val window = seconds * 1000L
        if (fadeOutStarted && remaining > window) {
            fadeOutStarted = false
            fadeJob?.cancel()
            p.volume = 1f
            return
        }
        if (!fadeOutStarted && remaining in 1..window) {
            fadeOutStarted = true
            fadeJob?.cancel()
            fadeJob = scope.launch {
                val steps = (remaining / 50L).toInt().coerceAtLeast(1)
                for (step in steps downTo 0) {
                    player?.volume = step / steps.toFloat()
                    delay(50L)
                }
            }
        }
    }

    /** Resolves the next track while this one is still playing, so skipping is immediate. */
    private fun prefetchNext() {
        val current = _state.value
        if (current.repeatMode == RepeatMode.ONE) return
        val next: Song = current.queue.getOrNull(current.index + 1)
            ?: (if (current.repeatMode == RepeatMode.ALL) current.queue.firstOrNull() else null)
            ?: return
        if (next.localUri != null) return
        if (MusicStreamResolver.cached(next.videoId) != null) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch(Dispatchers.IO) {
            runCatching { MusicStreamResolver.resolve(next.videoId) }
        }
    }

    private fun Song.toMediaItem(url: String, mimeType: String? = null): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(artist)
            .setAlbumTitle(albumName)
            .setArtworkUri(thumbnailUrl?.artworkAt(1080)?.let(Uri::parse))
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .build()
        return MediaItem.Builder()
            .setMediaId(videoId)
            .setUri(url)
            .setMediaMetadata(metadata)
            .apply { mimeType?.let { setMimeType(it) } }
            .build()
    }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_BUFFERING -> _state.update { it.copy(isBuffering = true) }
                Player.STATE_READY -> {
                    player?.audioSessionId?.takeIf { it > 0 }?.let { MusicEqualizer.attach(it) }
                    val duration = player?.duration?.takeIf { it > 0L }
                        ?: _state.value.song?.durationMillis()
                        ?: 0L
                    _state.update {
                        it.copy(
                            isBuffering = false,
                            isPlaying = player?.isPlaying == true,
                            durationMs = duration
                        )
                    }
                }
                Player.STATE_ENDED -> next(auto = true)
            }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _state.update { it.copy(isPlaying = isPlaying) }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update {
                it.copy(
                    isPlaying = false,
                    isBuffering = false,
                    error = "Playback failed: ${error.errorCodeName}"
                )
            }
        }
    }

    /** Saves the current queue and playhead without waiting for the next tick. */
    fun persistNow() {
        persistQueue()
    }

    private fun persistQueue() {
        val app = appContext ?: return
        runCatching { MusicQueueStore.save(app, _state.value) }
    }

    /**
     * Restores only the queue and playhead, never playback. The first press of Play starts the
     * saved track at the saved position; opening the app does not make noise on its own.
     */
    private fun restoreQueue(app: Context) {
        if (_state.value.queue.isNotEmpty()) return
        val restored = MusicQueueStore.restore(app) ?: return
        val index = restored.index.coerceIn(0, restored.songs.lastIndex)
        val song = restored.songs[index]
        val duration = song.durationMillis().takeIf { it > 0L } ?: restored.durationMs
        val savedPosition = restored.positionMs.coerceAtLeast(0L)
        // Treat a track that was effectively finished as starting from the top.
        val resumeAt = if (duration > 0L && savedPosition >= duration - 3_000L) 0L else savedPosition
        _state.value = State(
            song = song,
            queue = restored.songs,
            index = index,
            positionMs = resumeAt,
            durationMs = duration,
            repeatMode = restored.repeatMode,
            shuffle = restored.shuffle
        )
        originalQueue = restored.songs
        resumeVideoId = song.videoId
        resumePositionMs = resumeAt
    }

    private fun applyResumePosition(p: ExoPlayer, song: Song) {
        val id = resumeVideoId ?: return
        if (id != song.videoId) return
        val position = resumePositionMs
        resumeVideoId = null
        resumePositionMs = 0L
        if (position > 0L) runCatching { p.seekTo(position) }
    }

    /**
     * Hardware and Bluetooth Next/Previous become player commands. The player holds one item
     * at a time, so the default commands have nothing to move through; route them to our queue.
     */
    private val mediaSessionCallback = object : MediaSession.Callback {
        override fun onPlayerCommandRequest(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            @Player.Command command: Int
        ): Int = when (command) {
            Player.COMMAND_SEEK_TO_NEXT,
            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM -> {
                next()
                SessionResult.RESULT_SUCCESS
            }
            Player.COMMAND_SEEK_TO_PREVIOUS,
            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM -> {
                previous()
                SessionResult.RESULT_SUCCESS
            }
            else -> super.onPlayerCommandRequest(session, controller, command)
        }

        override fun onMediaButtonEvent(
            session: MediaSession,
            controller: MediaSession.ControllerInfo,
            intent: Intent
        ): Boolean {
            val event = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
            return when (event?.keyCode) {
                KeyEvent.KEYCODE_MEDIA_NEXT -> {
                    if (event.action == KeyEvent.ACTION_DOWN) next()
                    true
                }
                KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                    if (event.action == KeyEvent.ACTION_DOWN) previous()
                    true
                }
                else -> super.onMediaButtonEvent(session, controller, intent)
            }
        }
    }

    private fun ensureTicker() {
        if (tickerJob?.isActive == true) return
        tickerJob = scope.launch {
            var lastPersistMs = 0L
            while (isActive) {
                val p = player
                if (p != null && p.isPlaying) {
                    _state.update {
                        it.copy(
                            positionMs = p.currentPosition.coerceAtLeast(0L),
                            durationMs = p.duration.takeIf { duration -> duration > 0L }
                                ?: it.durationMs
                        )
                    }
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastPersistMs >= 5_000L) {
                        lastPersistMs = now
                        persistQueue()
                    }
                    maybeFadeOut()
                }
                val end = sleepTimerEndsAt
                if (end != null) {
                    val remaining = (end - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
                    if (remaining == 0L) {
                        sleepTimerEndsAt = null
                        p?.pause()
                        _state.update {
                            it.copy(
                                isPlaying = false,
                                sleepTimerMinutes = null,
                                sleepTimerRemainingMs = null
                            )
                        }
                        persistQueue()
                    } else {
                        _state.update { it.copy(sleepTimerRemainingMs = remaining) }
                    }
                }
                delay(if (fastProgress) 200L else 500L)
            }
        }
    }
}
