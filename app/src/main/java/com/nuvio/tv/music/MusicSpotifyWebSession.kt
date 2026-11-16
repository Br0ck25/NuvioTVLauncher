package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class SpotifyWebSessionState(
    val connected: Boolean = false,
    val accountName: String? = null
)

/**
 * The desktop-style Spotify session: the `sp_dc` cookie captured from a real Spotify sign-in.
 * It is stored in the same Keystore-backed store as the other Music secrets and is never logged.
 */
object MusicSpotifyWebSession {
    private const val PREFS_ENCRYPTED = "music_spotify_web_secure"
    private const val PREFS_PLAIN = "music_spotify_web"
    private const val KEY_COOKIE = "sp_dc"
    private const val KEY_NAME = "account_name"

    private var prefs: SharedPreferences? = null
    private val _state = MutableStateFlow(SpotifyWebSessionState())
    val state: StateFlow<SpotifyWebSessionState> = _state.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = MusicSecurePreferences.open(
            context.applicationContext,
            PREFS_ENCRYPTED,
            PREFS_PLAIN
        )
        prefs = store
        val cookie = store.getString(KEY_COOKIE, null).orEmpty()
        val name = store.getString(KEY_NAME, null).orEmpty()
        _state.value = SpotifyWebSessionState(
            connected = cookie.isNotBlank(),
            accountName = name.takeIf { it.isNotBlank() }
        )
    }

    fun cookie(context: Context): String {
        init(context)
        return prefs?.getString(KEY_COOKIE, null).orEmpty()
    }

    fun save(context: Context, cookie: String, accountName: String?) {
        init(context)
        val cleanCookie = cookie.trim()
        if (cleanCookie.isBlank()) return
        val cleanName = accountName?.trim()?.takeIf { it.isNotBlank() }
        prefs?.edit()
            ?.putString(KEY_COOKIE, cleanCookie)
            ?.putString(KEY_NAME, cleanName.orEmpty())
            ?.apply()
        _state.value = SpotifyWebSessionState(connected = true, accountName = cleanName)
    }

    fun clear(context: Context) {
        init(context)
        prefs?.edit()
            ?.remove(KEY_COOKIE)
            ?.remove(KEY_NAME)
            ?.apply()
        _state.value = SpotifyWebSessionState()
    }
}
