package com.nuvio.tv.music

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Per-profile Gemini suggestion preferences. The API key itself lives in [GeminiKeyStore]. */
data class GeminiSettings(
    val enabled: Boolean = true,
    val model: String = DEFAULT_MODEL,
    val shareTopArtists: Boolean = true,
    val shareLiked: Boolean = true,
    val shareRecents: Boolean = true,
    val sharePlaylists: Boolean = true,
    val lastGeneratedAtMs: Long = 0L,
    /** A short profile fingerprint so stale suggestions can be refreshed in the background. */
    val generatedForFingerprint: String = "",
) {
    companion object {
        const val DEFAULT_MODEL = "gemini-2.5-flash"
    }
}

/**
 * Gemini preferences, keyed by profile. The key is deliberately separate: see [GeminiKeyStore].
 */
object MusicGeminiSettings {
    private const val FILE = "nuvio_music_gemini_settings"
    private const val ENABLED = "enabled"
    private const val MODEL = "model"
    private const val SHARE_TOP_ARTISTS = "share_top_artists"
    private const val SHARE_LIKED = "share_liked"
    private const val SHARE_RECENTS = "share_recents"
    private const val SHARE_PLAYLISTS = "share_playlists"
    private const val LAST_GENERATED_AT = "last_generated_at"
    private const val FINGERPRINT = "fingerprint"

    private var prefs: android.content.SharedPreferences? = null
    private val _state = MutableStateFlow(GeminiSettings())
    val state: StateFlow<GeminiSettings> = _state.asStateFlow()
    private var currentProfileId: Int = -1

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    }

    @Synchronized
    fun load(profileId: Int): GeminiSettings {
        prefs ?: return _state.value
        val store = prefs ?: return _state.value
        val keyed = { name: String -> "$profileId.$name" }
        val loaded = GeminiSettings(
            enabled = store.getBoolean(keyed(ENABLED), true),
            model = store.getString(keyed(MODEL), GeminiSettings.DEFAULT_MODEL)
                ?.takeIf { it.isNotBlank() } ?: GeminiSettings.DEFAULT_MODEL,
            shareTopArtists = store.getBoolean(keyed(SHARE_TOP_ARTISTS), true),
            shareLiked = store.getBoolean(keyed(SHARE_LIKED), true),
            shareRecents = store.getBoolean(keyed(SHARE_RECENTS), true),
            sharePlaylists = store.getBoolean(keyed(SHARE_PLAYLISTS), true),
            lastGeneratedAtMs = store.getLong(keyed(LAST_GENERATED_AT), 0L),
            generatedForFingerprint = store.getString(keyed(FINGERPRINT), "").orEmpty(),
        )
        currentProfileId = profileId
        _state.value = loaded
        return loaded
    }

    fun update(profileId: Int, change: (GeminiSettings) -> GeminiSettings): GeminiSettings {
        val next = change(load(profileId))
        val store = prefs ?: return next
        val keyed = { name: String -> "$profileId.$name" }
        store.edit()
            .putBoolean(keyed(ENABLED), next.enabled)
            .putString(keyed(MODEL), next.model.takeIf { it.isNotBlank() } ?: GeminiSettings.DEFAULT_MODEL)
            .putBoolean(keyed(SHARE_TOP_ARTISTS), next.shareTopArtists)
            .putBoolean(keyed(SHARE_LIKED), next.shareLiked)
            .putBoolean(keyed(SHARE_RECENTS), next.shareRecents)
            .putBoolean(keyed(SHARE_PLAYLISTS), next.sharePlaylists)
            .putLong(keyed(LAST_GENERATED_AT), next.lastGeneratedAtMs)
            .putString(keyed(FINGERPRINT), next.generatedForFingerprint)
            .apply()
        _state.value = next
        return next
    }

    fun markGenerated(profileId: Int, fingerprint: String) {
        update(profileId) {
            it.copy(
                lastGeneratedAtMs = System.currentTimeMillis(),
                generatedForFingerprint = fingerprint,
            )
        }
    }

    fun current(): GeminiSettings = _state.value
    fun currentProfile(): Int = currentProfileId
}
