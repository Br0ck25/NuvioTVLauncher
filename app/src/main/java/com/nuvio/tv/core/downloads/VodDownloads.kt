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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

typealias VodDownloadConnectionFactory = (String, Map<String, String>, String, Int, Int) -> HttpURLConnection

/** Direct-file offline saves for sources the user is permitted to keep. */
object VodDownloads {
    enum class Status { DOWNLOADING, COMPLETE, FAILED }

    data class Item(
        val id: String,
        val title: String,
        val filename: String,
        val destinationId: String,
        val status: Status,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long? = null,
        val bytesPerSecond: Long = 0L,
        val remainingSeconds: Long? = null,
        val startedAtMs: Long = System.currentTimeMillis(),
        val error: String? = null
    )

    data class State(val items: List<Item> = emptyList(), val initialized: Boolean = false) {
        val active: List<Item> get() = items.filter { it.status == Status.DOWNLOADING }
        val completed: List<Item> get() = items.filter { it.status == Status.COMPLETE }
        val failed: List<Item> get() = items.filter { it.status == Status.FAILED }
        val totalDownloadedBytes: Long get() = completed.sumOf { it.bytesDownloaded }
    }

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
    private val jobs = ConcurrentHashMap<String, Job>()
    private val connections = ConcurrentHashMap<String, HttpURLConnection>()
    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var initialized = false
    private val safeExtensions = setOf("mp4", "m4v", "mkv", "webm", "mov", "avi", "mpg", "mpeg", "wmv")

    /** Loads the saved download list once; URLs and request headers are deliberately never persisted. */
    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val saved = runCatching {
            val raw = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .getString(KEY_ITEMS, "[]")
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                Item(
                    id = item.optString("id"),
                    title = item.optString("title"),
                    filename = item.optString("filename"),
                    destinationId = item.optString("destinationId"),
                    status = Status.COMPLETE,
                    bytesDownloaded = item.optLong("bytesDownloaded"),
                    startedAtMs = item.optLong("startedAtMs")
                ).takeIf { it.id.isNotBlank() && it.filename.isNotBlank() }
            }
        }.getOrDefault(emptyList())
        _state.value = State(items = saved, initialized = true)
        initialized = true
    }

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
        init(app)
        val id = UUID.randomUUID().toString()
        val filename = runCatching { outputName(request) }.getOrDefault(safeTitle(request.title))
        val item = Item(
            id = id,
            title = safeTitle(request.title),
            filename = filename,
            destinationId = destinationId,
            status = Status.DOWNLOADING
        )
        _state.update { it.copy(items = listOf(item) + it.items) }
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val size = save(app, request, destinationId, id, connectionFactory) { bytes, total ->
                    updateProgress(id, bytes, total)
                }
                val completed = _state.value.items.firstOrNull { it.id == id }?.copy(
                    status = Status.COMPLETE,
                    bytesDownloaded = size,
                    totalBytes = size,
                    remainingSeconds = 0L,
                    bytesPerSecond = 0L,
                    error = null
                ) ?: item.copy(status = Status.COMPLETE, bytesDownloaded = size, totalBytes = size)
                _state.update { current ->
                    current.copy(items = current.items.map { if (it.id == id) completed else it })
                }
                persistCompleted(app)
                toast(app, "Download complete: ${safeTitle(request.title)}")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                _state.update { current ->
                    current.copy(items = current.items.mapNotNull {
                        if (it.id != id) it else it.copy(
                            status = Status.FAILED,
                            remainingSeconds = null,
                            error = error.message?.take(140) ?: "Download failed"
                        )
                    })
                }
                toast(app, "Download failed: ${error.message?.take(120) ?: "could not save this file"}")
            } finally {
                activeRequests.remove(key)
                jobs.remove(id)
                connections.remove(id)?.disconnect()
                if (_state.value.items.any { it.id == id && it.status == Status.DOWNLOADING }) {
                    _state.update { current -> current.copy(items = current.items.filterNot { it.id == id }) }
                }
            }
        }
        jobs[id] = job
        job.start()
        return true
    }

    fun cancel(id: String) {
        connections.remove(id)?.disconnect()
        jobs[id]?.cancel(CancellationException("Download cancelled"))
    }

    fun remove(context: Context, id: String) {
        val item = _state.value.items.firstOrNull { it.id == id } ?: return
        if (item.status == Status.DOWNLOADING) {
            cancel(id)
            return
        }
        if (item.status == Status.FAILED) {
            _state.update { current -> current.copy(items = current.items.filterNot { it.id == id }) }
            return
        }
        scope.launch {
            runCatching { deleteFile(context.applicationContext, item) }
                .onSuccess {
                    _state.update { current -> current.copy(items = current.items.filterNot { it.id == id }) }
                    persistCompleted(context.applicationContext)
                }
                .onFailure { error -> toast(context.applicationContext, "Could not delete download: ${error.message.orEmpty()}") }
        }
    }

    fun clearCompleted(context: Context) {
        val app = context.applicationContext
        scope.launch {
            val completed = _state.value.completed
            val deleted = completed.filter { item -> runCatching { deleteFile(app, item) }.isSuccess }
            val deletedIds = deleted.mapTo(hashSetOf()) { it.id }
            _state.update { current -> current.copy(items = current.items.filterNot { it.id in deletedIds }) }
            persistCompleted(app)
        }
    }

    private fun updateProgress(id: String, bytes: Long, total: Long) {
        val now = System.currentTimeMillis()
        _state.update { current ->
            current.copy(items = current.items.map { item ->
                if (item.id != id) item else {
                    val elapsed = (now - item.startedAtMs).coerceAtLeast(1L)
                    val speed = (bytes * 1000L / elapsed).coerceAtLeast(0L)
                    val remaining = if (total > bytes && speed > 0L) (total - bytes) / speed else null
                    item.copy(
                        bytesDownloaded = bytes,
                        totalBytes = total.takeIf { it > 0L },
                        bytesPerSecond = speed,
                        remainingSeconds = remaining
                    )
                }
            })
        }
    }

    private fun persistCompleted(context: Context) {
        val array = JSONArray()
        _state.value.completed.forEach { item ->
            array.put(JSONObject().apply {
                put("id", item.id)
                put("title", item.title)
                put("filename", item.filename)
                put("destinationId", item.destinationId)
                put("bytesDownloaded", item.bytesDownloaded)
                put("startedAtMs", item.startedAtMs)
            })
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ITEMS, array.toString())
            .apply()
    }

    private fun deleteFile(context: Context, item: Item) {
        val destination = RecordingStorage.choices(context).firstOrNull { it.id == item.destinationId }
            ?: error("Reconnect the storage containing this download, then try again.")
        if (destination.isSmb) {
            val config = RecordingStorage.smbConfig(context) ?: error("The SMB destination is not configured.")
            SmbClient.deleteRecursively(config, "NuvioDownloads/${item.filename}")
        } else {
            val root = File(destination.dir.parentFile ?: context.filesDir, "downloads")
            val file = File(root, item.filename)
            if (file.exists() && !file.delete()) error("Could not delete the file.")
        }
    }

    private suspend fun save(
        context: Context,
        request: Request,
        destinationId: String,
        downloadId: String,
        connectionFactory: VodDownloadConnectionFactory,
        onProgress: (Long, Long) -> Unit
    ): Long {
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
            connections[downloadId] = next
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
                        onProgress(partial.length(), length)
                    }
                }
            }
            val savedBytes = partial.length()
            if (savedBytes <= 0L) error("The source returned an empty file.")
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
            return savedBytes
        } finally {
            response.disconnect()
            connections.remove(downloadId, response)
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

    private const val PREFS = "vod_download_manager"
    private const val KEY_ITEMS = "completed_downloads"

    private suspend fun toast(context: Context, message: String) = withContext(Dispatchers.Main) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
}
