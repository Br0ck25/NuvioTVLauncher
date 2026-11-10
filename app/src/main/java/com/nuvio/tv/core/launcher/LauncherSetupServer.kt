package com.nuvio.tv.core.launcher

import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

/** One command on the setup page, with what it does. */
data class SetupCommand(val title: String, val note: String, val command: String)

/** A group of commands on the setup page. */
data class SetupSection(val title: String, val intro: String, val commands: List<SetupCommand>, val warning: String? = null)

/**
 * The commands people run over ADB to make a launcher the Home app, taken from FLauncher's and
 * ArcLauncher's setup guides, filled in with this app's own package and activity so they can be
 * copied as they are. Nothing here runs by itself: the page only shows them.
 */
object LauncherSetupCommands {
    private const val HOME_CATEGORY = "-c android.intent.category.HOME -a android.intent.action.MAIN"

    fun sections(component: String, tvIp: String?): List<SetupSection> = listOf(
        SetupSection(
            title = "1. Connect ADB to this TV",
            intro = "Do this once from a computer, or from a phone app that runs ADB. Turn on Developer options (Settings → System → About → tap Build several times), then:",
            commands = listOf(
                SetupCommand("Wireless debugging (Android 11 and newer)", "Developer options → Wireless debugging → Pair device with pairing code. Use the pairing address and code it shows, then connect with the address on the main Wireless debugging screen (the port is different).",
                    "adb pair ${tvIp ?: "TV_IP"}:PAIRING_PORT\nadb connect ${tvIp ?: "TV_IP"}:CONNECTION_PORT"),
                SetupCommand("USB / network debugging (older TVs, many Fire TVs)", "Turn on ADB debugging and Network debugging, then accept the prompt on the TV.",
                    "adb connect ${tvIp ?: "TV_IP"}:5555")
            )
        ),
        SetupSection(
            title = "2. Make this app the Home app (recommended)",
            intro = "One command. The stock launcher is left untouched and nothing needs re-enabling, so it is easy to undo.",
            commands = listOf(
                SetupCommand("Set this app as the Home app", "The Home button on the remote now goes to this app.",
                    "adb shell cmd package set-home-activity $component"),
                SetupCommand("Check which launcher is Home right now", "", "adb shell cmd package resolve-activity $HOME_CATEGORY"),
                SetupCommand("List the launchers installed on the TV", "Copy the original launcher's package/activity from this list to go back.",
                    "adb shell cmd package query-activities $HOME_CATEGORY"),
                SetupCommand("Go back to the original launcher", "Replace the placeholder with the entry you copied from the list above.",
                    "adb shell cmd package set-home-activity <package>/<activity>")
            )
        ),
        SetupSection(
            title = "3. Optional: turn off the stock launcher (Chromecast with Google TV)",
            intro = "Only if step 2 is not enough, because the TV keeps going back to its own launcher. This is what FLauncher's guide does. It is reversible with the re-enable commands below.",
            warning = "On Chromecast with Google TV (and maybe other models) the remote's YouTube button stops working while the stock launcher is disabled. A button-remapping app such as Button Mapper (flar2.homebutton) can map it again. Re-enable the launcher if anything misbehaves.",
            commands = listOf(
                SetupCommand("Disable the stock launcher", "com.google.android.apps.tv.launcherx is the default launcher on Chromecast with Google TV.",
                    "adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx"),
                SetupCommand("Disable its fallback", "com.google.android.tungsten.setupwraith is used as a fallback and re-enables the stock launcher on its own, so disable it as well.",
                    "adb shell pm disable-user --user 0 com.google.android.tungsten.setupwraith"),
                SetupCommand("Re-enable the stock launcher", "Run both to undo step 3.",
                    "adb shell pm enable com.google.android.apps.tv.launcherx\nadb shell pm enable com.google.android.tungsten.setupwraith")
            )
        )
    )
}

/**
 * A small page on the home network, opened from a QR code, that shows the setup commands on a
 * phone with a copy button on each. Like the other phone pages in this app, it runs only while the
 * screen that asked for it is open, nothing leaves the home network, and the address has a
 * random code in it.
 */
object LauncherSetupServer {

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /** Null when there is no home network. */
    fun start(appName: String, component: String): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        val html = page(appName, LauncherSetupCommands.sections(component, ip))
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(client, token, html) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "launcher-setup"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8091..8099) {
            val s = runCatching { ServerSocket(port) }.getOrNull()
            if (s != null) return s
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = java.security.SecureRandom()
        return (1..6).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun serve(client: Socket, token: String, html: String) {
        runCatching {
            client.use { c ->
                c.soTimeout = 5_000
                val reader = c.getInputStream().bufferedReader()
                val request = reader.readLine().orEmpty()
                while (true) { val l = reader.readLine() ?: break; if (l.isEmpty()) break }
                val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?').trim('/')
                val out = c.getOutputStream()
                if (path != token) {
                    val body = "Not found".toByteArray()
                    out.write("HTTP/1.1 404 Not Found\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    out.write(body)
                } else {
                    val body = html.toByteArray(Charsets.UTF_8)
                    out.write(
                        ("HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                }
                out.flush()
            }
        }
    }

    internal fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    internal fun page(appName: String, sections: List<SetupSection>): String {
        val body = buildString {
            sections.forEach { section ->
                append("<h2>").append(esc(section.title)).append("</h2><p>").append(esc(section.intro)).append("</p>")
                section.warning?.let { append("<p class=\"warn\">").append(esc(it)).append("</p>") }
                section.commands.forEach { c ->
                    append("<div class=\"cmd\"><b>").append(esc(c.title)).append("</b>")
                    if (c.note.isNotEmpty()) append("<span>").append(esc(c.note)).append("</span>")
                    append("<pre>").append(esc(c.command)).append("</pre>")
                    append("<button type=\"button\" onclick=\"copyCmd(this)\">Copy</button></div>")
                }
            }
        }
        return """<!doctype html>
<html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(appName)} setup</title>
<style>
body{font-family:-apple-system,Roboto,sans-serif;background:#111;color:#eee;margin:0;padding:18px;max-width:720px}
h1{font-size:22px;margin:0 0 6px}h2{font-size:18px;margin:26px 0 4px}
p{color:#aaa;font-size:15px;margin:6px 0 12px;line-height:1.4}.warn{color:#f0b35a}
.cmd{background:#1b1b1f;border:1px solid #2a2a30;border-radius:10px;padding:12px;margin:10px 0}
.cmd b{display:block;font-size:15px}.cmd span{display:block;color:#aaa;font-size:13.5px;margin:4px 0 8px;line-height:1.4}
pre{background:#0b0b0d;border-radius:8px;padding:10px;margin:8px 0;overflow-x:auto;font-size:13px;white-space:pre-wrap;word-break:break-all}
button{font-size:15px;padding:9px 18px;border:0;border-radius:8px;background:#fff;color:#111;font-weight:600}
</style></head><body>
<h1>${esc(appName)}: use as your Home app</h1>
<p>These are the standard ADB commands launchers like FLauncher and ArcLauncher use. They are shown here for you to run if you want to; nothing runs from this page. Menu names vary by TV, and some firmware (many Fire TVs) refuses a different Home app however it is set.</p>
$body
<script>
function copyCmd(btn){var t=btn.parentNode.querySelector('pre').innerText;
var done=function(){btn.textContent='Copied';setTimeout(function(){btn.textContent='Copy'},1500)};
if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done,function(){fb(t,done)})}else{fb(t,done)}}
function fb(t,done){var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();try{document.execCommand('copy');done()}catch(e){}document.body.removeChild(a)}
</script>
</body></html>"""
    }
}
