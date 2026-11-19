package com.nuvio.tv.livetv.crash

import android.content.res.Resources
import android.view.View
import com.nuvio.tv.R
import java.io.InputStream
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom

/**
 * "Restore from your phone": a small page on the home network where the backup .json can be
 * chosen with the phone's own file picker (Google Drive, Downloads, Files…) and sent to the TV.
 *
 * The TV validates and applies the backup itself. Only runs while the QR dialog is open, nothing
 * leaves the home network, and the address has a random code so only someone who sees the TV can
 * use it.
 */
object RestoreFromPhoneServer {

    data class Texts(
        val languageTag: String,
        val isRtl: Boolean,
        val title: String,
        val connectedTo: String,
        val heading: String,
        val instructions: String,
        val choose: String,
        val selected: String,
        val send: String,
        val replace: String,
        val sending: String,
        val received: String,
        val tooLarge: String,
        val unreachable: String,
        val nothing: String,
        val networkNote: String
    ) {
        companion object {
            fun from(resources: Resources, tvName: String): Texts {
                val configuration = resources.configuration
                val languageTag = configuration.locales.takeIf { !it.isEmpty }
                    ?.get(0)?.toLanguageTag()?.takeIf { it.isNotBlank() } ?: "en"
                return Texts(
                    languageTag = languageTag,
                    isRtl = configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL,
                    title = resources.getString(R.string.iptv_settings_restore_phone_title),
                    connectedTo = resources.getString(R.string.iptv_phone_connected_to, tvName),
                    heading = resources.getString(R.string.iptv_settings_restore_phone_heading),
                    instructions = resources.getString(R.string.iptv_settings_restore_phone_instructions),
                    choose = resources.getString(R.string.iptv_settings_restore_phone_choose),
                    selected = resources.getString(R.string.iptv_settings_restore_phone_selected),
                    send = resources.getString(R.string.iptv_settings_restore_phone_send),
                    replace = resources.getString(R.string.iptv_settings_restore_phone_replace),
                    sending = resources.getString(R.string.iptv_settings_restore_phone_sending),
                    received = resources.getString(R.string.iptv_settings_restore_phone_received),
                    tooLarge = resources.getString(R.string.iptv_settings_restore_phone_too_large),
                    unreachable = resources.getString(R.string.iptv_settings_restore_phone_unreachable),
                    nothing = resources.getString(R.string.iptv_settings_restore_phone_nothing),
                    networkNote = resources.getString(R.string.iptv_settings_restore_phone_network)
                )
            }
        }
    }

    class Handle(val url: String, private val socket: ServerSocket) {
        fun stop() { runCatching { socket.close() } }
    }

    /**
     * Null when there's no home network. [onBackup] runs on a background thread with the text the
     * phone sent and returns the line shown on the phone ("Sent. Check your TV.").
     */
    fun start(texts: Texts, accent: String, onBackup: (String) -> String): Handle? {
        val ip = localAddress() ?: return null
        val server = bind() ?: return null
        val token = randomToken()
        Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (e: Exception) { break }
                Thread { serve(client, token, texts, accent, onBackup) }.apply { isDaemon = true; start() }
            }
        }.apply { isDaemon = true; name = "restore-from-phone"; start() }
        return Handle("http://$ip:${server.localPort}/$token", server)
    }

    private fun bind(): ServerSocket? {
        for (port in 8686..8695) {
            val s = runCatching { ServerSocket(port) }.getOrNull()
            if (s != null) return s
        }
        return runCatching { ServerSocket(0) }.getOrNull()
    }

    private fun randomToken(): String {
        val chars = "abcdefghjkmnpqrstuvwxyz23456789"
        val r = SecureRandom()
        return (1..8).map { chars[r.nextInt(chars.length)] }.joinToString("")
    }

    private fun localAddress(): String? = runCatching {
        val found = NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif -> nif.inetAddresses.toList().filter { it is Inet4Address && it.isSiteLocalAddress }.map { nif.name to it.hostAddress } }
        (found.firstOrNull { it.first.startsWith("wlan") || it.first.startsWith("eth") } ?: found.firstOrNull())?.second
    }.getOrNull()

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(b.toChar())
            if (sb.length > 8192) return sb.toString()
        }
    }

    private fun serve(
        client: Socket,
        token: String,
        texts: Texts,
        accent: String,
        onBackup: (String) -> String
    ) {
        runCatching {
            client.use { c ->
                c.soTimeout = 20_000
                val input = c.getInputStream().buffered()
                val request = readLine(input).orEmpty()
                var length = 0
                while (true) {
                    val l = readLine(input) ?: break
                    if (l.isEmpty()) break
                    if (l.startsWith("content-length:", ignoreCase = true)) length = l.substringAfter(':').trim().toIntOrNull() ?: 0
                }
                val method = request.substringBefore(' ')
                val path = request.split(' ').getOrNull(1).orEmpty().substringBefore('?').trim('/')
                val out = c.getOutputStream()
                fun send(status: String, type: String, body: ByteArray) {
                    out.write(
                        ("HTTP/1.1 $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                            "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray()
                    )
                    out.write(body)
                    out.flush()
                }
                when {
                    method == "GET" && path == token ->
                        send("200 OK", "text/html; charset=utf-8", page(token, texts, accent).toByteArray(Charsets.UTF_8))
                    method == "POST" && path == "$token/restore" -> {
                        if (length !in 1..16 * 1024 * 1024) {
                            send("413 Payload Too Large", "application/json", json(false, texts.tooLarge))
                            return@use
                        }
                        val bytes = ByteArray(length)
                        var read = 0
                        while (read < length) {
                            val n = input.read(bytes, read, length - read)
                            if (n < 0) break
                            read += n
                        }
                        val body = String(bytes, 0, read, Charsets.UTF_8).trim()
                        if (body.isEmpty()) {
                            send("400 Bad Request", "application/json", json(false, texts.nothing))
                        } else {
                            val message = runCatching { onBackup(body) }.getOrElse { texts.unreachable }
                            send("200 OK", "application/json", json(true, message))
                        }
                    }
                    else -> send("404 Not Found", "text/plain; charset=utf-8", texts.nothing.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }

    /** JSON string literals also safe inside an HTML script block. */
    private fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        for (c in s) {
            when (c) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                '<' -> append("\\u003c")
                '>' -> append("\\u003e")
                '&' -> append("\\u0026")
                '\u2028' -> append("\\u2028")
                '\u2029' -> append("\\u2029")
                else -> if (c.code < 0x20) {
                    append("\\u")
                    append(c.code.toString(16).padStart(4, '0'))
                } else append(c)
            }
        }
        append('"')
    }

    private fun json(ok: Boolean, message: String) = "{\"ok\":$ok,\"message\":${quote(message)}}".toByteArray(Charsets.UTF_8)

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")

    private fun page(token: String, texts: Texts, accent: String): String = """<!doctype html>
<html lang="${esc(texts.languageTag)}" dir="${if (texts.isRtl) "rtl" else "ltr"}"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>${esc(texts.title)}</title>
<style>
:root{--accent:${esc(accent)}}
*{box-sizing:border-box}
body{margin:0;background:#0E0E12;color:#F2F2F5;font-family:-apple-system,Roboto,"Segoe UI",sans-serif}
main{max-width:480px;margin:0 auto;padding:32px 20px;display:flex;flex-direction:column;gap:20px}
.chip{display:inline-flex;align-items:center;gap:8px;background:#17171D;border:1px solid #26262F;border-radius:999px;padding:6px 12px;font-size:13px;color:#C9C9D3;align-self:flex-start}
.dot{width:8px;height:8px;border-radius:50%;background:#3DDC97}
h1{margin:0;font-size:24px}
p{margin:0;font-size:14px;color:#A9A9B5;line-height:1.5}
.file{display:flex;align-items:center;justify-content:center;min-height:64px;border:1px dashed #3A3A46;border-radius:12px;background:#17171D;color:#C9C9D3;font-size:15px;font-weight:600;cursor:pointer;padding:12px;text-align:center}
.file input{display:none}
.picked{background:#17171D;border:1px solid #2E2E39;border-radius:10px;padding:12px 14px;font-size:14px;color:#F2F2F5;word-break:break-all;line-height:1.4}
[hidden]{display:none!important}
.send{height:52px;border:0;border-radius:12px;background:var(--accent);color:#0B0B10;font-size:16px;font-weight:700;font-family:inherit}
.send:disabled{opacity:.6}
#msg{min-height:20px;font-size:15px;text-align:center;line-height:1.4}
#msg.ok{color:#3DDC97}#msg.bad{color:#FF8A80}
.note{font-size:12px;color:#8E8E9B;text-align:center;line-height:1.5}
</style></head><body><main>
<div class="chip"><span class="dot"></span><span>${esc(texts.connectedTo)}</span></div>
<h1>${esc(texts.heading)}</h1>
<p>${esc(texts.instructions)}</p>
<label class="file">${esc(texts.choose)}<input id="file" type="file" accept=".json,application/json,text/plain,application/octet-stream"></label>
<div id="picked" class="picked" hidden></div>
<button class="send" id="send">${esc(texts.send)}</button>
<div id="msg" role="status"></div>
<p class="note">${esc(texts.replace)}<br>${esc(texts.networkNote)}</p>
</main>
<script>
var labels={sending:${quote(texts.sending)},received:${quote(texts.received)},tooLarge:${quote(texts.tooLarge)},unreachable:${quote(texts.unreachable)},nothing:${quote(texts.nothing)},selected:${quote(texts.selected)}};
var f=document.getElementById('file'),b=document.getElementById('send'),m=document.getElementById('msg'),picked=document.getElementById('picked');
function msg(t,c){m.textContent=t;m.className=c||''}
function showPicked(){var file=f.files[0];if(!file){picked.hidden=true;picked.textContent='';return}
picked.hidden=false;picked.textContent=labels.selected.replace('%1${'$'}s',file.name)}
f.onchange=showPicked;
function read(file){if(file.text)return file.text();return new Promise(function(res,rej){var r=new FileReader();r.onload=function(){res(r.result)};r.onerror=rej;r.readAsText(file)})}
b.onclick=function(){var file=f.files[0];if(!file){msg(labels.nothing,'bad');return}
if(file.size>16777216){msg(labels.tooLarge,'bad');return}
b.disabled=true;msg(labels.sending,'');
read(file).then(function(t){return fetch(location.pathname.replace(/\/$/,'')+'/restore',{method:'POST',headers:{'Content-Type':'application/json'},body:t})})
.then(function(r){return r.json()}).then(function(j){msg(j.message,j.ok?'ok':'bad');if(j.ok){f.value='';picked.hidden=true;picked.textContent=''}})
.catch(function(){msg(labels.unreachable,'bad')}).finally(function(){b.disabled=false})};
</script>
</body></html>"""
}
