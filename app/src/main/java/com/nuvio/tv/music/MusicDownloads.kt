package com.nuvio.tv.music

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadHelper
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Requirements
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.artworkAt
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Where one downloaded track is in the media3 download state machine. */
enum class MusicDownloadStatus {
    QUEUED,
    DOWNLOADING,
    PAUSED,
    COMPLETE,
    FAILED
}

data class MusicDownloadItem(
    val song: Song,
    val status: MusicDownloadStatus,
    val progress: Float,
    val bytes: Long,
    val totalBytes: Long,
    val failureReason: Int = 0
) {
    val videoId: String get() = song.videoId
}

data class MusicDownloadsState(
    val items: List<MusicDownloadItem> = emptyList(),
    val starting: Map<String, Song> = emptyMap(),
    val paused: Boolean = false,
    val waitingForWifi: Boolean = false,
    val error: String? = null
) {
    val completed: List<MusicDownloadItem>
        get() = items.filter { it.status == MusicDownloadStatus.COMPLETE }
    val active: List<MusicDownloadItem>
        get() = items.filter {
            it.status == MusicDownloadStatus.DOWNLOADING ||
                it.status == MusicDownloadStatus.QUEUED ||
                it.status == MusicDownloadStatus.PAUSED
        }
    val failed: List<MusicDownloadItem>
        get() = items.filter { it.status == MusicDownloadStatus.FAILED }
    val busy: Boolean get() = active.isNotEmpty() || starting.isNotEmpty()
    val totalBytes: Long get() = completed.sumOf { it.totalBytes }

    fun entry(videoId: String): MusicDownloadItem? =
        items.firstOrNull { it.videoId == videoId }
}

/**
 * Offline downloads for the Music section.
 *
 * Media3 owns the download protocol and the cache; this object owns the bit the UI needs:
 * resolving a Music track to a downloadable stream, keeping the download metadata keyed by
 * video id, and handing playback the downloaded copy when one exists.
 */
@UnstableApi
object MusicDownloads {
    private const val CHANNEL_ID = "music_downloads"
    private const val CACHE_FOLDER = "NuvioDownloads"
    private const val STREAM_CACHE_BYTES = 512L * 1024 * 1024
    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13; Android TV) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val preparingJobs = ConcurrentHashMap<String, Job>()
    private val prefetchedArtwork = ConcurrentHashMap.newKeySet<String>()

    private val _state = MutableStateFlow(MusicDownloadsState())
    val state: StateFlow<MusicDownloadsState> = _state.asStateFlow()

    private var appContext: Context? = null
    private var cache: SimpleCache? = null
    private var database: StandaloneDatabaseProvider? = null
    private var manager: DownloadManager? = null
    private var initialized = false

    /** One stream lookup and manifest preparation at a time; a playlist can queue 100. */
    private val prepareMutex = Mutex()

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        val app = context.applicationContext
        appContext = app
        MusicSettings.init(app)

        val root = app.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            ?: File(app.filesDir, "Music")
        val dir = File(root, CACHE_FOLDER).apply { mkdirs() }
        // Streamed tracks use the same store as downloads, so cap the disposable part.
        // Pinned download spans are kept even when the budget is reached.
        val simpleCache = SimpleCache(
            dir,
            LeastRecentlyUsedCacheEvictor(STREAM_CACHE_BYTES)
        )
        val db = StandaloneDatabaseProvider(app)
        val downloadManager = DownloadManager(
            app,
            db,
            simpleCache,
            downloadUpstreamFactory(),
            Executors.newFixedThreadPool(2)
        )
        downloadManager.maxParallelDownloads = 1
        downloadManager.minRetryCount = 3
        downloadManager.addListener(listener)

        cache = simpleCache
        database = db
        manager = downloadManager
        initialized = true
        updateRequirements()
        refresh()

        scope.launch {
            MusicSettings.wifiOnlyDownloads.collect {
                updateRequirements()
                refresh()
            }
        }
    }

    fun cacheDataSourceFactory(upstream: DataSource.Factory): DataSource.Factory {
        val store = cache ?: error("MusicDownloads.init() has not been called")
        return CacheDataSource.Factory()
            .setCache(store)
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    internal fun downloadManager(context: Context): DownloadManager {
        init(context)
        return manager ?: error("MusicDownloads.init() failed")
    }

    fun download(song: Song) {
        if (appContext == null) return
        val id = song.videoId
        if (id.isBlank()) return
        if (manager?.downloadIndex?.getDownload(id) != null) return
        if (preparingJobs.containsKey(id)) return

        val job = scope.launch(start = CoroutineStart.LAZY) {
            _state.update { it.copy(starting = it.starting + (id to song), error = null) }
            prefetchArtwork(song)
            try {
                val request = prepareMutex.withLock {
                    val stream = withContext(Dispatchers.IO) {
                        MusicStreamResolver.resolve(song.videoId)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        val app = appContext ?: error("Downloads haven't been initialized")
                        val item = MediaItem.Builder()
                            .setUri(stream.url)
                            .setMediaId(song.videoId)
                            .setMimeType(stream.mimeType)
                            .setMediaMetadata(
                                MediaMetadata.Builder()
                                    .setTitle(song.title)
                                    .setArtist(song.artist)
                                    .setAlbumTitle(song.albumName)
                                    .setArtworkUri(song.thumbnailUrl?.let(Uri::parse))
                                    .build()
                            )
                            .build()
                        val params = TrackSelectionParameters.Builder()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                            .setMaxAudioBitrate(
                                MusicSettings.downloadQuality.value.maxKbps * 1000
                            )
                            .build()
                        val helper = DownloadHelper.forMediaItem(
                            item,
                            params,
                            DefaultRenderersFactory(app),
                            downloadUpstreamFactory()
                        )
                        try {
                            awaitPrepared(helper)
                            helper.getDownloadRequest(song.videoId, song.toDownloadData())
                        } finally {
                            helper.release()
                        }
                    }
                }
                appContext?.let {
                    DownloadService.sendAddDownload(
                        it,
                        MusicDownloadService::class.java,
                        request,
                        true
                    )
                }
                refresh()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.update {
                    it.copy(error = error.message ?: "Couldn't download this track.")
                }
            } finally {
                preparingJobs.remove(id)
                _state.update { it.copy(starting = it.starting - id) }
                refresh()
            }
        }
        preparingJobs[id] = job
        _state.update { it.copy(starting = it.starting + (id to song)) }
        job.start()
    }

    fun downloadAll(songs: List<Song>) {
        songs.filter { it.videoId.isNotBlank() }.forEach(::download)
    }

    fun cancel(videoId: String) {
        preparingJobs.remove(videoId)?.cancel()
        manager?.removeDownload(videoId)
        _state.update { it.copy(starting = it.starting - videoId) }
        refresh()
    }

    fun remove(videoId: String) {
        preparingJobs.remove(videoId)?.cancel()
        manager?.removeDownload(videoId)
        refresh()
    }

    fun retry(item: MusicDownloadItem) {
        manager?.removeDownload(item.videoId)
        download(item.song)
    }

    fun pauseAll() {
        manager?.pauseDownloads()
        refresh()
    }

    fun resumeAll() {
        manager?.resumeDownloads()
        refresh()
    }

    fun clearCompleted() {
        val settled = downloads().filter {
            it.state == Download.STATE_COMPLETED || it.state == Download.STATE_FAILED
        }
        settled.forEach { manager?.removeDownload(it.request.id) }
        refresh()
    }

    fun downloadedSong(videoId: String): Song? =
        _state.value.completed.firstOrNull { it.videoId == videoId }?.song

    /**
     * The player's copy of a downloaded track. The stored request keeps the original URI,
     * which is also the cache key, so both the manifest and every segment resolve from the
     * offline cache even after the process restarts.
     */
    fun downloadedMediaItem(song: Song): MediaItem? {
        val download = manager?.downloadIndex?.getDownload(song.videoId) ?: return null
        if (download.state != Download.STATE_COMPLETED) return null
        return download.request.toMediaItem(
            MediaItem.Builder()
                .setMediaId(song.videoId)
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(song.title)
                        .setArtist(song.artist)
                        .setAlbumTitle(song.albumName)
                        .setArtworkUri(song.thumbnailUrl?.let(Uri::parse))
                        .build()
                )
        )
    }

    fun isDownloaded(videoId: String): Boolean =
        manager?.downloadIndex?.getDownload(videoId)?.state == Download.STATE_COMPLETED

    private fun updateRequirements() {
        val m = manager ?: return
        m.setRequirements(
            if (MusicSettings.wifiOnlyDownloads.value) {
                Requirements(Requirements.NETWORK_UNMETERED)
            } else {
                Requirements(Requirements.NETWORK)
            }
        )
    }

    private fun refresh() {
        scope.launch {
            val m = manager ?: return@launch
            val items = downloads()
                .mapNotNull { download ->
                    val song = songFromDownloadData(download.request.data)
                        ?: Song(
                            videoId = download.request.id,
                            title = download.request.id,
                            artist = "",
                            thumbnailUrl = null
                        )
                    MusicDownloadItem(
                        song = song,
                        status = download.status(),
                        progress = (download.percentDownloaded / 100f).coerceIn(0f, 1f),
                        bytes = download.bytesDownloaded,
                        totalBytes = download.contentLength.coerceAtLeast(0L),
                        failureReason = download.failureReason
                    )
                }
                .sortedWith(
                    compareBy<MusicDownloadItem> {
                        when (it.status) {
                            MusicDownloadStatus.DOWNLOADING -> 0
                            MusicDownloadStatus.QUEUED -> 1
                            MusicDownloadStatus.PAUSED -> 2
                            MusicDownloadStatus.FAILED -> 3
                            MusicDownloadStatus.COMPLETE -> 4
                        }
                    }.thenBy { it.song.title.lowercase() }
                )
            _state.update {
                it.copy(
                    items = items,
                    paused = m.downloadsPaused,
                    waitingForWifi = m.isWaitingForRequirements
                )
            }
            // Coil's disk cache normally holds the thumbnails already; make sure the
            // high-resolution cover used by Now Playing is there too before the network goes.
            items.filter { it.status == MusicDownloadStatus.COMPLETE }.forEach { item ->
                if (prefetchedArtwork.add(item.videoId)) prefetchArtwork(item.song)
            }
        }
    }

    private fun downloads(): List<Download> {
        val index = manager?.downloadIndex ?: return emptyList()
        val result = mutableListOf<Download>()
        runCatching {
            index.getDownloads().use { cursor ->
                while (cursor.moveToNext()) result += cursor.download
            }
        }
        return result
    }

    private fun downloadUpstreamFactory(): DataSource.Factory =
        OkHttpDataSource.Factory(PlayerPlaybackNetworking.createHttpClient())
            .setUserAgent(DEFAULT_USER_AGENT)

    /** Fills Coil's disk cache with the covers the offline downloads screens ask for. */
    private fun prefetchArtwork(song: Song) {
        val app = appContext ?: return
        val loader = SingletonImageLoader.get(app)
        listOf(480, 1200).forEach { px ->
            val url = song.thumbnailUrl?.artworkAt(px) ?: return@forEach
            runCatching {
                loader.enqueue(
                    ImageRequest.Builder(app)
                        .data(url)
                        .build()
                )
            }
        }
    }

    private suspend fun awaitPrepared(helper: DownloadHelper) {
        suspendCancellableCoroutine { continuation ->
            Handler(Looper.getMainLooper()).post {
                if (!continuation.isActive) return@post
                runCatching {
                    helper.prepare(
                        object : DownloadHelper.Callback {
                            override fun onPrepared(helper: DownloadHelper, periodCount: Boolean) {
                                if (continuation.isActive) continuation.resume(Unit)
                            }

                            override fun onPrepareError(
                                helper: DownloadHelper,
                                error: java.io.IOException
                            ) {
                                if (continuation.isActive) {
                                    continuation.resumeWithException(error)
                                }
                            }
                        }
                    )
                }.onFailure {
                    if (continuation.isActive) continuation.resumeWithException(it)
                }
            }
        }
    }

    private fun Download.status(): MusicDownloadStatus = when (state) {
        Download.STATE_COMPLETED -> MusicDownloadStatus.COMPLETE
        Download.STATE_FAILED -> MusicDownloadStatus.FAILED
        Download.STATE_STOPPED -> MusicDownloadStatus.PAUSED
        Download.STATE_DOWNLOADING, Download.STATE_RESTARTING -> MusicDownloadStatus.DOWNLOADING
        else -> MusicDownloadStatus.QUEUED
    }

    private val listener = object : DownloadManager.Listener {
        override fun onDownloadChanged(
            downloadManager: DownloadManager,
            download: Download,
            finalException: Exception?
        ) {
            refresh()
        }

        override fun onDownloadRemoved(downloadManager: DownloadManager, download: Download) {
            refresh()
        }

        override fun onDownloadsPausedChanged(
            downloadManager: DownloadManager,
            downloadsPaused: Boolean
        ) {
            refresh()
        }

        override fun onWaitingForRequirementsChanged(
            downloadManager: DownloadManager,
            waitingForRequirements: Boolean
        ) {
            refresh()
        }

        override fun onIdle(downloadManager: DownloadManager) {
            refresh()
        }
    }

    private fun Song.toDownloadData(): ByteArray = JSONObject().apply {
        put("videoId", videoId)
        put("title", title)
        put("artist", artist)
        put("thumbnailUrl", thumbnailUrl ?: JSONObject.NULL)
        put("durationText", durationText ?: JSONObject.NULL)
        put("artistId", artistId ?: JSONObject.NULL)
        put("albumId", albumId ?: JSONObject.NULL)
        put("albumName", albumName ?: JSONObject.NULL)
        put("isVideo", isVideo)
    }.toString().toByteArray(Charsets.UTF_8)

    private fun songFromDownloadData(data: ByteArray?): Song? = runCatching {
        val json = JSONObject(String(data ?: return null, Charsets.UTF_8))
        val videoId = json.optString("videoId").takeIf { it.isNotBlank() } ?: return null
        Song(
            videoId = videoId,
            title = json.optString("title", videoId),
            artist = json.optString("artist"),
            thumbnailUrl = json.stringOrNull("thumbnailUrl"),
            durationText = json.stringOrNull("durationText"),
            artistId = json.stringOrNull("artistId"),
            albumId = json.stringOrNull("albumId"),
            albumName = json.stringOrNull("albumName"),
            isVideo = json.optBoolean("isVideo", false)
        )
    }.getOrNull()

    private fun JSONObject.stringOrNull(name: String): String? =
        if (isNull(name)) null else optString(name).takeIf { it.isNotBlank() }
}
