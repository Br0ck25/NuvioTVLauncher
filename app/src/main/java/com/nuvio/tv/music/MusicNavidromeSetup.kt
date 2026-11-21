package com.nuvio.tv.music

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.nuvio.tv.R
import com.nuvio.tv.core.server.DeviceIpAddress
import com.nuvio.tv.core.util.withAppLocale
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.UUID

data class NavidromeSetupState(
    val running: Boolean = false,
    val url: String? = null,
    val connected: Boolean = false,
    val error: String? = null,
    val message: String? = null
)

/**
 * Short-lived LAN page used to enter Navidrome credentials on a phone instead of a TV remote.
 *
 * The token in the URL is the only credential the page carries; the form posts to this TV,
 * which performs the Subsonic ping and stores only the derived token + salt.
 */
object MusicNavidromeSetup {
    private val _state = MutableStateFlow(NavidromeSetupState())
    val state: StateFlow<NavidromeSetupState> = _state.asStateFlow()

    private var server: NavidromeSetupServer? = null
    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable {
        if (server != null) {
            stop(expired = true)
        }
    }

    @Synchronized
    fun start(context: Context): NavidromeSetupState {
        if (server != null) return _state.value
        val app = context.applicationContext
        appContext = app
        MusicNavidrome.init(app)
        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val candidate = NavidromeSetupServer(app, token) { signedInAs ->
            _state.value = _state.value.copy(
                connected = true,
                message = app.withAppLocale().getString(R.string.music_navidrome_connected_as, signedInAs)
            )
            handler.postDelayed({
                runCatching { server?.stop() }
                server = null
                _state.value = _state.value.copy(running = false)
            }, 1_200L)
        }
        return runCatching {
            candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
            server = candidate
            val ip = DeviceIpAddress.get(app)
            if (ip.isNullOrBlank()) {
                candidate.stop()
                server = null
                _state.value = NavidromeSetupState(
                    error = app.withAppLocale().getString(R.string.music_no_network)
                )
            } else {
                _state.value = NavidromeSetupState(
                    running = true,
                    url = "http://$ip:${candidate.listeningPort}/navidrome/$token/"
                )
                handler.removeCallbacks(timeout)
                handler.postDelayed(timeout, 5 * 60 * 1000L)
            }
            _state.value
        }.getOrElse {
            runCatching { candidate.stop() }
            server = null
            _state.value = NavidromeSetupState(
                error = it.message ?: app.withAppLocale().getString(R.string.music_phone_setup_failed)
            )
            _state.value
        }
    }

    @Synchronized
    fun stop(expired: Boolean = false) {
        handler.removeCallbacks(timeout)
        runCatching { server?.stop() }
        server = null
        _state.value = if (expired) {
            NavidromeSetupState(error = appContext?.withAppLocale()?.getString(R.string.music_link_expired))
        } else {
            NavidromeSetupState()
        }
    }

    internal fun onPageError(message: String) {
        _state.value = _state.value.copy(error = message)
    }
}

private class NavidromeSetupServer(
    private val context: Context,
    private val token: String,
    private val onConnected: (String) -> Unit
) : NanoHTTPD(0) {

    private val pageHtml: String by lazy {
        context.assets.open("navidrome-setup.html").bufferedReader().use { it.readText() }
    }

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/navidrome/$token"
        if (!session.uri.startsWith(prefix)) return forbidden()
        val path = session.uri.removePrefix(prefix).trim('/')
        return when {
            path.isEmpty() -> newFixedLengthResponse(
                Response.Status.OK,
                "text/html; charset=utf-8",
                pageHtml
            ).apply {
                addHeader("Cache-Control", "no-store")
                addHeader("X-Content-Type-Options", "nosniff")
            }
            path == "connect" && session.method == Method.POST -> connect(session)
            else -> newFixedLengthResponse(
                Response.Status.NOT_FOUND,
                "text/plain; charset=utf-8",
                "Not found."
            )
        }
    }

    private fun connect(session: IHTTPSession): Response {
        val body = runCatching {
            val files = HashMap<String, String>()
            session.parseBody(files)
            files["postData"].orEmpty()
        }.getOrDefault("")
        val payload = runCatching { JSONObject(body) }.getOrDefault(JSONObject())
        val url = payload.optString("url").trim()
        val username = payload.optString("username").trim()
        val password = payload.optString("password")
        val result = MusicNavidrome.connect(context, url, username, password)
        return result.fold(
            onSuccess = {
                onConnected(username.ifBlank { "your account" })
                json(JSONObject().put("ok", true).put("status", "connected"))
            },
            onFailure = { error ->
                MusicNavidromeSetup.onPageError(error.message ?: "Couldn't connect.")
                json(
                    JSONObject()
                        .put("ok", false)
                        .put("status", "error")
                        .put("message", error.message ?: "Couldn't connect.")
                )
            }
        )
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
}
