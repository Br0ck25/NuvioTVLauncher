package com.nuvio.tv.core.launcher

import android.content.Context
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
        data object Done : State
        data class Failed(val message: String) : State
    }

    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gson = Gson()
    private val mutableState = MutableStateFlow<State>(State.Idle)
    private var server: Server? = null

    val state: StateFlow<State> = mutableState.asStateFlow()

    /** Address to open on the phone, or null if no network address or port is available. */
    @Volatile var phoneUrl: String? = null
        private set

    fun startPhoneServer() {
        if (server != null) return
        val ip = DeviceIpAddress.get(appContext) ?: return
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
    fun submit(pairingPort: Int, pairingCode: String, connectPort: Int?): Boolean {
        if (pairingPort !in 1..65535 || !CODE.matches(pairingCode)) return false
        synchronized(this) {
            if (mutableState.value is State.Working) return false
            mutableState.value = State.Working
        }
        scope.launch {
            val result = setup.setUp(pairingPort, pairingCode, connectPort)
            mutableState.value = result.fold(
                onSuccess = { State.Done },
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
                newFixedLengthResponse(Response.Status.OK, "text/html", PAGE)
            session.method == Method.GET && session.uri == "/api/status" -> json(Response.Status.OK, statusBody())
            session.method == Method.POST && session.uri == "/api/pair" -> handlePair(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        private fun handlePair(session: IHTTPSession): Response {
            runCatching { session.parseBody(HashMap()) }
            val port = session.parms["port"]?.trim()?.toIntOrNull()
            val code = session.parms["code"]?.trim().orEmpty()
            if (port == null || !CODE.matches(code)) {
                return json(Response.Status.BAD_REQUEST, mapOf("state" to "failed", "message" to "Enter the port and the 6-digit code."))
            }
            val connect = session.parms["connect"]?.trim()?.toIntOrNull()
            return if (submit(port, code, connect)) {
                json(Response.Status.OK, statusBody())
            } else {
                json(Response.Status.CONFLICT, mapOf("state" to "failed", "message" to "Setup is already running or the values are invalid."))
            }
        }

        private fun statusBody(): Map<String, String> = when (val s = mutableState.value) {
            State.Idle -> mapOf("state" to "waiting")
            State.Working -> mapOf("state" to "working")
            State.Done -> mapOf("state" to "done")
            is State.Failed -> mapOf("state" to "failed", "message" to s.message)
        }

        private fun json(status: Response.Status, body: Map<String, String>): Response =
            newFixedLengthResponse(status, "application/json", gson.toJson(body))
    }

    private companion object {
        const val START_PORT = 8091
        const val MAX_ATTEMPTS = 10
        val CODE = Regex("\\d{6}")

        const val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Nuvio Home setup</title>
<style>body{font-family:sans-serif;max-width:420px;margin:24px auto;padding:0 16px;background:#111;color:#eee}
input,button{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:6px 0;border-radius:8px;border:1px solid #555;background:#222;color:#eee}
button{background:#3b82f6;border:0;color:#fff}#msg{margin-top:12px;min-height:3em}</style></head><body>
<h2>Nuvio Home setup</h2>
<p>Keep the <b>Pair device with pairing code</b> popup open on your TV, then enter what it shows.</p>
<input id="port" inputmode="numeric" placeholder="Pairing port (e.g. 40617)">
<input id="code" inputmode="numeric" placeholder="6-digit pairing code" maxlength="6">
<button id="go">Pair and set up</button><div id="msg"></div>
<script>
var msg=document.getElementById('msg');
function show(s){msg.textContent=s}
function poll(){fetch('/api/status').then(function(r){return r.json()}).then(function(d){
 if(d.state==='working'){show('Working…');setTimeout(poll,1000)}
 else if(d.state==='done'){show('Done! Check your TV for the result.')}
 else if(d.state==='failed'){show('Failed: '+(d.message||'unknown error'))}
 else{show('')}}).catch(function(){show('Lost connection to the TV.')})}
document.getElementById('go').onclick=function(){
 var b='port='+encodeURIComponent(document.getElementById('port').value)+'&code='+encodeURIComponent(document.getElementById('code').value);
 show('Sending…');
 fetch('/api/pair',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b})
 .then(function(r){return r.json()}).then(function(d){if(d.state==='failed'){show('Failed: '+d.message)}else{poll()}})
 .catch(function(){show('Could not reach the TV.')})}
</script></body></html>"""
    }
}
