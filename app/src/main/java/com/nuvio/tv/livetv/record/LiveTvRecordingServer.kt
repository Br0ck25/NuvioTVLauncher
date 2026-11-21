package com.nuvio.tv.livetv.record

import android.util.Log
import jcifs.smb.SmbRandomAccessFile
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import kotlin.concurrent.thread

/**
 * Hands recordings to Nuvio's player as ordinary web links (http://127.0.0.1:…/rec…/index.m3u8),
 * so they play with its full controls (seek bar, pause, audio and subtitle choice). Only this
 * device can reach it, and it only serves files inside the recording's folder.
 *
 * A recording mirrored to SMB keeps its playlists here and its media pieces on the share, so a
 * missing local file is read from the share instead. Watching while it records still reads the
 * local staging copy, which the mirror removes only after everything is uploaded.
 */
object LiveTvRecordingServer {
    private const val TAG = "LiveTvRecordingServer"

    @Volatile private var server: ServerSocket? = null

    private data class Source(
        val dir: File,
        val smb: SmbConfig?,
        val remoteDir: String
    )

    /** Recording id -> where its files are. */
    private val sources = java.util.concurrent.ConcurrentHashMap<String, Source>()

    /** The link for a recording's playlist (starts the server if needed). */
    fun urlFor(
        id: String,
        dir: File,
        smb: SmbConfig? = null,
        remoteDir: String = id
    ): String {
        sources[id] = Source(dir, smb, remoteDir.trim('/'))
        val port = ensure()
        return "http://127.0.0.1:$port/$id/index.m3u8"
    }

    @Synchronized
    private fun ensure(): Int {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        server = s
        thread(name = "rec-server", isDaemon = true) {
            while (!s.isClosed) {
                val client = runCatching { s.accept() }.getOrNull() ?: break
                thread(name = "rec-client", isDaemon = true) { runCatching { serve(client) }.onFailure { Log.w(TAG, "serve: ${it.message}") } }
            }
        }
        return s.localPort
    }

    private fun serve(client: Socket) = client.use { sock ->
        sock.soTimeout = 30_000
        val reader = BufferedReader(InputStreamReader(sock.getInputStream()))
        val requestLine = reader.readLine() ?: return@use
        var rangeStart = -1L
        var rangeEnd = -1L
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) {
                Regex("""bytes=(\d*)-(\d*)""").find(line)?.let { m ->
                    rangeStart = m.groupValues[1].toLongOrNull() ?: -1L
                    rangeEnd = m.groupValues[2].toLongOrNull() ?: -1L
                }
            }
        }
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0) ?: "GET"
        val path = URLDecoder.decode(parts.getOrNull(1)?.substringBefore('?') ?: "/", "UTF-8").trimStart('/')
        val id = path.substringBefore('/')
        val base = sources[id] ?: return@use
        val relative = path.substringAfter('/', "")
        val out = sock.getOutputStream()
        if (relative.contains("..")) return@use
        val local = File(base.dir, relative)
        val remote = base.smb?.let { "${base.remoteDir}/$relative" }
        fun remoteExists() = remote != null && SmbClient.exists(base.smb, remote)
        fun available() = local.isFile || remoteExists()
        // A recording that has only just started: its playlist appears with the first piece, a
        // few seconds in. The mirror can also be a couple of seconds behind. Wait rather than
        // answering "not found" straight away.
        if (!available()) {
            var waited = 0
            while (!available() && waited < 15_000) { Thread.sleep(250); waited += 250 }
        }
        if (!available()) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return@use
        }
        val extension = relative.substringAfterLast('.', "").lowercase()
        val type = when (extension) {
            "m3u8" -> "application/vnd.apple.mpegurl"
            "ts" -> "video/mp2t"
            "m4s", "mp4" -> "video/mp4"
            else -> "application/octet-stream"
        }
        // A recording still being made has no end mark, so a player treats it like a live channel
        // and starts near the newest part. Watching it should start at the beginning (the
        // player's own resume position still applies), so say so in the playlist.
        if (type.contains("mpegurl")) {
            val text = when {
                local.isFile -> runCatching { local.readText() }.getOrDefault("")
                remote != null -> SmbClient.readText(base.smb, remote).orEmpty()
                else -> ""
            }
            if (!text.contains("#EXT-X-ENDLIST") && !text.contains("#EXT-X-START") && !text.contains("#EXT-X-STREAM-INF")) {
                val body = text.replaceFirst("#EXTM3U", "#EXTM3U\n#EXT-X-START:TIME-OFFSET=0,PRECISE=YES").toByteArray()
                out.write(("HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\n" +
                    "Cache-Control: no-cache\r\nConnection: close\r\n\r\n").toByteArray())
                if (!method.equals("HEAD", ignoreCase = true)) out.write(body)
                out.flush()
                return@use
            }
        }
        val length = if (local.isFile) local.length()
        else base.smb?.let { SmbClient.length(it, remote!!) } ?: 0L
        if (length <= 0L) {
            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            return@use
        }
        val start = if (rangeStart >= 0) rangeStart.coerceAtMost(length) else 0L
        val end = if (rangeEnd >= 0) rangeEnd.coerceAtMost(length - 1) else length - 1
        val count = (end - start + 1).coerceAtLeast(0)
        val head = StringBuilder()
        head.append(if (rangeStart >= 0) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
        head.append("Content-Type: ").append(type).append("\r\n")
        head.append("Content-Length: ").append(count).append("\r\n")
        head.append("Accept-Ranges: bytes\r\n")
        if (rangeStart >= 0) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(length).append("\r\n")
        // The playlist changes while recording: never cached.
        if (type.contains("mpegurl")) head.append("Cache-Control: no-cache\r\n")
        head.append("Connection: close\r\n\r\n")
        out.write(head.toString().toByteArray())
        if (method.equals("HEAD", ignoreCase = true)) return@use
        if (local.isFile) {
            RandomAccessFile(local, "r").use { raf -> copy(raf::seek, raf::read, start, count, out) }
        } else if (base.smb != null && remote != null) {
            val raf: SmbRandomAccessFile? = SmbClient.randomAccess(base.smb, remote)
            if (raf == null) return@use
            raf.use { copy(it::seek, it::read, start, count, out) }
        }
        out.flush()
    }

    private fun copy(
        seek: (Long) -> Unit,
        read: (ByteArray, Int, Int) -> Int,
        start: Long,
        count: Long,
        out: java.io.OutputStream
    ) {
        seek(start)
        val buf = ByteArray(64 * 1024)
        var left = count
        while (left > 0) {
            val n = read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) break
            out.write(buf, 0, n)
            left -= n
        }
    }
}
