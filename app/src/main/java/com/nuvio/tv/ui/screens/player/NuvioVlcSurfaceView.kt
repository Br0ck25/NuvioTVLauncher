package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup
import android.widget.FrameLayout
import com.nuvio.tv.core.audio.EqualizerOwner
import com.nuvio.tv.core.audio.SevenBandEqualizerProfile
import com.nuvio.tv.core.audio.SevenBandEqualizerSettings
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout
import java.util.Locale

/**
 * A small libVLC surface for the app's own player controls. VLC does not provide the
 * Media3/ExoPlayer chrome, so this view only owns decoding and keeps the current state for the
 * controller to read.
 */
class NuvioVlcSurfaceView @JvmOverloads constructor(
    context: Context
) : FrameLayout(context) {

    data class State(
        val isPlaying: Boolean = false,
        val isBuffering: Boolean = true,
        val isEnded: Boolean = false,
        val durationMs: Long = 0L,
        val positionMs: Long = 0L,
        val isLive: Boolean = false,
        val error: String? = null
    )

    /** App settings that libVLC can actually apply. */
    data class MediaConfig(
        val networkCachingMs: Int = 1500,
        val liveCachingMs: Int = 1500,
        val hardwareDecoding: Boolean = true,
        val passthrough: Boolean = false,
        val audioChannels: Int? = null,
        /** The user has subtitles off; don't let VLC pick a file's default track. */
        val subtitlesDisabled: Boolean = false
    )

    private companion object {
        const val TAG = "NuvioVlc"

        /**
         * VLC reports zero length and "not seekable" while it is still opening any file.
         * Wait until playback has actually started before treating that as a live stream,
         * otherwise a movie is latched as live on the first reading and never gets a seek bar.
         */
        const val LIVE_SETTLE_MS = 1500L

        /**
         * libVLC loads every plugin in its constructor. On a TV box that takes seconds, so
         * keep one engine for the process instead of rebuilding it for each movie/episode.
         */
        @Volatile
        private var sharedLibVlc: LibVLC? = null

        @Synchronized
        private fun obtainLibVlc(context: Context): LibVLC? {
            sharedLibVlc?.let { return it }
            val args = arrayListOf(
                "--no-sub-autodetect-file",
                // Warnings and errors only; VLC's debug level floods the playback path.
                "--verbose=1"
            )
            val vlc = runCatching { LibVLC(context.applicationContext, args) }.getOrNull()
            sharedLibVlc = vlc
            return vlc
        }
    }

    private val videoLayout = VLCVideoLayout(context).also {
        it.isFocusable = false
        it.isFocusableInTouchMode = false
        it.descendantFocusability = FOCUS_BLOCK_DESCENDANTS
        addView(it, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    private var libVlc: LibVLC? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile
    private var equalizerProfile = SevenBandEqualizerSettings.current(EqualizerOwner.PLAYBACK)
    private var activeEqualizer: MediaPlayer.Equalizer? = null
    private var initialized = false
    private var viewsAttached = false
    private var aspectMode: AspectMode = AspectMode.ORIGINAL
    @Volatile
    private var currentMediaHasPlayed = false
    @Volatile
    private var renderedFrame = false
    @Volatile
    private var playbackStartedAtElapsedMs = 0L
    private var requestedUrl: String? = null
    private val liveRecheckRunnable = Runnable {
        val player = mediaPlayer ?: return@Runnable
        update { it.copy(isLive = computeIsLive(player)) }
    }
    private data class PendingMedia(
        val url: String,
        val headers: Map<String, String>,
        val startMs: Long,
        val audioUrl: String? = null,
        val config: MediaConfig = MediaConfig()
    )
    private var pendingMedia: PendingMedia? = null

    init {
        isFocusable = false
        isFocusableInTouchMode = false
        descendantFocusability = FOCUS_BLOCK_DESCENDANTS
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ensureInitialized()
        if (!viewsAttached) {
            runCatching {
                // The third flag enables libVLC's subtitle surface. Without it VLC logs
                // "can't get Subtitles Surface" and no captions are drawn on the TextureView.
                mediaPlayer?.attachViews(videoLayout, null, true, true)
                viewsAttached = true
            }
        }
        pendingMedia?.let { media ->
            pendingMedia = null
            setMediaInternal(media.url, media.headers, media.startMs, media.audioUrl, media.config)
        }
    }

    override fun onDetachedFromWindow() {
        if (viewsAttached) {
            runCatching { mediaPlayer?.detachViews() }
            viewsAttached = false
        }
        super.onDetachedFromWindow()
    }

    /**
     * libVLC's AudioTrack path attaches a DynamicsProcessing effect. A few Android TV
     * boxes throw from its constructor, after which VLC drops audio completely. Probe
     * the same call here and use OpenSL ES on those devices.
     */
    private var audioTrackUsable = true

    @Volatile
    private var state = State()

    /** Called from VLC's thread; the controller only reads fields from [State]. */
    var onStateChanged: ((State) -> Unit)? = null

    /** Audio/subtitle tracks appeared or changed; the controller refreshes its track lists. */
    var onTracksChanged: (() -> Unit)? = null

    fun ensureInitialized() {
        if (initialized) return
        initialized = true
        val vlc = obtainLibVlc(context)
        libVlc = vlc
        if (vlc == null) {
            Log.e(TAG, "LibVLC failed to initialize")
            update { it.copy(isBuffering = false, error = "VLC couldn't start") }
            return
        }
        Log.i(TAG, "LibVLC initialized")
        val player = MediaPlayer(vlc)
        player.volume = 100
        audioTrackUsable = android.os.Build.VERSION.SDK_INT < 28 || runCatching {
            // Mirrors libVLC's own call (priority 0) before it gives up on AudioTrack.
            android.media.audiofx.DynamicsProcessing(0).release()
        }.isSuccess
        val audioOutput = if (audioTrackUsable) "audiotrack" else "opensles"
        val audioOutputSet = player.setAudioOutput(audioOutput)
        Log.i(TAG, "audio output $audioOutput=$audioOutputSet")
        mediaPlayer = player
        applyEqualizerProfile(player, equalizerProfile)
        runCatching {
            // attachViews(layout, displayManager, subtitles, textureView). A TextureView renders
            // inside the Compose hierarchy; subtitles need libVLC's separate subtitle surface.
            player.attachViews(videoLayout, null, true, true)
            viewsAttached = true
        }.onFailure {
            Log.e(TAG, "VLC surface attach failed: ${it.message}", it)
            update { s -> s.copy(isBuffering = false, error = "VLC couldn't start the video surface") }
        }
        player.setEventListener { event ->
            when (event.type) {
                MediaPlayer.Event.Playing -> {
                    markPlaybackStarted()
                    Log.i(TAG, "playing audioTracks=${player.audioTracks?.size ?: 0} volume=${player.volume}")
                    update {
                        it.copy(
                            isPlaying = true,
                            isBuffering = false,
                            isEnded = false,
                            isLive = computeIsLive(player),
                            error = null
                        )
                    }
                    onTracksChanged?.invoke()
                }
                MediaPlayer.Event.Paused -> update { it.copy(isPlaying = false) }
                MediaPlayer.Event.Stopped -> update { it.copy(isPlaying = false) }
                MediaPlayer.Event.EndReached -> {
                    // Replacing the media can deliver an EndReached for the outgoing item. Treating
                    // that as a real finish makes YouTube autoplay jump to the next video on start.
                    if (currentMediaHasPlayed) {
                        update {
                            it.copy(isPlaying = false, isBuffering = false, isEnded = true)
                        }
                    } else {
                        Log.i(TAG, "ignoring EndReached before current media started")
                    }
                }
                MediaPlayer.Event.EncounteredError -> update {
                    it.copy(isPlaying = false, isBuffering = false, error = "VLC couldn't play this stream")
                }
                MediaPlayer.Event.Buffering -> update {
                    it.copy(isBuffering = event.buffering < 100f)
                }
                MediaPlayer.Event.TimeChanged -> {
                    if (event.timeChanged > 500L) currentMediaHasPlayed = true
                    update {
                        it.copy(positionMs = event.timeChanged.coerceAtLeast(0L))
                    }
                }
                MediaPlayer.Event.LengthChanged -> update {
                    val length = event.lengthChanged.coerceAtLeast(0L)
                    it.copy(durationMs = length, isLive = computeIsLive(player))
                }
                MediaPlayer.Event.SeekableChanged -> update {
                    it.copy(isLive = computeIsLive(player))
                }
                MediaPlayer.Event.Vout -> {
                    renderedFrame = true
                    markPlaybackStarted()
                    update {
                        it.copy(isBuffering = false, isLive = computeIsLive(player))
                    }
                    applyAspectMode()
                }
                MediaPlayer.Event.ESAdded, MediaPlayer.Event.ESDeleted, MediaPlayer.Event.ESSelected -> {
                    onTracksChanged?.invoke()
                }
            }
        }
    }

    fun setMedia(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long = 0L,
        audioUrl: String? = null,
        config: MediaConfig = MediaConfig()
    ) {
        if (!initialized || mediaPlayer == null) {
            pendingMedia = PendingMedia(url, headers, startPositionMs, audioUrl, config)
            if (isAttachedToWindow) ensureInitialized()
            if (!initialized || mediaPlayer == null) return
        }
        setMediaInternal(url, headers, startPositionMs, audioUrl, config)
    }

    private fun setMediaInternal(
        url: String,
        headers: Map<String, String>,
        startPositionMs: Long,
        audioUrl: String? = null,
        config: MediaConfig = MediaConfig()
    ) {
        val vlc = libVlc ?: return
        val player = mediaPlayer ?: return
        requestedUrl = url
        currentMediaHasPlayed = false
        renderedFrame = false
        playbackStartedAtElapsedMs = 0L
        removeCallbacks(liveRecheckRunnable)
        Log.i(TAG, "setMedia host=${runCatching { Uri.parse(url).host }.getOrNull()} headers=${headers.keys} start=$startPositionMs audio=${!audioUrl.isNullOrBlank()} hw=${config.hardwareDecoding} passthrough=${config.passthrough} channels=${config.audioChannels} cache=${config.networkCachingMs}/${config.liveCachingMs}")
        runCatching {
            val media = Media(vlc, Uri.parse(url)).apply {
                // libVLC only exposes hardware on/off; there is no equivalent of Media3's
                // extension renderer preference, so "prefer app" maps to software decoding.
                setHWDecoderEnabled(config.hardwareDecoding, false)
                addOption(":network-caching=${config.networkCachingMs.coerceIn(500, 30_000)}")
                addOption(":live-caching=${config.liveCachingMs.coerceIn(500, 30_000)}")
                // VLC honours a Matroska "default" subtitle flag; an explicit -1 keeps the
                // user's global "subtitles off" choice from the very first frame.
                if (config.subtitlesDisabled) addOption(":sub-track=-1")
                if (config.passthrough) {
                    // VLC Android's encoded-audio path. AudioTrack supports up to 7.1.
                    addOption(":spdif")
                    addOption(":audiotrack-audio-channels=8")
                } else {
                    config.audioChannels?.let {
                        addOption(":audiotrack-audio-channels=${it.coerceIn(1, 8)}")
                    }
                }
                headers["User-Agent"]?.takeIf { it.isNotBlank() }?.let { addOption(":http-user-agent=$it") }
                headers["Referer"]?.takeIf { it.isNotBlank() }?.let { addOption(":http-referrer=$it") }
                headers["Cookie"]?.takeIf { it.isNotBlank() }?.let { addOption(":http-cookie=$it") }
                headers.forEach { (key, value) ->
                    if (key !in setOf("User-Agent", "Referer", "Cookie") && value.isNotBlank()) {
                        addOption(":http-header=$key: $value")
                    }
                }
            }
            // OpenSL ES is PCM-only; AudioTrack (when it works) handles passthrough too.
            runCatching { player.setAudioOutput(if (audioTrackUsable) "audiotrack" else "opensles") }
            player.media = media
            media.release()
            player.play()
            if (!audioUrl.isNullOrBlank()) {
                val added = runCatching {
                    player.addSlave(org.videolan.libvlc.interfaces.IMedia.Slave.Type.Audio, Uri.parse(audioUrl), true)
                }.getOrDefault(false)
                Log.i(TAG, "audio slave added=$added")
            }
            if (startPositionMs > 0L) player.time = startPositionMs
            update {
                State(
                    isPlaying = false,
                    isBuffering = true,
                    isEnded = false,
                    durationMs = 0L,
                    positionMs = startPositionMs,
                    isLive = false,
                    error = null
                )
            }
        }.onFailure {
            Log.e(TAG, "VLC media load failed: ${it.message}", it)
            update { s -> s.copy(isBuffering = false, error = "VLC couldn't open this stream") }
        }
    }

    fun setPaused(paused: Boolean) {
        val player = mediaPlayer ?: return
        runCatching {
            if (paused) {
                if (player.isPlaying) player.pause()
            } else {
                player.play()
            }
        }
        update { it.copy(isPlaying = !paused) }
    }

    fun setSevenBandEqualizerProfile(profile: SevenBandEqualizerProfile) {
        equalizerProfile = profile
        mediaPlayer?.let { applyEqualizerProfile(it, profile) }
    }

    private fun applyEqualizerProfile(player: MediaPlayer, profile: SevenBandEqualizerProfile) {
        if (!profile.isEnabled) {
            runCatching { player.setEqualizer(null) }
            activeEqualizer = null
            return
        }
        runCatching {
            val equalizer = MediaPlayer.Equalizer.create() ?: return
            val frequencies = (0 until MediaPlayer.Equalizer.getBandCount()).map {
                MediaPlayer.Equalizer.getBandFrequency(it).toDouble()
            }
            val anchors = SevenBandEqualizerSettings.frequenciesHz.zip(profile.gainsDb)
            frequencies.forEachIndexed { bandIndex, frequency ->
                equalizer.setAmp(bandIndex, interpolatedGainDb(frequency, anchors).toFloat())
            }
            equalizer.setPreAmp(-(profile.gainsDb.maxOrNull() ?: 0).coerceAtLeast(0).toFloat())
            if (player.setEqualizer(equalizer)) activeEqualizer = equalizer
        }.onFailure {
            Log.w(TAG, "Failed to apply seven-band equalizer in VLC: ${it.message}", it)
        }
    }

    private fun interpolatedGainDb(frequency: Double, anchors: List<Pair<Int, Int>>): Double {
        if (anchors.isEmpty()) return 0.0
        if (frequency <= anchors.first().first) return anchors.first().second.toDouble()
        if (frequency >= anchors.last().first) return anchors.last().second.toDouble()
        val upper = anchors.indexOfFirst { frequency <= it.first }.coerceAtLeast(1)
        val (lowerHz, lowerGain) = anchors[upper - 1]
        val (upperHz, upperGain) = anchors[upper]
        val fraction = (kotlin.math.ln(frequency) - kotlin.math.ln(lowerHz.toDouble())) /
            (kotlin.math.ln(upperHz.toDouble()) - kotlin.math.ln(lowerHz.toDouble()))
        return lowerGain + (upperGain - lowerGain) * fraction
    }

    fun isPlayingNow(): Boolean = mediaPlayer?.isPlaying == true || state.isPlaying

    fun isBufferingNow(): Boolean = state.isBuffering

    fun hasRenderedFrame(): Boolean = renderedFrame

    fun isEndedNow(): Boolean = state.isEnded

    fun currentError(): String? = state.error

    fun currentPositionMs(): Long = mediaPlayer?.time?.coerceAtLeast(0L) ?: state.positionMs

    fun durationMs(): Long = mediaPlayer?.length?.coerceAtLeast(0L) ?: state.durationMs

    fun isLiveStreamNow(): Boolean {
        val player = mediaPlayer ?: return state.isLive
        return computeIsLive(player)
    }

    fun isSeekableNow(): Boolean =
        runCatching { mediaPlayer?.isSeekable == true }.getOrDefault(false)

    /**
     * VLC fires `LengthChanged(0)` and `SeekableChanged(false)` before it knows anything
     * about a VOD file. Only call it live once playback has started, the first frame is on
     * screen, the settle time has passed and VLC still reports no length and no seeking.
     */
    private fun computeIsLive(player: MediaPlayer): Boolean {
        val length = runCatching { player.length }.getOrDefault(0L)
        if (length > 0L) return false
        if (!renderedFrame) return false
        val startedAt = playbackStartedAtElapsedMs
        if (startedAt <= 0L) return false
        if (SystemClock.elapsedRealtime() - startedAt < LIVE_SETTLE_MS) return false
        return runCatching { !player.isSeekable }.getOrDefault(false)
    }

    private fun markPlaybackStarted() {
        if (playbackStartedAtElapsedMs <= 0L) {
            playbackStartedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        // Re-check once VLC has had time to publish the real duration/seekable flags.
        removeCallbacks(liveRecheckRunnable)
        postDelayed(liveRecheckRunnable, LIVE_SETTLE_MS + 100L)
    }

    fun seekToMs(positionMs: Long) {
        mediaPlayer?.time = positionMs.coerceAtLeast(0L)
        update { it.copy(positionMs = positionMs.coerceAtLeast(0L)) }
    }

    fun setPlaybackSpeed(speed: Float) {
        mediaPlayer?.rate = speed.coerceIn(0.25f, 4f)
    }

    fun setSubtitleDelayMs(delayMs: Int) {
        runCatching { mediaPlayer?.spuDelay = delayMs * 1_000L }
    }

    fun setAudioDelayMs(delayMs: Int) {
        runCatching { mediaPlayer?.audioDelay = delayMs * 1_000L }
    }

    fun audioTrackInfos(): List<TrackInfo> {
        val player = mediaPlayer ?: return emptyList()
        val selected = player.audioTrack
        return player.audioTracks.orEmpty()
            .filter { it.id >= 0 }
            .mapIndexed { index, track ->
                TrackInfo(
                    index = index,
                    name = track.name ?: "Audio ${index + 1}",
                    language = inferLanguage(track.name),
                    trackId = track.id.toString(),
                    isSelected = track.id == selected
                )
            }
    }

    fun subtitleTrackInfos(): List<TrackInfo> {
        val player = mediaPlayer ?: return emptyList()
        val selected = player.spuTrack
        return player.spuTracks.orEmpty().mapIndexed { index, track ->
            TrackInfo(
                index = index,
                name = track.name ?: if (track.id < 0) "Off" else "Subtitle ${index + 1}",
                language = inferLanguage(track.name),
                trackId = track.id.toString(),
                isSelected = track.id == selected
            )
        }
    }

    /**
     * libVLC gives track names but no reliable language codes. Most files name tracks
     * "English", "eng", "Track 2 - [Spanish]" or "Portuguese (Brazil)", so normalize that
     * text into the app's language codes for preferred-language matching.
     */
    private fun inferLanguage(name: String?): String? {
        val text = name.orEmpty()
            .replace(Regex("[\\[\\](){}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (text.isBlank()) return null

        val code = PlayerSubtitleUtils.normalizeLanguageCode(text).lowercase(Locale.ROOT)
        if (code.isBlank()) return null
        val known = AVAILABLE_SUBTITLE_LANGUAGES.any { language ->
            val knownCode = language.code.lowercase(Locale.ROOT)
            code == knownCode || code.startsWith("$knownCode-") || knownCode.startsWith("$code-")
        }
        return code.takeIf { known }
    }

    fun selectAudioTrackById(trackId: Int): Boolean =
        runCatching { mediaPlayer?.setAudioTrack(trackId) == true }.getOrDefault(false)

    fun selectSubtitleTrackById(trackId: Int): Boolean =
        runCatching { mediaPlayer?.setSpuTrack(trackId) == true }.getOrDefault(false)

    fun addSubtitleFile(path: String): Boolean = runCatching {
        val uri = if (path.startsWith("http", ignoreCase = true)) {
            Uri.parse(path)
        } else {
            Uri.fromFile(java.io.File(path))
        }
        mediaPlayer?.addSlave(
            org.videolan.libvlc.interfaces.IMedia.Slave.Type.Subtitle,
            uri,
            true
        ) == true
    }.getOrDefault(false)

    fun setAspectMode(mode: AspectMode) {
        aspectMode = mode
        applyAspectMode()
    }

    private fun applyAspectMode() {
        val scale = when (aspectMode) {
            AspectMode.ORIGINAL -> MediaPlayer.ScaleType.SURFACE_BEST_FIT
            AspectMode.FULL_SCREEN -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
            AspectMode.STRETCH,
            AspectMode.VERTICAL_STRETCH,
            AspectMode.HORIZONTAL_STRETCH -> MediaPlayer.ScaleType.SURFACE_FILL
            AspectMode.SLIGHT_ZOOM,
            AspectMode.CINEMA_ZOOM -> MediaPlayer.ScaleType.SURFACE_FIT_SCREEN
        }
        runCatching { mediaPlayer?.videoScale = scale }
    }

    fun releasePlayer() {
        // libVLC's surface and player calls belong on the thread that owns the views.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { releasePlayer() }
            return
        }
        if (!initialized && mediaPlayer == null) return
        Log.i(TAG, "releasePlayer")
        requestedUrl = null
        runCatching { mediaPlayer?.setEventListener(null) }
        runCatching { mediaPlayer?.stop() }
        if (viewsAttached) {
            runCatching { mediaPlayer?.detachViews() }
            viewsAttached = false
        }
        runCatching { mediaPlayer?.release() }
        mediaPlayer = null
        // The process-wide LibVLC stays alive: its plugin startup is the slow part and the
        // next movie would otherwise pay for it again.
        libVlc = null
        initialized = false
        viewsAttached = false
        currentMediaHasPlayed = false
        renderedFrame = false
        playbackStartedAtElapsedMs = 0L
        removeCallbacks(liveRecheckRunnable)
        pendingMedia = null
        state = State()
    }

    fun describe(): String =
        String.format(Locale.US, "vlc state playing=%s buffering=%s live=%s", state.isPlaying, state.isBuffering, state.isLive)

    private fun update(transform: (State) -> State) {
        val next = transform(state)
        state = next
        onStateChanged?.invoke(next)
    }
}
