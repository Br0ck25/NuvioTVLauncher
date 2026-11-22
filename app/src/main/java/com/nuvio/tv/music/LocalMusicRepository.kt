package com.nuvio.tv.music

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import androidx.core.content.ContextCompat
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Audio on the device and on USB drives.
 *
 * MediaStore is asked first; TV boxes often leave removable volumes out of it, so the mounted
 * volumes under /storage and /mnt/usb are walked as well. Files found by both are de-duplicated
 * by path.
 */
object LocalMusicRepository {
    private const val TAG = "LocalMusic"
    private const val MAX_DEPTH = 6
    private const val ART_DIR = "local_music_art"

    private val audioExtensions = setOf(
        "mp3", "m4a", "m4b", "flac", "wav", "ogg", "opus", "aac", "webm", "3gp", "mp4"
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _tracks = MutableStateFlow<List<Song>>(emptyList())
    val tracks: StateFlow<List<Song>> = _tracks.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun requiredPermission(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

    fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            ContextCompat.checkSelfPermission(context, requiredPermission()) ==
            PackageManager.PERMISSION_GRANTED

    fun scan(context: Context) {
        if (_scanning.value) return
        val app = context.applicationContext
        if (!hasPermission(app)) {
            _error.value = "Storage access is needed to scan for music files."
            return
        }
        scope.launch {
            _scanning.value = true
            _error.value = null
            val result = runCatching { scanBlocking(app) }
            result.fold(
                onSuccess = { found ->
                    Log.i(TAG, "local scan: ${found.size} tracks")
                    _tracks.value = found
                },
                onFailure = {
                    Log.w(TAG, "local scan failed: ${it.message}")
                    _error.value = it.message ?: "The local music scan failed."
                }
            )
            _scanning.value = false
        }
    }

    private fun scanBlocking(context: Context): List<Song> {
        val songs = LinkedHashMap<String, Song>()
        val knownPaths = mutableSetOf<String>()

        scanMediaStore(context, songs, knownPaths)
        scanStorageRoots(context, songs, knownPaths)

        return songs.values.sortedBy { it.title.lowercase(Locale.ROOT) }
    }

    private fun scanMediaStore(
        context: Context,
        songs: MutableMap<String, Song>,
        knownPaths: MutableSet<String>
    ) {
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DISPLAY_NAME
        )
        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val dataColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val path = cursor.getString(dataColumn)
                    val title = cursor.getString(titleColumn)?.takeIf { it.isNotBlank() }
                        ?: cursor.getString(nameColumn)?.substringBeforeLast('.')
                        ?: "Unknown track"
                    val artist = cursor.getString(artistColumn)
                        ?.takeUnless { it.isBlank() || it == "<unknown>" }
                        ?: "Unknown Artist"
                    val album = cursor.getString(albumColumn)
                        ?.takeUnless { it.isBlank() || it == "<unknown>" }
                    val albumId = cursor.getLong(albumIdColumn)
                    val durationMs = cursor.getLong(durationColumn)
                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()
                    val artUri = if (albumId > 0) {
                        ContentUris.withAppendedId(
                            Uri.parse("content://media/external/audio/albumart"),
                            albumId
                        ).toString()
                    } else {
                        null
                    }
                    if (path != null) knownPaths += path
                    val key = path ?: contentUri
                    songs[key] = Song(
                        videoId = "local:$contentUri",
                        title = title,
                        artist = artist,
                        thumbnailUrl = artUri,
                        durationText = formatDuration(durationMs),
                        albumName = album,
                        localUri = contentUri,
                        localPath = path
                    )
                }
            }
        }.onFailure { Log.w(TAG, "MediaStore scan failed: ${it.message}") }
    }

    private fun scanStorageRoots(
        context: Context,
        songs: MutableMap<String, Song>,
        knownPaths: MutableSet<String>
    ) {
        val roots = mutableListOf<File>()

        runCatching {
            File("/storage").takeIf { it.isDirectory }?.listFiles()?.forEach { volume ->
                if (volume.isDirectory && volume.canRead() &&
                    volume.name != "emulated" && volume.name != "self"
                ) {
                    roots += volume
                }
            }
        }.onFailure { Log.w(TAG, "Failed to list /storage: ${it.message}") }

        runCatching {
            File("/mnt/usb").takeIf { it.isDirectory }?.listFiles()?.forEach { volume ->
                if (volume.isDirectory && volume.canRead()) roots += volume
            }
        }.onFailure { Log.w(TAG, "Failed to list /mnt/usb: ${it.message}") }

        runCatching {
            context.getExternalFilesDirs(null)?.forEach { externalDir ->
                var root = externalDir ?: return@forEach
                while (root.parentFile != null &&
                    root.parentFile?.name != "storage" &&
                    root.parentFile?.name != "Android"
                ) {
                    root = root.parentFile ?: break
                }
                if (root.canRead()) roots += root
            }
        }.onFailure { Log.w(TAG, "Failed to list app external dirs: ${it.message}") }

        // MediaStore often skips a file copied over adb or onto a USB stick on TV boxes.
        // The usual public music folders are cheap to walk and catch those files.
        runCatching {
            listOf(
                Environment.DIRECTORY_MUSIC,
                Environment.DIRECTORY_DOWNLOADS,
                Environment.DIRECTORY_PODCASTS,
                Environment.DIRECTORY_RECORDINGS
            ).forEach { directory ->
                val dir = Environment.getExternalStoragePublicDirectory(directory)
                if (dir.isDirectory && dir.canRead()) roots += dir
            }
        }.onFailure { Log.w(TAG, "Failed to list public music dirs: ${it.message}") }

        roots.distinctBy { it.absolutePath }.forEach { root ->
            scanDirectory(context, root, songs, knownPaths, MAX_DEPTH)
        }
    }

    private fun scanDirectory(
        context: Context,
        dir: File,
        songs: MutableMap<String, Song>,
        knownPaths: MutableSet<String>,
        depth: Int
    ) {
        if (depth <= 0 || !dir.canRead() || dir.name.startsWith(".")) return
        dir.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> scanDirectory(context, file, songs, knownPaths, depth - 1)
                file.isFile && file.extension.lowercase(Locale.ROOT) in audioExtensions -> {
                    val path = file.absolutePath
                    if (knownPaths.add(path)) {
                        val song = runCatching { songFromFile(context, file) }
                            .onFailure { Log.w(TAG, "Skipping $path: ${it.message}") }
                            .getOrNull()
                        if (song != null) songs[path] = song
                    }
                }
            }
        }
    }

    private fun songFromFile(context: Context, file: File): Song {
        val uri = Uri.fromFile(file).toString()
        var title = file.nameWithoutExtension
        var artist = "Unknown Artist"
        var albumName: String? = null
        var durationText: String? = null
        var artwork: String? = null

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                ?.takeIf { it.isNotBlank() }?.let { title = it }
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                ?.takeIf { it.isNotBlank() }?.let { artist = it }
            albumName = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                ?.takeUnless { it.isBlank() || it == "<unknown>" }
            durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.takeIf { it > 0 }?.let(::formatDuration)
            artwork = runCatching { retriever.embeddedPicture }.getOrNull()
                ?.let { bytes -> cacheArtwork(context, file, bytes) }
        } finally {
            runCatching { retriever.release() }
        }

        return Song(
            videoId = "local:$uri",
            title = title,
            artist = artist,
            thumbnailUrl = artwork,
            durationText = durationText,
            albumName = albumName,
            localUri = uri,
            localPath = file.absolutePath
        )
    }

    /** Saves embedded artwork once per file, so the row and notification can load it later. */
    private fun cacheArtwork(context: Context, file: File, bytes: ByteArray): String? {
        val dir = File(context.cacheDir, ART_DIR).apply { mkdirs() }
        if (!dir.isDirectory) return null
        val name = sha1(file.absolutePath) + ".img"
        val output = File(dir, name)
        if (!output.exists() || output.length() == 0L) {
            runCatching { output.writeBytes(bytes) }.getOrNull() ?: return null
        }
        return Uri.fromFile(output).toString()
    }

    private fun sha1(value: String): String =
        MessageDigest.getInstance("SHA-1")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private fun formatDuration(milliseconds: Long): String? {
        if (milliseconds <= 0L) return null
        val totalSeconds = milliseconds / 1000L
        val minutes = totalSeconds / 60L
        val seconds = totalSeconds % 60L
        val hours = minutes / 60L
        return if (hours > 0) {
            String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes % 60L, seconds)
        } else {
            String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
        }
    }
}
