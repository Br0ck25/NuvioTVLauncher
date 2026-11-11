package com.nuvio.tv.core.launcher

import android.content.ComponentName
import android.content.Context
import com.nuvio.tv.MainActivity
import com.nuvio.tv.R
import com.google.gson.Gson
import com.nuvio.tv.core.server.DeviceIpAddress
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Runs the on-device setup and lets it be driven from either the TV's own fields or a phone on the
 * same network. The phone path exists because the TV's "Pair device with pairing code" popup (and
 * its code) goes away when the user leaves Settings, so the code can't be typed into Nuvio on the TV.
 */
internal class PhonePairingSession(
    context: Context,
    private val setup: LocalAdbSetup
) {
    sealed interface State {
        data object Idle : State
        data object Working : State
        data class Done(val message: String? = null) : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private var server: Server? = null

    val state: StateFlow<State> = mutableState.asStateFlow()

    @Volatile private var pageHtml: String = ""

    /** Address to open on the phone, or null if no network address or port is available. */
    @Volatile var phoneUrl: String? = null
        private set

    fun startPhoneServer() {
        if (server != null) return
        val ip = DeviceIpAddress.get(appContext) ?: return
        val component = ComponentName(appContext, MainActivity::class.java).flattenToString()
        pageHtml = LauncherSetupPage.html(
            appName = appContext.getString(R.string.app_name),
            sections = LauncherSetupCommands.sections(
                component, ip,
                ComponentName(appContext, com.nuvio.tv.core.notification.LauncherNotificationListenerService::class.java).flattenToString()
            )
        )
        for (port in START_PORT until START_PORT + MAX_ATTEMPTS) {
            val candidate = Server(port)
            if (runCatching { candidate.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false) }.isSuccess) {
                server = candidate
                phoneUrl = "http://$ip:$port"
                return
            }
        }
    }

    /** Returns false when a run is already in progress or the input is invalid. */
    fun submit(pairingPort: Int?, pairingCode: String?, connectPort: Int?): Boolean {
        // Both blank means "already paired, just connect"; otherwise both must be valid.
        val pairing = pairingPort != null || !pairingCode.isNullOrBlank()
        if (pairing && (pairingPort == null || pairingPort !in 1..65535 || pairingCode == null || !CODE.matches(pairingCode))) {
            return false
        }
        synchronized(this) {
            if (mutableState.value is State.Working) return false
            mutableState.value = State.Working
        }
        scope.launch {
            val result = setup.setUp(pairingPort, pairingCode, connectPort)
            mutableState.value = result.fold(
                onSuccess = { State.Done() },
                onFailure = { State.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
        return true
    }

    /** Runs one button of the page on this TV. False when something is already running. */
    fun runAction(action: DeviceAction, connectPort: Int?): Boolean {
        synchronized(this) {
            if (mutableState.value is State.Working) return false
            mutableState.value = State.Working
        }
        scope.launch {
            val result = setup.run(action, connectPort)
            mutableState.value = result.fold(
                onSuccess = { State.Done(it) },
                onFailure = { State.Failed(it.message ?: it.javaClass.simpleName) }
            )
        }
        return true
    }

    fun stop() {
        runCatching { server?.stop() }
        server = null
        phoneUrl = null
        scope.cancel()
    }

    private inner class Server(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response = when {
            session.method == Method.GET && session.uri == "/" ->
                newFixedLengthResponse(Response.Status.OK, "text/html", pageHtml)
            session.method == Method.GET && session.uri == "/api/status" -> json(Response.Status.OK, statusBody())
            session.method == Method.POST && session.uri == "/api/pair" -> handlePair(session)
            session.method == Method.POST && session.uri == "/api/run" -> handleRun(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        private fun handlePair(session: IHTTPSession): Response {
            runCatching { session.parseBody(HashMap()) }
            val rawPort = session.parms["port"]?.trim().orEmpty()
            val rawCode = session.parms["code"]?.trim().orEmpty()
            val port = rawPort.toIntOrNull()
            val bothBlank = rawPort.isEmpty() && rawCode.isEmpty()
            if (!bothBlank && (port == null || !CODE.matches(rawCode))) {
                return json(Response.Status.BAD_REQUEST, mapOf("state" to "failed", "message" to "Enter the port and the 6-digit code."))
            }
            val connect = session.parms["connect"]?.trim()?.toIntOrNull()
            return if (submit(port, rawCode.ifEmpty { null }, connect)) {
                json(Response.Status.OK, statusBody())
            } else {
                json(Response.Status.CONFLICT, mapOf("state" to "failed", "message" to "Setup is already running or the values are invalid."))
            }
        }

        private fun handleRun(session: IHTTPSession): Response {
            runCatching { session.parseBody(HashMap()) }
            val action = DeviceAction.fromId(session.parms["action"]?.trim())
                ?: return json(Response.Status.BAD_REQUEST, mapOf("state" to "failed", "message" to "Unknown action."))
            val connect = session.parms["connect"]?.trim()?.toIntOrNull()
            return if (runAction(action, connect)) {
                json(Response.Status.OK, statusBody())
            } else {
                json(Response.Status.CONFLICT, mapOf("state" to "failed", "message" to "Something is already running. Wait a moment."))
            }
        }

        private fun statusBody(): Map<String, String> = when (val s = mutableState.value) {
            State.Idle -> mapOf("state" to "waiting")
            State.Working -> mapOf("state" to "working")
            is State.Done -> mapOf("state" to "done", "message" to (s.message ?: "Done. Check your TV for the result."))
            is State.Failed -> mapOf("state" to "failed", "message" to s.message)
        }

        private fun json(status: Response.Status, body: Map<String, String>): Response =
            newFixedLengthResponse(status, "application/json", gson.toJson(body))
    }

    private companion object {
        const val START_PORT = 8091
        const val MAX_ATTEMPTS = 10
        val CODE = Regex("\\d{6}")
    }
}
