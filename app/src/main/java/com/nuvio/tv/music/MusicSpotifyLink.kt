package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.withAppLocale
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import android.util.Base64

data class SpotifyLinkState(
    val configured: Boolean = false,
    val running: Boolean = false,
    val url: String? = null,
    val connected: Boolean = false,
    val displayName: String? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val syncProgress: String? = null,
    val syncResult: String? = null
)

/**
 * Links a Spotify account to the TV with Authorization Code + PKCE.
 *
 * The TV shows a QR code for a small static HTTPS page. That page sends the phone to Spotify,
 * then Spotify returns the one-time code to the page, which forwards it to the TV's LAN callback.
 * The TV exchanges the code itself, so no token ever reaches the phone page.
 */
object MusicSpotifyLink {
    private const val PREFS_ENCRYPTED = "music_spotify_secure"
    private const val PREFS_PLAIN = "music_spotify"
    private const val KEY_REFRESH = "refresh_token"
    private const val KEY_ACCESS = "access_token"
    private const val KEY_EXPIRES = "expires_at"
    private const val KEY_NAME = "display_name"
    private const val KEY_ACCOUNT = "account_id"
    private const val SESSION_TIMEOUT_MS = 5 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var prefs: SharedPreferences? = null
    private var appContext: Context? = null
    private var server: SpotifyCallbackServer? = null
    private var session: LinkSession? = null
    private var timeoutJob: Job? = null

    private val _state = MutableStateFlow(SpotifyLinkState(configured = isConfigured()))
    val state: StateFlow<SpotifyLinkState> = _state.asStateFlow()

    private data class LinkSession(
        val verifier: String,
        val state: String,
        val redirectUri: String
    )

    fun isConfigured(): Boolean =
        BuildConfig.SPOTIFY_CLIENT_ID.isNotBlank() && BuildConfig.SPOTIFY_LINK_BASE_URL.isNotBlank()

    private fun text(id: Int): String =
        appContext?.withAppLocale()?.getString(id).orEmpty()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = MusicSecurePreferences.open(
            context.applicationContext,
            PREFS_ENCRYPTED,
            PREFS_PLAIN
        )
        prefs = store
        appContext = context.applicationContext
        val refresh = store.getString(KEY_REFRESH, null).orEmpty()
        val name = store.getString(KEY_NAME, null).orEmpty()
        _state.update {
            it.copy(
                configured = isConfigured(),
                connected = refresh.isNotBlank(),
                displayName = name.takeIf { it.isNotBlank() }
            )
        }
    }

    @Synchronized
    fun start(context: Context) {
        init(context)
        stopServer()
        if (!isConfigured()) {
            _state.update {
                it.copy(
                    running = false,
                    url = null,
                    error = text(R.string.music_spotify_link_not_configured)
                )
            }
            return
        }
        val verifier = randomToken(64)
        val challenge = base64Url(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray()))
        val state = randomToken(32)
        val base = BuildConfig.SPOTIFY_LINK_BASE_URL.trimEnd('/')
        val redirectUri = "$base/callback.html"
        val candidate = SpotifyCallbackServer(::handleCallback)
        val ip = DeviceIpAddress.get(context.applicationContext)
        if (ip.isNullOrBlank()) {
            _state.update {
                it.copy(
                    running = false,
                    url = null,
                    error = text(R.string.music_no_network)
                )
            }
            return
        }
        runCatching {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            val localCallback = "http://$ip:${candidate.listeningPort}/spotify/callback"
            val url = "$base/?tv=${enc(localCallback)}&state=${enc(state)}" +
                "&challenge=${enc(challenge)}&client_id=${enc(BuildConfig.SPOTIFY_CLIENT_ID)}" +
                "&redirect=${enc(redirectUri)}"
            server = candidate
            session = LinkSession(verifier, state, redirectUri)
            _state.update {
                it.copy(
                    running = true,
                    url = url,
                    busy = false,
                    message = null,
                    error = null,
                    syncProgress = null,
                    syncResult = null
                )
            }
            timeoutJob?.cancel()
            timeoutJob = scope.launch {
                delay(SESSION_TIMEOUT_MS)
                if (session != null) {
                    stopServer()
                    _state.update {
                        it.copy(
                            running = false,
                            error = text(R.string.music_spotify_link_expired)
                        )
                    }
                }
            }
        }.onFailure { error ->
            runCatching { candidate.stop() }
            server = null
            session = null
            _state.update {
                it.copy(
                    running = false,
                    url = null,
                    error = text(R.string.music_spotify_start_failed)
                )
            }
        }
    }

    @Synchronized
    fun stop() {
        stopServer()
        _state.update { it.copy(running = false, url = null) }
    }

    @Synchronized
    fun disconnect(context: Context) {
        init(context)
        prefs?.edit()
            ?.remove(KEY_REFRESH)
            ?.remove(KEY_ACCESS)
            ?.remove(KEY_EXPIRES)
            ?.remove(KEY_NAME)
            ?.remove(KEY_ACCOUNT)
            ?.apply()
        _state.update {
            it.copy(
                connected = false,
                displayName = null,
                message = text(R.string.music_integrations_spotify_disconnected),
                error = null,
                syncProgress = null,
                syncResult = null
            )
        }
    }

    /** Sync the account's playlists and Liked Songs into the local Music library. */
    fun sync(context: Context, onProgress: (String) -> Unit = {}) {
        init(context)
        if (_state.value.busy) return
        _state.update {
            it.copy(busy = true, syncProgress = text(R.string.music_spotify_reading_library), syncResult = null, error = null)
        }
        scope.launch {
            val token = ensureAccessToken(context)
            if (token == null) {
                _state.update { it.copy(busy = false, syncProgress = null) }
                return@launch
            }
            MusicSpotifyImport.syncAccount(
                context = context.applicationContext,
                accessToken = token,
                onProgress = { text ->
                    onProgress(text)
                    _state.update { it.copy(syncProgress = text) }
                }
            ).fold(
                onSuccess = { result ->
                    _state.update {
                        it.copy(
                            busy = false,
                            syncProgress = null,
                            syncResult = text(R.string.music_spotify_imported_tracks)
                                .format(result.matched, result.tracks, result.playlists)
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            busy = false,
                            syncProgress = null,
                            error = error.message ?: text(R.string.music_spotify_library_sync_failed)
                        )
                    }
                }
            )
        }
    }

    internal suspend fun ensureAccessToken(context: Context): String? {
        init(context)
        val store = prefs ?: return null
        val access = store.getString(KEY_ACCESS, null).orEmpty()
        val expires = store.getLong(KEY_EXPIRES, 0L)
        if (access.isNotBlank() && expires - System.currentTimeMillis() > 60_000L) {
            return access
        }
        val refresh = store.getString(KEY_REFRESH, null).orEmpty()
        if (refresh.isBlank()) {
            _state.update {
                it.copy(connected = false, error = text(R.string.music_spotify_link_again))
            }
            return null
        }
        val tokens = runCatching {
            withTimeoutOrNull(30_000L) {
                MusicSpotifyApi.refresh(BuildConfig.SPOTIFY_CLIENT_ID, refresh)
            }
        }.getOrNull()
        if (tokens == null) {
            store.edit().remove(KEY_ACCESS).remove(KEY_EXPIRES).apply()
            _state.update {
                it.copy(
                    connected = false,
                    error = text(R.string.music_spotify_sign_in_expired)
                )
            }
            return null
        }
        saveTokens(store, tokens, keepRefresh = tokens.refreshToken ?: refresh)
        return tokens.accessToken
    }

    private fun handleCallback(code: String?, state: String?, error: String?): String {
        val current = session
        if (current == null) return text(R.string.music_spotify_link_inactive)
        if (error != null) {
            stopServer()
            _state.update {
                it.copy(
                    running = false,
                    url = null,
                    busy = false,
                    error = if (error == "access_denied") {
                        text(R.string.music_spotify_access_denied)
                    } else {
                        appContext?.let {
                            text(R.string.music_spotify_returned_error).format(error)
                        } ?: "Spotify returned an error: $error"
                    }
                )
            }
            return text(R.string.music_spotify_not_linked)
        }
        if (state != current.state) {
            stopServer()
            _state.update {
                it.copy(
                    running = false,
                    url = null,
                    busy = false,
                    error = text(R.string.music_spotify_answer_mismatch)
                )
            }
            return text(R.string.music_spotify_answer_mismatch)
        }
        if (code.isNullOrBlank()) {
            return text(R.string.music_spotify_no_code)
        }
        _state.update { it.copy(busy = true, message = text(R.string.music_spotify_finishing_link), error = null) }
        val tokens = runCatching {
            runBlocking {
                withTimeoutOrNull(30_000L) {
                    MusicSpotifyApi.exchangeCode(
                        BuildConfig.SPOTIFY_CLIENT_ID,
                        code,
                        current.verifier,
                        current.redirectUri
                    )
                }
            }
        }.getOrNull()
        if (tokens == null) {
            stopServer()
            _state.update {
                it.copy(
                    running = false,
                    url = null,
                    busy = false,
                    error = text(R.string.music_spotify_link_rejected)
                )
            }
            return text(R.string.music_spotify_link_rejected)
        }
        val store = prefs
        if (store == null) {
            stopServer()
            _state.update { it.copy(running = false, busy = false, error = text(R.string.music_spotify_tv_not_ready)) }
            return text(R.string.music_spotify_tv_not_ready)
        }
        saveTokens(store, tokens, keepRefresh = tokens.refreshToken)
        val profile = runCatching {
            runBlocking { withTimeoutOrNull(15_000L) { MusicSpotifyApi.profile(tokens.accessToken) } }
        }.getOrNull()
        if (profile != null) {
            store.edit()
                .putString(KEY_NAME, profile.displayName)
                .putString(KEY_ACCOUNT, profile.id)
                .apply()
        }
        stopServer()
        _state.update {
            it.copy(
                running = false,
                busy = false,
                connected = true,
                displayName = profile?.displayName ?: it.displayName,
                message = text(R.string.music_spotify_linked),
                error = null
            )
        }
        return text(R.string.music_spotify_linked_browser)
    }

    private fun saveTokens(store: SharedPreferences, tokens: SpotifyTokens, keepRefresh: String?) {
        val editor = store.edit()
            .putString(KEY_ACCESS, tokens.accessToken)
            .putLong(KEY_EXPIRES, tokens.expiresAtMs)
        if (!keepRefresh.isNullOrBlank()) editor.putString(KEY_REFRESH, keepRefresh)
        editor.apply()
    }

    private fun stopServer() {
        timeoutJob?.cancel()
        timeoutJob = null
        runCatching { server?.stop() }
        server = null
        session = null
    }

    private fun randomToken(bytes: Int): String {
        val data = ByteArray(bytes)
        SecureRandom().nextBytes(data)
        return base64Url(data)
    }

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}

private class SpotifyCallbackServer(
    private val onCallback: (code: String?, state: String?, error: String?) -> String
) : NanoHTTPD(0) {
    override fun serve(session: IHTTPSession): Response {
        if (session.uri != "/spotify/callback") return notFound()
        val params = session.parameters
        val code = params["code"]?.firstOrNull()
        val state = params["state"]?.firstOrNull()
        val error = params["error"]?.firstOrNull()
        val message = onCallback(code, state, error)
        return newFixedLengthResponse(
            Response.Status.OK,
            "text/html; charset=utf-8",
            "<!doctype html><html><head><meta charset=\"utf-8\">" +
                "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
                "<title>Spotify</title></head>" +
                "<body style=\"font:16px/1.5 system-ui,sans-serif;background:#12141a;color:#f3f4f6;" +
                "display:grid;min-height:100vh;place-items:center;margin:0\">" +
                "<main style=\"padding:24px;text-align:center;max-width:480px\">" +
                "<h1 style=\"font-size:22px\">${escapeHtml(message)}</h1>" +
                "<p style=\"color:#b8bdc9\">You can close this page.</p></main></body></html>"
        ).apply {
            addHeader("Cache-Control", "no-store")
            addHeader("X-Content-Type-Options", "nosniff")
        }
    }

    private fun escapeHtml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun notFound(): Response = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        "Not found."
    )
}
