package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.nuvio.tv.music.spotifyweb.SpotifySource
import com.nuvio.tv.music.spotifyweb.SpotifyTrack
import com.nuvio.tv.music.spotifyweb.SpotifyWebClient
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

data class SpotifyWebSyncState(
    val busy: Boolean = false,
    val progress: String? = null,
    val result: String? = null,
    val error: String? = null
)

/**
 * Copies the linked Spotify account's playlists and Liked Songs into the local Music library,
 * using the desktop-style `sp_dc` web-player client. Matching is the same YouTube Music search
 * the public playlist import uses; re-syncing reuses the local playlists made last time.
 */
object MusicSpotifyWebSync {
    private const val PREFS = "nuvio_music_spotify_web_sync"
    private const val KEY_PLAYLIST_MAP = "playlist_map"
    private const val KEY_TRACK_MAP = "track_map"
    private const val KEY_PLAYLIST_COUNTS = "playlist_counts"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val SOURCE_PREFIX = "web:"
    private const val MATCH_CONCURRENCY = 2
    private const val SEARCH_TIMEOUT_MS = 8_000L
    private const val MATCH_PAUSE_MS = 300L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SpotifyWebSyncState())
    val state: StateFlow<SpotifyWebSyncState> = _state.asStateFlow()

    private data class PendingTrack(
        val trackKey: String,
        val query: String
    )

    fun sync(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        _state.value = SpotifyWebSyncState(busy = true, progress = "Reading your Spotify library…")
        scope.launch {
            val cookie = MusicSpotifyWebSession.cookie(app)
            if (cookie.isBlank()) {
                _state.value = SpotifyWebSyncState(error = "Link Spotify first.")
                return@launch
            }
            val result = runCatching {
                MusicLibrary.init(app)
                val client = SpotifyWebClient.connect(cookie, requireSignedIn = true).getOrThrow()
                val account = client.account().getOrThrow()
                val accountKey = account.name.ifBlank { "spotify" }
                val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val playlistMap = loadStringMap(store.getString(KEY_PLAYLIST_MAP, null))
                val trackMap = loadTrackMap(store.getString(KEY_TRACK_MAP, null))
                val playlistCounts = loadIntMap(store.getString(KEY_PLAYLIST_COUNTS, null))
                val localIndex = buildLocalIndex()
                val queryHits = ConcurrentHashMap<String, Song>()
                val queryMisses = Collections.synchronizedSet(HashSet<String>())
                var playlistsSynced = 0
                var tracksSeen = 0
                var tracksAdded = 0

                account.library.forEachIndexed { index, entry ->
                    val sourceKey = "$SOURCE_PREFIX$accountKey:${entry.source.key}"
                    val name = if (entry.source is SpotifySource.Liked) {
                        "Spotify Liked Songs"
                    } else {
                        entry.name.ifBlank { "Spotify playlist" }
                    }
                    _state.value = _state.value.copy(
                        progress = "$name (${index + 1} of ${account.library.size})…"
                    )

                    val mappedLocal = playlistMap[sourceKey]
                    val localExists = mappedLocal != null &&
                        MusicLibrary.playlists.value.any { it.id == mappedLocal }
                    val spotifyCount = entry.count
                    // Unchanged on Spotify and already local: no need to fetch or search it again.
                    if (localExists && spotifyCount != null && spotifyCount > 0 &&
                        playlistCounts[sourceKey] == spotifyCount
                    ) {
                        playlistsSynced++
                        return@forEachIndexed
                    }

                    val playlist = if (entry.source is SpotifySource.Liked) {
                        client.liked().getOrThrow()
                    } else {
                        client.playlist(entry.source.key).getOrThrow()
                    }
                    if (playlist.tracks.isEmpty()) return@forEachIndexed

                    val localId = ensureLocalPlaylist(name, sourceKey, playlistMap)
                    store.edit()
                        .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                        .apply()

                    val existing = withContext(Dispatchers.Main) {
                        MusicLibrary.playlists.value
                            .firstOrNull { it.id == localId }
                            ?.songs
                            ?.mapTo(mutableSetOf()) { it.videoId }
                            ?: mutableSetOf()
                    }
                    val known = trackMap.getOrPut(localId) { mutableMapOf() }
                    var addedHere = 0
                    var handled = 0
                    val pending = ArrayList<PendingTrack>()

                    // Cheap pass first: tracks already mapped or already in the library do not
                    // need a YouTube search at all.
                    playlist.tracks.forEach { track ->
                        tracksSeen++
                        if (track.local || track.name.isBlank()) {
                            handled++
                            return@forEach
                        }
                        val key = trackKey(track)
                        val knownVideoId = known[key]
                        if (knownVideoId != null && knownVideoId in existing) {
                            handled++
                            return@forEach
                        }
                        val indexKey = localIndexKey(track.name, track.artistLine)
                        val localVideoId = localIndex[indexKey]
                        if (localVideoId != null) {
                            known[key] = localVideoId
                            if (existing.add(localVideoId)) {
                                withContext(Dispatchers.Main) {
                                    MusicLibrary.addToPlaylist(
                                        localId,
                                        Song(
                                            videoId = localVideoId,
                                            title = track.name,
                                            artist = track.artistLine,
                                            thumbnailUrl = track.art,
                                            durationText = null
                                        )
                                    )
                                }
                                addedHere++
                            }
                            handled++
                            return@forEach
                        }
                        val query = listOf(track.name, track.artistLine)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        pending += PendingTrack(key, query)
                    }

                    // Search the remaining tracks a few at a time; a large playlist used to
                    // wait for every track one by one.
                    pending.chunked(MATCH_CONCURRENCY).forEach { chunk ->
                        val found = coroutineScope {
                            chunk.map { item ->
                                async {
                                    val cached = queryHits[item.query]
                                    if (cached != null) return@async item to cached
                                    if (item.query in queryMisses) return@async item to null
                                    val song = searchSong(item.query)
                                    if (song == null) queryMisses.add(item.query)
                                    else queryHits[item.query] = song
                                    item to song
                                }
                            }.awaitAll()
                        }
                        found.forEach { (item, song) ->
                            if (song != null) {
                                if (existing.add(song.videoId)) {
                                    withContext(Dispatchers.Main) {
                                        MusicLibrary.addToPlaylist(localId, song)
                                    }
                                    addedHere++
                                }
                                known[item.trackKey] = song.videoId
                            }
                        }
                        handled += chunk.size
                        _state.value = _state.value.copy(
                            progress = "$name: ${handled.coerceAtMost(playlist.tracks.size)} of ${playlist.tracks.size}…"
                        )
                        delay(MATCH_PAUSE_MS)
                    }

                    tracksAdded += addedHere
                    playlistsSynced++
                    if (spotifyCount != null && spotifyCount > 0) {
                        playlistCounts[sourceKey] = spotifyCount
                    }
                    store.edit()
                        .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                        .putString(KEY_PLAYLIST_COUNTS, JSONObject(playlistCounts).toString())
                        .apply()
                }

                store.edit()
                    .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                    .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                    .putString(KEY_PLAYLIST_COUNTS, JSONObject(playlistCounts).toString())
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply()
                Triple(playlistsSynced, tracksAdded, tracksSeen)
            }

            result.fold(
                onSuccess = { (playlists, added, seen) ->
                    _state.value = SpotifyWebSyncState(
                        busy = false,
                        result = "Imported $added of $seen tracks from $playlists Spotify playlists."
                    )
                },
                onFailure = { error ->
                    _state.value = SpotifyWebSyncState(
                        busy = false,
                        error = error.message ?: "The Spotify library couldn't be synced."
                    )
                }
            )
        }
    }

    private suspend fun searchSong(query: String): Song? {
        val result = runCatching {
            withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                YtMusicRepository.search(query, SearchFilter.SONGS)
            }
        }.getOrNull() ?: return null
        val found = result.getOrElse { error ->
            val message = error.message.orEmpty()
            val lower = message.lowercase()
            if (message.contains("403") || lower.contains("automated queries") ||
                message.contains("<html", ignoreCase = true) || lower.contains("unusual traffic")
            ) {
                throw IllegalStateException(
                    "YouTube Music is temporarily limiting requests. Wait a few minutes, then " +
                        "sync again. The tracks already added are safe."
                )
            }
            return null
        }
        return found.asSequence()
            .mapNotNull { item ->
                when (item) {
                    is SearchResult.TopTrack -> item.song
                    is SearchResult.Track -> item.song
                    else -> null
                }
            }
            .firstOrNull { it.videoId.isNotBlank() }
    }

    /** Tracks already in this device's library, so they are added without a search. */
    private fun buildLocalIndex(): Map<String, String> {
        val index = HashMap<String, String>()
        fun add(title: String, artist: String, videoId: String) {
            if (title.isBlank() || videoId.isBlank()) return
            index.putIfAbsent(localIndexKey(title, artist), videoId)
        }
        MusicLibrary.liked.value.forEach { add(it.title, it.artist, it.videoId) }
        MusicLibrary.recents.value.forEach { add(it.title, it.artist, it.videoId) }
        MusicLibrary.playlists.value.forEach { playlist ->
            playlist.songs.forEach { add(it.title, it.artist, it.videoId) }
        }
        return index
    }

    private suspend fun ensureLocalPlaylist(
        name: String,
        sourceKey: String,
        playlistMap: MutableMap<String, String>
    ): String {
        val mapped = playlistMap[sourceKey]
        if (mapped != null && MusicLibrary.playlists.value.any { it.id == mapped }) return mapped
        val playlist = withContext(Dispatchers.Main) {
            MusicLibrary.createPlaylist(name)
        }
        playlistMap[sourceKey] = playlist.id
        return playlist.id
    }

    fun reset() {
        _state.value = SpotifyWebSyncState()
    }

    private fun trackKey(track: SpotifyTrack): String =
        "${track.name.lowercase().trim()}|${track.artistLine.lowercase().trim()}|${track.durationMs}"

    private fun localIndexKey(title: String, artist: String): String =
        "${title.lowercase().trim()}|${artist.lowercase().trim()}"

    private fun loadStringMap(raw: String?): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { key ->
            json.optString(key).takeIf { it.isNotBlank() }?.let { result[key] = it }
        }
        return result
    }

    private fun loadIntMap(raw: String?): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { key ->
            val value = json.optInt(key, 0)
            if (value > 0) result[key] = value
        }
        return result
    }

    private fun loadTrackMap(raw: String?): MutableMap<String, MutableMap<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { localId ->
            val tracks = json.optJSONObject(localId) ?: return@forEach
            val map = mutableMapOf<String, String>()
            tracks.keys().forEach { key ->
                tracks.optString(key).takeIf { it.isNotBlank() }?.let { map[key] = it }
            }
            result[localId] = map
        }
        return result
    }
}
