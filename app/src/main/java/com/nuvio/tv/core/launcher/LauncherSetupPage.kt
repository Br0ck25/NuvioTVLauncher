package com.nuvio.tv.core.launcher

/**
 * The page the TV serves to a phone on the same Wi-Fi. Its buttons run the commands ON the TV
 * (through the TV's own Wireless debugging, see [LocalAdbSetup]), because a phone can't run ADB
 * itself. The commands are also listed at the bottom, with the TV's address filled in, for anyone who
 * would rather run them from a computer.
 */
internal object LauncherSetupPage {

    private data class Button(val action: DeviceAction, val label: String, val note: String, val confirm: String? = null)

    private val groups: List<Pair<String, List<Button>>> = listOf(
        "Home button" to listOf(
            Button(DeviceAction.MAKE_HOME, "Make this the Home app", "Android's Home role: the Home button opens this launcher."),
            Button(DeviceAction.ENABLE_OVERRIDE, "Turn on Home button override", "Turns on this app's accessibility setting, so Home still opens the launcher on TVs that won't switch Home apps. It stays on after a restart."),
            Button(DeviceAction.ENABLE_NOTIFICATIONS, "Turn on notifications", "Lets the launcher show your TV's notifications under the bell at the top right of Home."),
            Button(DeviceAction.CHECK_HOME, "Which launcher is Home now?", "Only reads; changes nothing.")
        ),
        "Go back" to listOf(
            Button(DeviceAction.RESTORE_ORIGINAL, "Switch back to the original launcher", "Points the Home button at the launcher the TV came with.")
        ),
        "The TV's own launcher" to listOf(
            Button(DeviceAction.DISABLE_STOCK, "Turn off the stock launcher", "Warning: optional, and only for TVs that keep going back to Google TV after the Home button override is on. This launcher's Home takeover works best with the TV's own launcher left on. With it off, Google TV can show a black screen or loop on a recovery screen when you press Home, and the remote's YouTube button stops working (Button Mapper can remap it). Turn it back on with the button below.",
                confirm = "Turn off the TV's own launcher? On some TVs this causes a black screen or a recovery loop. You can turn it back on from this page."),
            Button(DeviceAction.ENABLE_STOCK, "Turn the stock launcher on", "Turns the TV's own launcher back on. Harmless if it already is. Use this if Home shows a black screen.")
        )
    )

    fun html(appName: String, sections: List<SetupSection>): String {
        val buttons = buildString {
            groups.forEach { (title, items) ->
                append("<h3>").append(esc(title)).append("</h3>")
                items.forEach { b ->
                    append("<div class=\"act\"><button class=\"run\" data-action=\"").append(b.action.id).append("\"")
                    b.confirm?.let { append(" data-confirm=\"").append(esc(it)).append("\"") }
                    append(">").append(esc(b.label)).append("</button><span>").append(esc(b.note)).append("</span></div>")
                }
            }
        }
        val reference = buildString {
            sections.forEach { section ->
                append("<h3>").append(esc(section.title)).append("</h3><p>").append(esc(section.intro)).append("</p>")
                section.warning?.let { append("<p class=\"warn\">").append(esc(it)).append("</p>") }
                section.commands.forEach { c ->
                    append("<div class=\"cmd\"><b>").append(esc(c.title)).append("</b>")
                    if (c.note.isNotEmpty()) append("<span>").append(esc(c.note)).append("</span>")
                    append("<pre>").append(esc(c.command)).append("</pre>")
                    append("<button type=\"button\" class=\"copy\" onclick=\"copyCmd(this)\">Copy</button></div>")
                }
            }
        }
        return TEMPLATE
            .replace("%%TITLE%%", esc(appName))
            .replace("%%BUTTONS%%", buttons)
            .replace("%%REFERENCE%%", reference)
    }

    internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private const val TEMPLATE = """<!doctype html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>%%TITLE%% setup</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;max-width:480px;margin:20px auto;padding:0 16px 60px;background:#111;color:#eee}
h2{margin:0 0 6px}h3{font-size:16px;margin:22px 0 6px}p{color:#aaa;font-size:14.5px;line-height:1.4;margin:6px 0 12px}.warn{color:#f0b35a}
input,button{width:100%;box-sizing:border-box;font-size:18px;padding:12px;margin:6px 0;border-radius:8px;border:1px solid #555;background:#222;color:#eee}
button{background:#3b82f6;border:0;color:#fff;font-weight:600}button.run{background:#2563eb}button.copy{width:auto;font-size:14px;padding:8px 16px;background:#fff;color:#111}
button:disabled{opacity:.5}
.act{margin:8px 0}.act span{display:block;color:#999;font-size:13px;line-height:1.35}
#msg{position:sticky;top:0;background:#1b1b1f;border:1px solid #333;border-radius:8px;padding:10px 12px;margin:10px 0;min-height:1.4em;white-space:pre-wrap}
.cmd{background:#1b1b1f;border:1px solid #2a2a30;border-radius:10px;padding:10px;margin:8px 0}.cmd b{display:block;font-size:14px}.cmd span{display:block;color:#aaa;font-size:13px;margin:4px 0}
pre{background:#0b0b0d;border-radius:8px;padding:8px;margin:6px 0;overflow-x:auto;font-size:12.5px;white-space:pre-wrap;word-break:break-all}
details{margin-top:28px}summary{font-size:16px;color:#9db7ff;cursor:pointer}
</style></head><body>
<h2>%%TITLE%% setup</h2>
<div id="msg">Ready.</div>
<h3>1. Pair this TV (once)</h3>
<p>On the TV open Settings → System → Developer options → Wireless debugging → <b>Pair device with pairing code</b> and keep that popup open. Enter what it shows. Already paired before? Leave both boxes empty.</p>
<input id="port" inputmode="numeric" placeholder="Pairing port (e.g. 40617)">
<input id="code" inputmode="numeric" placeholder="6-digit pairing code" maxlength="6">
<button id="go">Pair and set up</button>
<h3 style="margin-top:28px">2. Run on the TV</h3>
<p>These buttons run the commands on the TV for you. The TV must be paired (step 1) and Wireless debugging must stay on.</p>
%%BUTTONS%%
<details><summary>Rather use a computer? The same commands</summary>%%REFERENCE%%</details>
<script>
var msg=document.getElementById('msg'),all=document.querySelectorAll('button.run,#go');
function show(t){msg.textContent=t}
function busy(b){for(var i=0;i<all.length;i++)all[i].disabled=b}
function poll(){fetch('/api/status').then(function(r){return r.json()}).then(function(d){
 if(d.state==='working'){show('Working…');setTimeout(poll,1000)}
 else if(d.state==='done'){busy(false);show(d.message||'Done.')}
 else if(d.state==='failed'){busy(false);show('Failed: '+(d.message||'unknown error'))}
 else{busy(false)}}).catch(function(){busy(false);show('Lost connection to the TV.')})}
function post(url,body){busy(true);show('Sending…');
 fetch(url,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:body})
 .then(function(r){return r.json()}).then(function(d){if(d.state==='failed'){busy(false);show('Failed: '+d.message)}else{poll()}})
 .catch(function(){busy(false);show('Could not reach the TV.')})}
document.getElementById('go').onclick=function(){
 post('/api/pair','port='+encodeURIComponent(document.getElementById('port').value)+'&code='+encodeURIComponent(document.getElementById('code').value))};
for(var i=0;i<all.length;i++){(function(b){var a=b.getAttribute('data-action');if(!a)return;b.onclick=function(){
 var c=b.getAttribute('data-confirm');if(c&&!confirm(c))return;post('/api/run','action='+encodeURIComponent(a))}})(all[i])}
function copyCmd(btn){var t=btn.parentNode.querySelector('pre').innerText;
var done=function(){btn.textContent='Copied';setTimeout(function(){btn.textContent='Copy'},1500)};
if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done,function(){fb(t,done)})}else{fb(t,done)}}
function fb(t,done){var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();try{document.execCommand('copy');done()}catch(e){}document.body.removeChild(a)}
</script></body></html>"""
}
