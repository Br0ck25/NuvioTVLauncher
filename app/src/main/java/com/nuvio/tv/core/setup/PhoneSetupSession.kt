package com.nuvio.tv.core.setup

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
 * its code) goes away when the user leaves Settings, so the code cannot be typed into Nuvio on the TV.
 *
 * The phone page offers the same two things the TV dialog does: hand the system Home preference to
 * Nuvio, or only apply the ADB grants.
 */
internal class PhoneSetupSession(
    context: Context,
    private val setup: LocalAdbSetup
) {
    sealed interface State {
        data object Idle : State
        data object Working : State
        data class Done(val outcome: SetupOutcome) : State
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
    fun submit(
        pairingPort: Int?,
        pairingCode: String?,
        connectPort: Int?,
        mode: SetupMode = SetupMode.HOME_APP,
        disableStockLauncher: Boolean = false
    ): Boolean {
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
            val result = setup.setUp(pairingPort, pairingCode, connectPort, mode, disableStockLauncher)
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
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", PAGE)
            session.method == Method.GET && session.uri == "/api/status" -> json(Response.Status.OK, statusBody())
            session.method == Method.POST && session.uri == "/api/setup" -> handleSetup(session)
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not found")
        }

        private fun handleSetup(session: IHTTPSession): Response {
            runCatching { session.parseBody(HashMap()) }
            val rawPort = session.parms["port"]?.trim().orEmpty()
            val rawCode = session.parms["code"]?.trim().orEmpty()
            val port = rawPort.toIntOrNull()
            val bothBlank = rawPort.isEmpty() && rawCode.isEmpty()
            if (!bothBlank && (port == null || !CODE.matches(rawCode))) {
                return json(Response.Status.BAD_REQUEST, mapOf("state" to "failed", "message" to "Enter the port and the 6-digit code."))
            }
            val mode = if (session.parms["mode"] == "grants") SetupMode.GRANTS_ONLY else SetupMode.HOME_APP
            val disableStock = session.parms["disableStock"] == "1"
            val connect = session.parms["connect"]?.trim()?.toIntOrNull()
            return if (submit(port, rawCode.ifEmpty { null }, connect, mode, disableStock)) {
                json(Response.Status.OK, statusBody())
            } else {
                json(Response.Status.CONFLICT, mapOf("state" to "failed", "message" to "Setup is already running or the values are invalid."))
            }
        }

        private fun statusBody(): Map<String, Any> = when (val s = mutableState.value) {
            State.Idle -> mapOf("state" to "waiting")
            State.Working -> mapOf("state" to "working")
            is State.Done -> mapOf(
                "state" to "done",
                "homeIsNuvio" to s.outcome.homeIsNuvio,
                "home" to (s.outcome.homeComponent ?: ""),
                "grants" to s.outcome.grantsApplied,
                "disabled" to s.outcome.disabledPackages,
                "recovery" to s.outcome.recoveryCommands
            )
            is State.Failed -> mapOf("state" to "failed", "message" to s.message)
        }

        private fun json(status: Response.Status, body: Map<String, Any>): Response =
            newFixedLengthResponse(status, "application/json", gson.toJson(body))
    }

    private companion object {
        const val START_PORT = 8091
        const val MAX_ATTEMPTS = 10
        val CODE = Regex("\\d{6}")

        const val PAGE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>Nuvio TV setup</title>
<style>body{font-family:sans-serif;max-width:440px;margin:24px auto;padding:0 16px;background:#111;color:#eee}
input,button,select{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:6px 0;border-radius:8px;border:1px solid #555;background:#222;color:#eee}
button{background:#3b82f6;border:0;color:#fff}fieldset{border:1px solid #444;border-radius:8px;margin:10px 0;padding:8px 12px}
legend{font-size:14px;color:#aaa}label{display:block;font-size:16px;margin:6px 0}#msg{margin-top:12px;min-height:3em;white-space:pre-wrap}
code{display:block;background:#000;padding:8px;border-radius:6px;font-size:13px;word-break:break-all;margin:4px 0}</style></head><body>
<h2>Nuvio TV setup</h2>
<p>Keep the <b>Pair device with pairing code</b> popup open on your TV, then enter what it shows. If you already paired this TV once, leave both boxes empty.</p>
<fieldset><legend>What should this do?</legend>
<label><input type="radio" name="mode" value="home" checked style="width:auto"> Make Nuvio the Home app</label>
<label><input type="radio" name="mode" value="grants" style="width:auto"> Only apply the permission grants</label>
</fieldset>
<input id="port" inputmode="numeric" placeholder="Pairing port (e.g. 40617)">
<input id="code" inputmode="numeric" placeholder="6-digit pairing code" maxlength="6">
<input id="connect" inputmode="numeric" placeholder="Connection port (optional)">
<label><input id="disableStock" type="checkbox" style="width:auto"> Also disable the stock launcher (only for a known stock launcher, and only after Home is Nuvio)</label>
<button id="go">Pair and set up</button><div id="msg"></div>
<script>
var msg=document.getElementById('msg');
function show(s){msg.textContent=s}
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')}
function list(items){return (items||[]).map(function(c){return '<code>'+esc(c)+'</code>'}).join('')}
function done(d){
 var out=d.grants?'Permissions granted.\n':'';
 out+= d.homeIsNuvio ? 'Nuvio is now the Home app ('+(d.home||'')+')\n' : 'Home app: '+(d.home||'unchanged')+'\n';
 if(d.disabled&&d.disabled.length){out+='Disabled: '+d.disabled.join(', ')+'\n'}
 var html='<pre style="margin:0;white-space:pre-wrap;font-family:inherit">'+esc(out)+'</pre>';
 if(d.recovery&&d.recovery.length){html+='To put the stock launcher back from a computer:'+list(d.recovery)}
 msg.innerHTML=html;
}
function poll(){fetch('/api/status').then(function(r){return r.json()}).then(function(d){
 if(d.state==='working'){show('Working…');setTimeout(poll,1000)}
 else if(d.state==='done'){done(d)}
 else if(d.state==='failed'){show('Failed: '+(d.message||'unknown error'))}
 else{show('')}}).catch(function(){show('Lost connection to the TV.')})}
document.getElementById('go').onclick=function(){
 var mode=document.querySelector('input[name=mode]:checked').value;
 var b='port='+encodeURIComponent(document.getElementById('port').value)+
  '&code='+encodeURIComponent(document.getElementById('code').value)+
  '&connect='+encodeURIComponent(document.getElementById('connect').value)+
  '&mode='+encodeURIComponent(mode)+
  '&disableStock='+(document.getElementById('disableStock').checked?'1':'0');
 show('Sending…');
 fetch('/api/setup',{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:b})
 .then(function(r){return r.json()}).then(function(d){if(d.state==='failed'){show('Failed: '+d.message)}else{poll()}})
 .catch(function(){show('Could not reach the TV.')})}
</script></body></html>"""
    }
}
