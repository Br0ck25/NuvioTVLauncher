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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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
    private const val KEY_LAST_SYNC = "last_sync"
    private const val SOURCE_PREFIX = "web:"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SpotifyWebSyncState())
    val state: StateFlow<SpotifyWebSyncState> = _state.asStateFlow()

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

                    playlist.tracks.forEachIndexed { trackIndex, track ->
                        tracksSeen++
                        if (track.local || track.name.isBlank()) return@forEachIndexed
                        val trackKey = trackKey(track)
                        val knownVideoId = known[trackKey]
                        if (knownVideoId != null && knownVideoId in existing) return@forEachIndexed

                        val query = listOf(track.name, track.artistLine)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        val found = runCatching {
                            withTimeoutOrNull(12_000L) {
                                YtMusicRepository.search(query, SearchFilter.SONGS)
                            }
                        }.getOrNull()?.getOrNull()
                        val song = found.orEmpty()
                            .asSequence()
                            .mapNotNull { item ->
                                when (item) {
                                    is SearchResult.TopTrack -> item.song
                                    is SearchResult.Track -> item.song
                                    else -> null
                                }
                            }
                            .firstOrNull { it.videoId.isNotBlank() }

                        if (song != null) {
                            if (existing.add(song.videoId)) {
                                withContext(Dispatchers.Main) {
                                    MusicLibrary.addToPlaylist(localId, song)
                                }
                                addedHere++
                            }
                            known[trackKey] = song.videoId
                        }
                        if (trackIndex % 10 == 9) {
                            _state.value = _state.value.copy(
                                progress = "$name: ${trackIndex + 1} of ${playlist.tracks.size}…"
                            )
                        }
                    }
                    tracksAdded += addedHere
                    playlistsSynced++
                    store.edit()
                        .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                        .apply()
                }

                store.edit()
                    .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                    .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
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

    private fun loadStringMap(raw: String?): MutableMap<String, String> {
        val result = mutableMapOf<String, String>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { key ->
            json.optString(key).takeIf { it.isNotBlank() }?.let { result[key] = it }
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
