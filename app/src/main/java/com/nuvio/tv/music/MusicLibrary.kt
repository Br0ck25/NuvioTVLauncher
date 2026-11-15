package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

/** One track as it is kept in the local Music library. */
@Serializable
data class StoredSong(
    val videoId: String,
    val title: String,
    val artist: String,
    val thumbnailUrl: String? = null,
    val durationText: String? = null,
    val artistId: String? = null,
    val albumId: String? = null,
    val albumName: String? = null,
    val isVideo: Boolean = false,
    val localUri: String? = null,
    val localPath: String? = null
) {
    fun toSong(): Song = Song(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        localUri = localUri,
        localPath = localPath
    )
}

@Serializable
data class StoredPlaylist(
    val id: String,
    val name: String,
    val songs: List<StoredSong> = emptyList(),
    val createdAt: Long = 0L
)

/**
 * The Music section's local library: liked songs, playlists and recently played.
 *
 * Kept on the device rather than on a YouTube Music account for this wave, so it works signed
 * out and does not touch the YouTube account. Account sync can be added over this later.
 */
object MusicLibrary {
    private const val PREFS_NAME = "nuvio_music_library"
    private const val KEY_LIKED = "liked_songs"
    private const val KEY_RECENTS = "recent_songs"
    private const val KEY_PLAYLISTS = "playlists"
    /**
     * Recently played is kept effectively unlimited for a TV listener: one entry per track and
     * old rows age out only once this many distinct songs have been played. Stored as a single
     * JSON string, so it stays bounded rather than growing with every play.
     */
    private const val MAX_RECENTS = 500

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var prefs: SharedPreferences? = null

    private val _liked = MutableStateFlow<List<StoredSong>>(emptyList())
    val liked: StateFlow<List<StoredSong>> = _liked.asStateFlow()

    private val _recents = MutableStateFlow<List<StoredSong>>(emptyList())
    val recents: StateFlow<List<StoredSong>> = _recents.asStateFlow()

    private val _playlists = MutableStateFlow<List<StoredPlaylist>>(emptyList())
    val playlists: StateFlow<List<StoredPlaylist>> = _playlists.asStateFlow()

    @Synchronized
    fun init(context: Context) {
        if (prefs != null) return
        val store = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = store
        _liked.value = decode(store.getString(KEY_LIKED, null), emptyList<StoredSong>())
        _recents.value = decode(store.getString(KEY_RECENTS, null), emptyList<StoredSong>())
        _playlists.value = decode(store.getString(KEY_PLAYLISTS, null), emptyList<StoredPlaylist>())
    }

    fun isLiked(videoId: String): Boolean = _liked.value.any { it.videoId == videoId }

    /** Returns true when the song is liked after the toggle. */
    fun toggleLike(song: Song): Boolean {
        if (song.videoId.isBlank()) return false
        val current = _liked.value
        val existing = current.firstOrNull { it.videoId == song.videoId }
        val next = if (existing != null) {
            current.filterNot { it.videoId == song.videoId }
        } else {
            listOf(song.toStored()) + current
        }
        _liked.value = next
        persist(KEY_LIKED, next)
        return existing == null
    }

    /** Moves [song] to the front of Recently Played, keeping one entry per video. */
    fun recordPlayed(song: Song) {
        if (song.videoId.isBlank()) return
        val next = (listOf(song.toStored()) + _recents.value.filterNot { it.videoId == song.videoId })
            .take(MAX_RECENTS)
        _recents.value = next
        persist(KEY_RECENTS, next)
    }

    fun createPlaylist(name: String? = null): StoredPlaylist {
        val title = name?.trim()?.takeIf { it.isNotBlank() }
            ?: "My Playlist ${_playlists.value.size + 1}"
        val playlist = StoredPlaylist(
            id = UUID.randomUUID().toString(),
            name = title,
            createdAt = System.currentTimeMillis()
        )
        _playlists.value = listOf(playlist) + _playlists.value
        persist(KEY_PLAYLISTS, _playlists.value)
        return playlist
    }

    fun addToPlaylist(playlistId: String, song: Song): Boolean {
        val current = _playlists.value
        val playlist = current.firstOrNull { it.id == playlistId } ?: return false
        if (playlist.songs.any { it.videoId == song.videoId }) return false
        val updated = playlist.copy(songs = playlist.songs + song.toStored())
        _playlists.value = current.map { if (it.id == playlistId) updated else it }
        persist(KEY_PLAYLISTS, _playlists.value)
        return true
    }

    /** Creates or replaces a local playlist by name; used by server imports. */
    fun importPlaylist(name: String, songs: List<Song>): StoredPlaylist {
        val title = name.trim().takeIf { it.isNotBlank() } ?: "Imported playlist"
        val existing = _playlists.value.firstOrNull { it.name.equals(title, ignoreCase = true) }
        val id = existing?.id ?: UUID.randomUUID().toString()
        val playlist = StoredPlaylist(
            id = id,
            name = title,
            songs = songs.distinctBy { it.videoId }.map { it.toStored() },
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        _playlists.value = listOf(playlist) + _playlists.value.filterNot { it.id == id }
        persist(KEY_PLAYLISTS, _playlists.value)
        return playlist
    }

    fun removeFromPlaylist(playlistId: String, videoId: String) {
        val current = _playlists.value
        val playlist = current.firstOrNull { it.id == playlistId } ?: return
        val updated = playlist.copy(songs = playlist.songs.filterNot { it.videoId == videoId })
        _playlists.value = current.map { if (it.id == playlistId) updated else it }
        persist(KEY_PLAYLISTS, _playlists.value)
    }

    fun deletePlaylist(playlistId: String) {
        _playlists.value = _playlists.value.filterNot { it.id == playlistId }
        persist(KEY_PLAYLISTS, _playlists.value)
    }

    fun renamePlaylist(playlistId: String, name: String) {
        val title = name.trim().takeIf { it.isNotBlank() } ?: return
        _playlists.value = _playlists.value.map {
            if (it.id == playlistId) it.copy(name = title) else it
        }
        persist(KEY_PLAYLISTS, _playlists.value)
    }

    private fun Song.toStored(): StoredSong = StoredSong(
        videoId = videoId,
        title = title,
        artist = artist,
        thumbnailUrl = thumbnailUrl,
        durationText = durationText,
        artistId = artistId,
        albumId = albumId,
        albumName = albumName,
        isVideo = isVideo,
        localUri = localUri,
        localPath = localPath
    )

    private inline fun <reified T> persist(key: String, value: T) {
        prefs?.edit()?.putString(key, json.encodeToString(value))?.apply()
    }

    private inline fun <reified T> decode(raw: String?, fallback: T): T {
        if (raw.isNullOrBlank()) return fallback
        return runCatching { json.decodeFromString<T>(raw) }.getOrDefault(fallback)
    }
}
