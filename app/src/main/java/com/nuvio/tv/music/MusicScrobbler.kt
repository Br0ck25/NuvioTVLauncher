package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.music.bitchord.data.model.Song
import com.nuvio.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.min

/**
 * The signed-in Last.fm account. The app key and shared secret that sign requests come from the
 * build (see [BuildConfig.LASTFM_API_KEY]), so the only thing stored here is the username and the
 * session key Last.fm returned at login.
 */
data class LastFmConfig(
    val username: String = "",
    val sessionKey: String = "",
    val enabled: Boolean = false
) {
    val configured: Boolean get() = sessionKey.isNotBlank()

    /** False when this build was compiled without Last.fm API credentials. */
    val available: Boolean
        get() = BuildConfig.LASTFM_API_KEY.isNotBlank() && BuildConfig.LASTFM_SECRET.isNotBlank()
}

/**
 * Last.fm scrobbling for the Music section.
 *
 * The user signs in with their Last.fm username and password once; [login] exchanges those for a
 * session key through `auth.getMobileSession` and stores only that key. A track is scrobbled once
 * it passes half its duration (or four minutes, whichever comes first) and is at least 30 seconds
 * long.
 */
object MusicScrobbler {
    private const val TAG = "MusicScrobbler"
    /** Pre-encryption file; read once by [MusicSecurePreferences] and then removed. */
    private const val LEGACY_PREFS = "nuvio_music_lastfm"
    private const val SECURE_PREFS = "nuvio_music_lastfm_secure"
    private const val PLAIN_PREFS = "nuvio_music_lastfm_plain"
    private const val KEY_USERNAME = "username"
    private const val KEY_SESSION = "session_key"
    private const val KEY_ENABLED = "enabled"
    private const val ENDPOINT = "https://ws.audioscrobbler.com/2.0/"

    private val _config = MutableStateFlow(LastFmConfig())
    val config: StateFlow<LastFmConfig> = _config.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private var prefs: SharedPreferences? = null
    private var started = false
    private var announcedId: String? = null
    private var scrobbledId: String? = null
    private var lastSongId: String? = null
    private var lastPositionMs = 0L

    @Synchronized
    fun init(context: Context) {
        val app = context.applicationContext
        val store = MusicSecurePreferences.open(app, SECURE_PREFS, PLAIN_PREFS, LEGACY_PREFS)
        prefs = store
        val username = store.getString(KEY_USERNAME, "").orEmpty()
        // Older builds stored a session key without an account name. That key was signed with a
        // different app credential and can no longer be used, so don't pretend it is signed in.
        val sessionKey = store.getString(KEY_SESSION, "").orEmpty().takeIf { username.isNotBlank() }.orEmpty()
        _config.value = LastFmConfig(
            username = username,
            sessionKey = sessionKey,
            enabled = store.getBoolean(KEY_ENABLED, false)
        )
        if (sessionKey.isBlank() && store.contains(KEY_SESSION)) {
            store.edit().remove(KEY_SESSION).remove(KEY_ENABLED).apply()
        }
        if (!started) {
            started = true
            observePlayback()
        }
    }

    /**
     * Signs in with Last.fm and enables scrobbling.
     *
     * Returns the account name on success, or a failure carrying the message Last.fm returned.
     */
    suspend fun login(context: Context, username: String, password: String): Result<String> =
        withContext(Dispatchers.IO) {
            val apiKey = BuildConfig.LASTFM_API_KEY.trim()
            val secret = BuildConfig.LASTFM_SECRET.trim()
            if (apiKey.isBlank() || secret.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Last.fm login isn't configured for this build.")
                )
            }
            val name = username.trim()
            if (name.isBlank() || password.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Enter your Last.fm username and password.")
                )
            }
            runCatching {
                val body = execute(
                    params = linkedMapOf(
                        "method" to "auth.getMobileSession",
                        "api_key" to apiKey,
                        "username" to name,
                        "password" to password
                    ),
                    secret = secret
                )
                val session = JSONObject(body).getJSONObject("session")
                val account = session.optString("name").takeIf { it.isNotBlank() } ?: name
                val key = session.getString("key")
                saveSession(context, account, key, enabled = true)
                account
            }
        }

    fun setEnabled(context: Context, enabled: Boolean) {
        if (enabled && !_config.value.configured) return
        init(context)
        val updated = _config.value.copy(enabled = enabled)
        _config.value = updated
        prefs?.edit()
            ?.putBoolean(KEY_ENABLED, enabled)
            ?.apply()
        announcedId = null
        scrobbledId = null
    }

    fun logout(context: Context) {
        init(context)
        _config.value = LastFmConfig()
        prefs?.edit()?.clear()?.apply()
        announcedId = null
        scrobbledId = null
    }

    private fun saveSession(context: Context, username: String, sessionKey: String, enabled: Boolean) {
        init(context)
        val updated = LastFmConfig(
            username = username,
            sessionKey = sessionKey,
            enabled = enabled
        )
        _config.value = updated
        prefs?.edit()
            ?.putString(KEY_USERNAME, updated.username)
            ?.putString(KEY_SESSION, updated.sessionKey)
            ?.putBoolean(KEY_ENABLED, updated.enabled)
            ?.apply()
        announcedId = null
        scrobbledId = null
    }

    private fun observePlayback() {
        scope.launch {
            MusicPlayer.state.collect { state ->
                val config = _config.value
                if (!config.enabled || !config.configured) return@collect
                val song = state.song ?: return@collect
                if (song.videoId != lastSongId) {
                    lastSongId = song.videoId
                    lastPositionMs = 0L
                    announcedId = null
                    scrobbledId = null
                }
                if (state.positionMs + 5_000L < lastPositionMs) {
                    // A manual seek to the top (or repeat-one) is a fresh listen.
                    announcedId = null
                    scrobbledId = null
                }
                lastPositionMs = state.positionMs
                if (state.isPlaying && announcedId != song.videoId) {
                    announcedId = song.videoId
                    scrobbledId = null
                    updateNowPlaying(config, song)
                }
                if (scrobbledId == song.videoId || !state.isPlaying) return@collect
                val duration = state.durationMs
                if (duration < 30_000L) return@collect
                val threshold = min(duration / 2L, 4 * 60_000L)
                if (state.positionMs >= threshold) {
                    scrobbledId = song.videoId
                    scrobble(config, song, duration)
                }
            }
        }
    }

    private fun updateNowPlaying(config: LastFmConfig, song: Song) {
        val params = linkedMapOf(
            "method" to "track.updateNowPlaying",
            "api_key" to BuildConfig.LASTFM_API_KEY,
            "sk" to config.sessionKey,
            "artist" to song.artist,
            "track" to song.title
        )
        song.albumName?.takeIf { it.isNotBlank() }?.let { params["album"] = it }
        song.trackNumber?.let { params["trackNumber"] = it.toString() }
        post(params)
    }

    private fun scrobble(config: LastFmConfig, song: Song, durationMs: Long) {
        val params = linkedMapOf(
            "method" to "track.scrobble",
            "api_key" to BuildConfig.LASTFM_API_KEY,
            "sk" to config.sessionKey,
            "artist" to song.artist,
            "track" to song.title,
            "timestamp" to (System.currentTimeMillis() / 1000L).toString(),
            "duration" to (durationMs / 1000L).coerceAtLeast(1L).toString()
        )
        song.albumName?.takeIf { it.isNotBlank() }?.let { params["album"] = it }
        song.trackNumber?.let { params["trackNumber"] = it.toString() }
        post(params)
    }

    private fun post(params: Map<String, String>) {
        val secret = BuildConfig.LASTFM_SECRET
        if (secret.isBlank()) return
        runCatching { execute(params, secret) }
            .onFailure { Log.w(TAG, "${params["method"]} failed: ${it.message}") }
    }

    /**
     * Sends one signed call and returns its body.
     *
     * Last.fm reports failures inside a 200 response as `{"error":..,"message":..}`, so those are
     * turned into exceptions to keep both login errors and scrobble logs readable.
     */
    private fun execute(params: Map<String, String>, secret: String): String {
        val signed = params.toMutableMap()
        signed["api_sig"] = signature(params, secret)
        val form = FormBody.Builder().apply {
            signed.forEach { (key, value) -> add(key, value) }
            add("format", "json")
        }.build()
        val request = Request.Builder()
            .url(ENDPOINT)
            .post(form)
            .header("User-Agent", "NuvioOne/1.0")
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            // Last.fm reports credential failures as HTTP 403 with an error object in the body;
            // read that first so the TV shows "Authentication Failed" rather than just "HTTP 403".
            if (body.contains("\"error\"")) {
                val error = runCatching { JSONObject(body) }.getOrNull()
                val message = error?.optString("message").orEmpty()
                    .ifBlank { "Last.fm rejected the request." }
                throw IOException(message)
            }
            if (!response.isSuccessful) {
                throw IOException("Last.fm returned HTTP ${response.code}.")
            }
            return body
        }
    }

    /**
     * Last.fm's signature is MD5 over the alphabetically sorted `key + value` pairs followed by
     * the shared secret. The `format` and `callback` keys are never part of it.
     */
    internal fun signature(params: Map<String, String>, secret: String): String {
        val raw = params.toSortedMap().entries.joinToString("") { (key, value) -> key + value } +
            secret
        return MessageDigest.getInstance("MD5")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
