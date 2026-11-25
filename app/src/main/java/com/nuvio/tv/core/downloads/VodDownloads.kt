package com.nuvio.tv.core.downloads

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.nuvio.tv.core.player.isStalkerPlaybackAddress
import com.nuvio.tv.livetv.record.RecordingStorage
import com.nuvio.tv.livetv.record.SmbClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

typealias VodDownloadConnectionFactory = (String, Map<String, String>, String, Int, Int) -> HttpURLConnection

/** Direct-file offline saves for sources the user is permitted to keep. */
object VodDownloads {
    data class Request(
        val url: String,
        val headers: Map<String, String>,
        val title: String,
        val filename: String?,
        val isLive: Boolean,
        val isYoutube: Boolean,
        val knownDrm: Boolean
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeRequests = ConcurrentHashMap.newKeySet<String>()
    private val safeExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "mpg", "mpeg", "wmv")

    /** Reject unsupported routes before the user confirms the save. */
    fun canDownload(request: Request): Boolean {
        val uri = runCatching { Uri.parse(request.url) }.getOrNull() ?: return false
        if (request.isLive || request.isYoutube || request.knownDrm || request.url.isBlank() || isStalkerPlaybackAddress(request.url)) return false
        if (!uri.scheme.equals("http", true) && !uri.scheme.equals("https", true)) return false
        val path = (uri.lastPathSegment ?: request.filename.orEmpty()).lowercase(Locale.ROOT)
        if (path.endsWith(".m3u8") || path.endsWith(".mpd") || path.endsWith(".ism") || path.endsWith(".ts") || path.endsWith(".m4s")) return false
        val ext = listOf(request.filename, path)
            .filterNotNull()
            .map(::extension)
            .firstOrNull { it in safeExtensions }
        return ext != null
    }

    fun start(
        context: Context,
        request: Request,
        destinationId: String,
        permissionConfirmed: Boolean,
        connectionFactory: VodDownloadConnectionFactory
    ): Boolean {
        if (!permissionConfirmed || !canDownload(request)) return false
        val app = context.applicationContext
        val key = "$destinationId|${request.url}"
        if (!activeRequests.add(key)) return false
        scope.launch {
            try {
                save(app, request, destinationId, connectionFactory)
                toast(app, "Download complete: ${safeTitle(request.title)}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                toast(app, "Download failed: ${error.message?.take(120) ?: "could not save this file"}")
            } finally {
                activeRequests.remove(key)
            }
        }
        return true
    }

    private suspend fun save(
        context: Context,
        request: Request,
        destinationId: String,
        connectionFactory: VodDownloadConnectionFactory
    ) {
        val choice = RecordingStorage.choices(context).firstOrNull { it.id == destinationId }
            ?: error("The selected storage is no longer available.")
        if (choice.isSmb && !RecordingStorage.isSmbConfigured(context)) {
            error("Set up the SMB destination in Live TV recording settings first.")
        }
        val name = outputName(request)
        val localRoot = if (choice.isSmb) {
            File(context.cacheDir, "vod-download-staging")
        } else {
            File(choice.dir.parentFile ?: context.filesDir, "downloads")
        }
        if (!localRoot.exists() && !localRoot.mkdirs()) error("The selected storage is unavailable.")
        if (!localRoot.canWrite()) error("The selected storage is read-only.")
        val finalFile = File(localRoot, name)
        val partial = File(localRoot, "$name.part")
        var committed = false
        val initialUrl = URL(request.url)
        var targetUrl = initialUrl
        var connection: HttpURLConnection? = null
        for (redirect in 0..5) {
            val next = connectionFactory(targetUrl.toExternalForm(), request.headers, "GET", 20_000, 30_000)
            next.instanceFollowRedirects = false
            next.setRequestProperty("Accept-Encoding", "identity")
            val sameOrigin = targetUrl.protocol.equals(initialUrl.protocol, true) &&
                targetUrl.host.equals(initialUrl.host, true) && targetUrl.port == initialUrl.port
            request.headers.forEach { (key, value) ->
                val sensitive = key.equals("Authorization", true) || key.equals("Cookie", true) || key.equals("Proxy-Authorization", true)
                if (key.isNotBlank() && value.isNotBlank() && !key.equals("Host", true) &&
                    !key.equals("Content-Length", true) && (sameOrigin || !sensitive)
                ) {
                    next.setRequestProperty(key, value)
                }
            }
            val status = next.responseCode
            if (status in setOf(301, 302, 303, 307, 308)) {
                val location = next.getHeaderField("Location")
                next.disconnect()
                if (redirect == 5 || location.isNullOrBlank()) error("The source redirected too many times.")
                val redirected = URL(targetUrl, location)
                if (initialUrl.protocol.equals("https", true) && redirected.protocol.equals("http", true)) {
                    error("The source attempted an insecure redirect.")
                }
                targetUrl = redirected
                continue
            }
            connection = next
            break
        }
        val response = connection ?: error("The source could not be reached.")
        try {
            val status = response.responseCode
            if (status !in 200..299) error("The source returned HTTP $status.")
            val mime = response.contentType.orEmpty().substringBefore(';').trim().lowercase(Locale.ROOT)
            if (mime.contains("mpegurl") || mime.contains("dash+xml") || mime.contains("smoothstreaming")) {
                error("Adaptive playlists are not supported for offline saving.")
            }
            if (mime.isNotBlank() && mime != "application/octet-stream" && !mime.startsWith("video/") && mime != "application/mp4") {
                error("The source did not return a video file.")
            }
            val length = response.contentLengthLong
            if (length > 0 && length > localRoot.usableSpace) error("There is not enough free space on the selected storage.")
            partial.delete()
            response.inputStream.use { input ->
                partial.outputStream().buffered(256 * 1024).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        coroutineContext.ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            if (partial.length() <= 0L) error("The source returned an empty file.")
            if (choice.isSmb) {
                val config = RecordingStorage.smbConfig(context) ?: error("The SMB destination is not configured.")
                SmbClient.upload(config, partial, "NuvioDownloads/$name")
                partial.delete()
                committed = true
            } else {
                if (finalFile.exists() && !finalFile.delete()) error("Could not replace the existing file.")
                if (!partial.renameTo(finalFile)) {
                    partial.copyTo(finalFile, overwrite = true)
                    partial.delete()
                }
                committed = true
            }
        } finally {
            response.disconnect()
            if (partial.exists() && !committed) partial.delete()
        }
    }

    private fun outputName(request: Request): String {
        val candidate = request.filename?.takeIf { extension(it) in safeExtensions }
            ?: Uri.parse(request.url).lastPathSegment.orEmpty()
        val ext = extension(candidate).takeIf { it in safeExtensions }
            ?: error("The source URL must point to a supported video file.")
        val base = candidate.substringBeforeLast('.', candidate)
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim('.', ' ', '_')
            .take(100)
            .ifBlank { safeTitle(request.title) }
        return "$base.$ext"
    }

    private fun extension(name: String): String = name.substringAfterLast('.', "").substringBefore('?').lowercase(Locale.ROOT)

    private fun safeTitle(value: String): String = value.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().take(80).ifBlank { "video" }

    private suspend fun toast(context: Context, message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
