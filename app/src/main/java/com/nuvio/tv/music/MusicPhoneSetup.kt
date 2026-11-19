package com.nuvio.tv.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.withAppLocale
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import java.util.UUID

enum class MusicPhoneTarget(val label: String, val path: String) {
    WEBDAV("WebDAV", "webdav"),
    LASTFM("Last.fm", "lastfm"),
    SPOTIFY("Spotify", "spotify")
}

data class MusicPhoneSetupState(
    val target: MusicPhoneTarget? = null,
    val running: Boolean = false,
    val url: String? = null,
    val connected: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val progress: String? = null,
    val result: String? = null
)

/**
 * The shared "link it on your phone" page used by WebDAV, Last.fm and Spotify.
 *
 * Navidrome keeps its own specialised page because it also has a token-auth client and a
 * different confirmation flow. This manager handles the simpler integrations with one form page.
 */
object MusicPhoneSetup {
    private val _state = MutableStateFlow(MusicPhoneSetupState())
    val state: StateFlow<MusicPhoneSetupState> = _state.asStateFlow()

    private var server: PhoneSetupServer? = null
    private var appContext: Context? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (server != null) {
            closeServer()
            _state.update {
                it.copy(running = false, error = appContext?.withAppLocale()?.getString(R.string.music_link_expired))
            }
        }
    }

    @Synchronized
    fun start(context: Context, target: MusicPhoneTarget): MusicPhoneSetupState {
        stop()
        val app = context.applicationContext
        appContext = app
        MusicWebDav.init(app)
        MusicScrobbler.init(app)
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val candidate = PhoneSetupServer(app, token, target)
        runCatching {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = candidate
            val ip = DeviceIpAddress.get(app)
            if (ip.isNullOrBlank()) {
                candidate.stop()
                server = null
                _state.value = MusicPhoneSetupState(
                    target = target,
                    error = app.withAppLocale().getString(R.string.music_no_network)
                )
            } else {
                _state.value = MusicPhoneSetupState(
                    target = target,
                    running = true,
                    url = "http://$ip:${candidate.listeningPort}/phone/$token/?target=${target.path}"
                )
                handler.postDelayed(timeout, 5 * 60 * 1000L)
            }
        }.onFailure {
            runCatching { candidate.stop() }
            server = null
            _state.value = MusicPhoneSetupState(
                target = target,
                error = it.message ?: app.withAppLocale().getString(R.string.music_phone_setup_failed)
            )
        }
        return _state.value
    }

    @Synchronized
    fun stop() {
        handler.removeCallbacks(timeout)
        runCatching { server?.stop() }
        server = null
        _state.value = MusicPhoneSetupState()
    }

    private fun closeServer() {
        handler.removeCallbacks(timeout)
        runCatching { server?.stop() }
        server = null
        _state.update { it.copy(running = false) }
    }

    internal fun handle(
        target: MusicPhoneTarget,
        fields: JSONObject
    ): Result<String> {
        val app = appContext ?: return Result.failure(IllegalStateException("The TV isn't ready."))
        return when (target) {
            MusicPhoneTarget.WEBDAV -> {
                val result = runBlocking {
                    MusicWebDav.connect(
                        app,
                        fields.optString("url"),
                        fields.optString("username"),
                        fields.optString("password")
                    )
                }
                result.map {
                    val url = MusicWebDav.config.value.url
                    _state.update {
                        it.copy(connected = true, message = app.withAppLocale().getString(R.string.music_phone_connected_to, url))
                    }
                    handler.postDelayed({ closeServer() }, 1_500L)
                    app.withAppLocale().getString(R.string.music_phone_connected_to, url)
                }
            }
            MusicPhoneTarget.LASTFM -> {
                runBlocking {
                    MusicScrobbler.login(
                        app,
                        fields.optString("lastfmUsername"),
                        fields.optString("lastfmPassword")
                    )
                }.map { account ->
                    _state.update {
                        it.copy(connected = true, message = app.withAppLocale().getString(R.string.music_phone_lastfm_signed_in, account))
                    }
                    handler.postDelayed({ closeServer() }, 1_500L)
                    app.withAppLocale().getString(R.string.music_phone_lastfm_signed_in, account)
                }
            }
            MusicPhoneTarget.SPOTIFY -> {
                val playlist = fields.optString("playlist").trim()
                if (playlist.isBlank()) {
                    Result.failure(IllegalArgumentException("Paste a Spotify playlist link."))
                } else {
                    _state.update {
                        it.copy(progress = "Reading playlist…", error = null, result = null)
                    }
                    scope.launch {
                        MusicSpotifyImport.import(
                            app,
                            playlist,
                            onProgress = { done, total ->
                                _state.update {
                                    it.copy(progress = "Matched $done of $total tracks…")
                                }
                            }
                        ).fold(
                            onSuccess = { result ->
                                _state.update {
                                    it.copy(
                                        connected = true,
                                        progress = null,
                                        result = "Imported ${result.imported} of " +
                                            "${result.total} tracks into “${result.playlistName}”."
                                    )
                                }
                                handler.postDelayed({ closeServer() }, 15_000L)
                            },
                            onFailure = { error ->
                                _state.update {
                                    it.copy(
                                        progress = null,
                                        error = error.message ?: "The playlist couldn't be imported."
                                    )
                                }
                            }
                        )
                    }
                    Result.success("Import started")
                }
            }
        }
    }
}

private class PhoneSetupServer(
    private val context: Context,
    private val token: String,
    private val target: MusicPhoneTarget
) : NanoHTTPD(0) {
    private val pageHtml: String by lazy {
        context.assets.open("phone-setup.html").bufferedReader().use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/phone/$token"
        if (!session.uri.startsWith(prefix)) return forbidden()
        val path = session.uri.removePrefix(prefix).trim('/')
        return when {
            path.isEmpty() -> page()
            path == "connect" && session.method == Method.POST -> connect(session)
            path == "api/state" -> json(stateJson())
            else -> notFound()
        }
    }

    private fun page(): Response = newFixedLengthResponse(
        Response.Status.OK,
        "text/html; charset=utf-8",
        pageHtml
    ).apply {
        addHeader("Cache-Control", "no-store")
        addHeader("X-Content-Type-Options", "nosniff")
    }

    private fun connect(session: IHTTPSession): Response {
        val body = runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }.getOrDefault("")
        val payload = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        return MusicPhoneSetup.handle(target, payload).fold(
            onSuccess = { message ->
                json(
                    JSONObject()
                        .put("ok", true)
                        .put("status", if (target == MusicPhoneTarget.SPOTIFY) "started" else "connected")
                        .put("message", message)
                )
            },
            onFailure = { error ->
                json(
                    JSONObject()
                        .put("ok", false)
                        .put("status", "error")
                        .put("message", error.message ?: "Couldn't save those details.")
                )
            }
        )
    }

    private fun stateJson(): JSONObject {
        val state = MusicPhoneSetup.state.value
        return JSONObject().apply {
            put("running", state.running)
            put("connected", state.connected)
            put("message", state.message ?: "")
            put("error", state.error ?: "")
            put("progress", state.progress ?: "")
            put("result", state.result ?: "")
        }
    }

    private fun json(body: JSONObject): Response =
        newFixedLengthResponse(
            Response.Status.OK,
            "application/json; charset=utf-8",
            body.toString()
        ).apply { addHeader("Cache-Control", "no-store") }

    private fun forbidden(): Response = newFixedLengthResponse(
        Response.Status.FORBIDDEN,
        "text/plain; charset=utf-8",
        "This link is not valid."
    )

    private fun notFound(): Response = newFixedLengthResponse(
        Response.Status.NOT_FOUND,
        "text/plain; charset=utf-8",
        "Not found."
    )
}
