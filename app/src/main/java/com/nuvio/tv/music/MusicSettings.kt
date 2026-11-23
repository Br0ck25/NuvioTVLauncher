package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Bitrate ceiling applied to the audio track selected from the stream. */
enum class MusicAudioQuality(
    val label: String,
    val detail: String,
    val maxKbps: Int
) {
    LOW("Low", "~96 kbps · saves data", 96),
    MEDIUM("Medium", "~192 kbps", 192),
    HIGH("High", "Best available", 320)
}

/** The equalizer curves the music player can apply. */
enum class MusicEqualizerPreset(val label: String) {
    OFF("Off"),
    FLAT("Flat"),
    BASS("Bass"),
    VOCAL("Vocal"),
    ROCK("Rock")
}

/**
 * The Music section's playback preferences. These are deliberately separate from Nuvio's
 * video-player settings: the music player has its own queue, speed and audio effects.
 */
object MusicSettings {
    private const val PREFS_NAME = "nuvio_music_settings"
    private const val KEY_QUALITY = "audio_quality"
    private const val KEY_AUTOPLAY = "autoplay"
    private const val KEY_SKIP_SILENCE = "skip_silence"
    private const val KEY_SPEED = "playback_speed"
    private const val KEY_FADE = "fade_seconds"
    private const val KEY_EQUALIZER = "equalizer_preset"
    private const val KEY_DOWNLOAD_QUALITY = "download_quality"
    private const val KEY_WIFI_ONLY_DOWNLOADS = "wifi_only_downloads"
    private const val KEY_PREFER_ADDON_SOURCES = "prefer_addon_sources"
    private const val KEY_KEEP_PLAYING_WHILE_BROWSING = "keep_playing_while_browsing"

    private var prefs: SharedPreferences? = null

    private val _audioQuality = MutableStateFlow(MusicAudioQuality.HIGH)
    val audioQuality: StateFlow<MusicAudioQuality> = _audioQuality.asStateFlow()

    private val _autoplay = MutableStateFlow(true)
    val autoplay: StateFlow<Boolean> = _autoplay.asStateFlow()

    private val _skipSilence = MutableStateFlow(false)
    val skipSilence: StateFlow<Boolean> = _skipSilence.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    /** 0 = gapless, otherwise the fade-in/fade-out length in seconds. */
    private val _fadeSeconds = MutableStateFlow(0)
    val fadeSeconds: StateFlow<Int> = _fadeSeconds.asStateFlow()

    private val _equalizerPreset = MutableStateFlow(MusicEqualizerPreset.OFF)
    val equalizerPreset: StateFlow<MusicEqualizerPreset> = _equalizerPreset.asStateFlow()

    private val _downloadQuality = MutableStateFlow(MusicAudioQuality.HIGH)
    val downloadQuality: StateFlow<MusicAudioQuality> = _downloadQuality.asStateFlow()

    private val _wifiOnlyDownloads = MutableStateFlow(true)
    val wifiOnlyDownloads: StateFlow<Boolean> = _wifiOnlyDownloads.asStateFlow()

    /** Look for a matching addon recording before playing a YouTube track. */
    private val _preferAddonSources = MutableStateFlow(false)
    val preferAddonSources: StateFlow<Boolean> = _preferAddonSources.asStateFlow()

    /**
     * Keep the audio playing after leaving the Music section. Off by default, so music
     * only follows the user when they ask for it. Full-screen video still stops it.
     */
    private val _keepPlayingWhileBrowsing = MutableStateFlow(false)
    val keepPlayingWhileBrowsing: StateFlow<Boolean> = _keepPlayingWhileBrowsing.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        _audioQuality.value = runCatching {
            MusicAudioQuality.valueOf(store.getString(KEY_QUALITY, null).orEmpty())
        }.getOrDefault(MusicAudioQuality.HIGH)
        _autoplay.value = store.getBoolean(KEY_AUTOPLAY, true)
        _skipSilence.value = store.getBoolean(KEY_SKIP_SILENCE, false)
        _playbackSpeed.value = store.getFloat(KEY_SPEED, 1.0f).coerceIn(0.5f, 2.0f)
        _fadeSeconds.value = store.getInt(KEY_FADE, 0).coerceIn(0, 4)
        _equalizerPreset.value = runCatching {
            MusicEqualizerPreset.valueOf(store.getString(KEY_EQUALIZER, null).orEmpty())
        }.getOrDefault(MusicEqualizerPreset.OFF)
        _downloadQuality.value = runCatching {
            MusicAudioQuality.valueOf(store.getString(KEY_DOWNLOAD_QUALITY, null).orEmpty())
        }.getOrDefault(MusicAudioQuality.HIGH)
        _wifiOnlyDownloads.value = store.getBoolean(KEY_WIFI_ONLY_DOWNLOADS, true)
        _preferAddonSources.value = store.getBoolean(KEY_PREFER_ADDON_SOURCES, false)
        _keepPlayingWhileBrowsing.value = store.getBoolean(KEY_KEEP_PLAYING_WHILE_BROWSING, false)
    }

    fun setAudioQuality(value: MusicAudioQuality) {
        _audioQuality.value = value
        edit { putString(KEY_QUALITY, value.name) }
    }

    fun setAutoplay(value: Boolean) {
        _autoplay.value = value
        edit { putBoolean(KEY_AUTOPLAY, value) }
    }

    fun setSkipSilence(value: Boolean) {
        _skipSilence.value = value
        edit { putBoolean(KEY_SKIP_SILENCE, value) }
    }

    fun setPlaybackSpeed(value: Float) {
        val safe = value.coerceIn(0.5f, 2.0f)
        _playbackSpeed.value = safe
        edit { putFloat(KEY_SPEED, safe) }
    }

    fun setFadeSeconds(value: Int) {
        val safe = value.coerceIn(0, 4)
        _fadeSeconds.value = safe
        edit { putInt(KEY_FADE, safe) }
    }

    fun setEqualizerPreset(value: MusicEqualizerPreset) {
        _equalizerPreset.value = value
        edit { putString(KEY_EQUALIZER, value.name) }
    }

    fun setDownloadQuality(value: MusicAudioQuality) {
        _downloadQuality.value = value
        edit { putString(KEY_DOWNLOAD_QUALITY, value.name) }
    }

    fun setWifiOnlyDownloads(value: Boolean) {
        _wifiOnlyDownloads.value = value
        edit { putBoolean(KEY_WIFI_ONLY_DOWNLOADS, value) }
    }

    fun setPreferAddonSources(value: Boolean) {
        _preferAddonSources.value = value
        edit { putBoolean(KEY_PREFER_ADDON_SOURCES, value) }
    }

    fun setKeepPlayingWhileBrowsing(value: Boolean) {
        _keepPlayingWhileBrowsing.value = value
        edit { putBoolean(KEY_KEEP_PLAYING_WHILE_BROWSING, value) }
    }

    private inline fun edit(block: SharedPreferences.Editor.() -> Unit) {
        val editor = prefs?.edit() ?: return
        editor.block()
        editor.apply()
    }
}
