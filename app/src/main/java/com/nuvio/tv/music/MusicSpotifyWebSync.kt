package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.sources.TrackMatcher
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.music.spotifyweb.SpotifySource
import com.nuvio.tv.music.spotifyweb.SpotifyTrack
import com.nuvio.tv.music.spotifyweb.SpotifyWebClient
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
    private const val KEY_MISS_CACHE = "miss_cache"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val SOURCE_PREFIX = "web:"
    private const val LIKED_LOCAL_ID = "__spotify_liked__"
    // Searches are anonymous, so start with two in flight and a short gap; a rate-limit answer
    // drops the rest of the run back to one search at a time.
    private const val MATCH_CONCURRENCY = 2
    private const val MATCH_PAUSE_MS = 300L
    private const val SPOTIFY_FETCH_CONCURRENCY = 3
    /** Do not hold a whole large library in memory before matching starts. */
    private const val SPOTIFY_PREFETCH_LIMIT = 12
    private const val MAX_PACE_MS = 2_000L
    private const val SEARCH_TIMEOUT_MS = 8_000L
    private const val RATE_LIMIT_WAIT_MS = 20_000L
    /** A track that YouTube Music had no result for is not searched again for this long. */
    private const val MISS_TTL_MS = 7L * 24 * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(SpotifyWebSyncState())
    val state: StateFlow<SpotifyWebSyncState> = _state.asStateFlow()

    /** Adaptive gap between search chunks; raised when YouTube pushes back. */
    @Volatile
    private var paceMs = MATCH_PAUSE_MS

    /** Searches in flight for the next chunk. Drops back to one on a rate limit. */
    @Volatile
    private var matchConcurrency = MATCH_CONCURRENCY
    private var minPaceMs = MATCH_PAUSE_MS

    private data class PendingTrack(
        val trackKey: String,
        val query: String,
        val target: TrackMatcher.Target,
    )

    /** A search either found a song, definitively had none, or failed in a way worth retrying. */
    private data class SongMatch(
        val song: Song?,
        val definitive: Boolean,
    )

    private data class SyncTotals(
        val playlists: Int,
        val added: Int,
        val seen: Int,
        val liked: Int
    )

    fun sync(context: Context) {
        if (_state.value.busy) return
        val app = context.applicationContext
        _state.value = SpotifyWebSyncState(busy = true, progress = app.withAppLocale().getString(R.string.music_spotify_reading_library))
        scope.launch {
            val cookie = MusicSpotifyWebSession.cookie(app)
            if (cookie.isBlank()) {
                _state.value = SpotifyWebSyncState(error = app.withAppLocale().getString(R.string.music_spotify_link_first))
                return@launch
            }
            val result = runCatching {
                // Music has its own YouTube Music account choice; searches must use it.
                runCatching {
                    com.nuvio.tv.youtube.YouTubeAccountScopes.engine(
                        app,
                        com.nuvio.tv.youtube.AccountScope.MUSIC
                    )
                }
                MusicLibrary.init(app)
                matchConcurrency = MATCH_CONCURRENCY
                minPaceMs = MATCH_PAUSE_MS
                paceMs = minPaceMs
                val client = SpotifyWebClient.connect(cookie, requireSignedIn = true).getOrThrow()
                val account = client.account().getOrThrow()
                val accountKey = account.name.ifBlank { "spotify" }
                val store = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                val playlistMap = loadStringMap(store.getString(KEY_PLAYLIST_MAP, null))
                val trackMap = loadTrackMap(store.getString(KEY_TRACK_MAP, null))
                val playlistCounts = loadIntMap(store.getString(KEY_PLAYLIST_COUNTS, null))
                val missCache = loadLongMap(store.getString(KEY_MISS_CACHE, null))
                missCache.entries.removeAll { System.currentTimeMillis() - it.value > MISS_TTL_MS }
                val localIndex = buildLocalIndex()
                val queryHits = ConcurrentHashMap<String, Song>()
                var playlistsSynced = 0
                var tracksSeen = 0
                var tracksAdded = 0
                var likedAdded = 0

                // The Spotify pages themselves are cheap but sequential. Fetch the changed
                // playlists a few at a time while the library is being prepared, so matching
                // does not wait for one round trip per playlist.
                val prefetched = ConcurrentHashMap<String, com.nuvio.tv.music.spotifyweb.SpotifyPlaylist>()
                val fetchTargets = account.library.filter { entry ->
                    val isLiked = entry.source is SpotifySource.Liked
                    if (isLiked) return@filter true
                    val sourceKey = "$SOURCE_PREFIX$accountKey:${entry.source.key}"
                    val mappedLocal = playlistMap[sourceKey]
                    val localExists = mappedLocal != null &&
                        MusicLibrary.playlists.value.any { it.id == mappedLocal }
                    val spotifyCount = entry.count
                    !(localExists && spotifyCount != null && spotifyCount > 0 &&
                        playlistCounts[sourceKey] == spotifyCount)
                }.take(SPOTIFY_PREFETCH_LIMIT)
                for (chunk in fetchTargets.chunked(SPOTIFY_FETCH_CONCURRENCY)) {
                    val fetched = coroutineScope {
                        chunk.map { entry ->
                            async {
                                val sourceKey = "$SOURCE_PREFIX$accountKey:${entry.source.key}"
                                val playlist = if (entry.source is SpotifySource.Liked) {
                                    client.liked()
                                } else {
                                    client.playlist(entry.source.key)
                                }
                                sourceKey to playlist
                            }
                        }.awaitAll()
                    }
                    fetched.forEach { (sourceKey, playlist) ->
                        playlist.getOrNull()?.let { prefetched[sourceKey] = it }
                    }
                }

                account.library.forEachIndexed { index, entry ->
                    val isLiked = entry.source is SpotifySource.Liked
                    val sourceKey = "$SOURCE_PREFIX$accountKey:${entry.source.key}"
                    val name = if (isLiked) {
                        "Spotify Liked Songs"
                    } else {
                        entry.name.ifBlank { "Spotify playlist" }
                    }
                    _state.value = _state.value.copy(
                        progress = app.withAppLocale().getString(R.string.music_spotify_playlist_progress, name, index + 1, account.library.size)
                    )

                    // Earlier builds put Spotify Liked Songs in a local playlist. Move those
                    // songs into the app's real Liked Songs and remove the old playlist.
                    if (isLiked) {
                        val oldLikedPlaylist = playlistMap[sourceKey]
                        if (oldLikedPlaylist != null) {
                            val old = MusicLibrary.playlists.value
                                .firstOrNull { it.id == oldLikedPlaylist }
                            if (old != null) {
                                withContext(Dispatchers.Main) {
                                    MusicLibrary.addLikedSongs(old.songs.map { it.toSong() })
                                    MusicLibrary.deletePlaylist(old.id)
                                }
                                playlistMap.remove(sourceKey)
                                store.edit()
                                    .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                                    .apply()
                            }
                        }
                    }

                    val mappedLocal = playlistMap[sourceKey]
                    val localExists = !isLiked && mappedLocal != null &&
                        MusicLibrary.playlists.value.any { it.id == mappedLocal }
                    val spotifyCount = entry.count
                    // Unchanged on Spotify and already local: no need to fetch or search it again.
                    if (!isLiked && localExists && spotifyCount != null && spotifyCount > 0 &&
                        playlistCounts[sourceKey] == spotifyCount
                    ) {
                        playlistsSynced++
                        return@forEachIndexed
                    }

                    val playlist = prefetched.remove(sourceKey) ?: if (isLiked) {
                        client.liked().getOrThrow()
                    } else {
                        client.playlist(entry.source.key).getOrThrow()
                    }
                    if (playlist.tracks.isEmpty()) return@forEachIndexed

                    val localId = if (isLiked) {
                        LIKED_LOCAL_ID
                    } else {
                        ensureLocalPlaylist(name, sourceKey, playlistMap)
                    }
                    if (!isLiked) {
                        store.edit()
                            .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                            .apply()
                    }

                    val existing = if (isLiked) {
                        withContext(Dispatchers.Main) {
                            MusicLibrary.liked.value.mapTo(mutableSetOf()) { it.videoId }
                        }
                    } else {
                        withContext(Dispatchers.Main) {
                            MusicLibrary.playlists.value
                                .firstOrNull { it.id == localId }
                                ?.songs
                                ?.mapTo(mutableSetOf()) { it.videoId }
                                ?: mutableSetOf()
                        }
                    }
                    val known = trackMap.getOrPut(localId) { mutableMapOf() }
                    var addedHere = 0
                    var handled = 0
                    val pending = ArrayList<PendingTrack>()
                    val likedBatch = ArrayList<Song>()

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
                        val localSong = localIndex[indexKey]
                        if (localSong != null) {
                            known[key] = localSong.videoId
                            if (existing.add(localSong.videoId)) {
                                if (isLiked) {
                                    likedBatch += localSong
                                } else {
                                    withContext(Dispatchers.Main) {
                                        MusicLibrary.addToPlaylist(localId, localSong)
                                    }
                                }
                                addedHere++
                            }
                            handled++
                            return@forEach
                        }
                        val query = listOf(track.name, track.artistLine)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        val missedAt = missCache[key]
                        if (missedAt != null && System.currentTimeMillis() - missedAt <= MISS_TTL_MS) {
                            handled++
                            return@forEach
                        }
                        val target = Song(
                            videoId = "",
                            title = track.name,
                            artist = track.artistLine,
                            thumbnailUrl = null,
                            durationText = durationTextOf(track.durationMs),
                            albumName = null,
                        ).let(TrackMatcher::targetOf)
                        val searchQuery = TrackMatcher.queries(target).firstOrNull()
                            ?.takeIf { it.isNotBlank() }
                            ?: query
                        pending += PendingTrack(key, searchQuery, target)
                    }

                    var cursor = 0
                    while (cursor < pending.size) {
                        val chunk = pending.subList(
                            cursor,
                            (cursor + matchConcurrency).coerceAtMost(pending.size)
                        )
                        cursor += chunk.size
                        val found = coroutineScope {
                            chunk.map { item ->
                                async {
                                    val cached = queryHits[item.trackKey]
                                    if (cached != null) {
                                        item to SongMatch(cached, definitive = true)
                                    } else {
                                        item to searchSong(item.query, item.target, app)
                                    }
                                }
                            }.awaitAll()
                        }
                        found.forEach { (item, match) ->
                            val song = match.song
                            if (song != null) {
                                missCache.remove(item.trackKey)
                                queryHits[item.trackKey] = song
                                if (existing.add(song.videoId)) {
                                    if (isLiked) {
                                        likedBatch += song
                                    } else {
                                        withContext(Dispatchers.Main) {
                                            MusicLibrary.addToPlaylist(localId, song)
                                        }
                                    }
                                    addedHere++
                                }
                                known[item.trackKey] = song.videoId
                            } else if (match.definitive) {
                                missCache[item.trackKey] = System.currentTimeMillis()
                            }
                        }
                        if (isLiked && likedBatch.isNotEmpty()) {
                            withContext(Dispatchers.Main) {
                                MusicLibrary.addLikedSongs(likedBatch)
                            }
                            likedBatch.clear()
                        }
                        handled += chunk.size
                        if (handled / 25 != (handled - chunk.size) / 25) {
                            // Keep matches and misses if the app is closed mid-playlist.
                            store.edit()
                                .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                                .putString(KEY_MISS_CACHE, JSONObject(missCache).toString())
                                .apply()
                        }
                        _state.value = _state.value.copy(
                            progress = app.withAppLocale().getString(R.string.music_spotify_track_progress, name, handled.coerceAtMost(playlist.tracks.size), playlist.tracks.size)
                        )
                        // Back off smoothly when YouTube pushes back, and recover slowly
                        // once it is answering normally again.
                        paceMs = (paceMs * 0.9).toLong().coerceAtLeast(minPaceMs)
                        delay(paceMs)
                    }
                    if (isLiked && likedBatch.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            MusicLibrary.addLikedSongs(likedBatch)
                        }
                    }

                    if (isLiked) {
                        likedAdded += addedHere
                    } else {
                        tracksAdded += addedHere
                        playlistsSynced++
                    }
                    if (!isLiked && spotifyCount != null && spotifyCount > 0) {
                        playlistCounts[sourceKey] = spotifyCount
                    }
                    store.edit()
                        .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                        .putString(KEY_PLAYLIST_COUNTS, JSONObject(playlistCounts).toString())
                        .putString(KEY_MISS_CACHE, JSONObject(missCache).toString())
                        .apply()
                }

                store.edit()
                    .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                    .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                    .putString(KEY_PLAYLIST_COUNTS, JSONObject(playlistCounts).toString())
                    .putString(KEY_MISS_CACHE, JSONObject(missCache).toString())
                    .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                    .apply()
                SyncTotals(
                    playlists = playlistsSynced,
                    added = tracksAdded,
                    seen = tracksSeen,
                    liked = likedAdded
                )
            }

            result.fold(
                onSuccess = { totals ->
                    _state.value = SpotifyWebSyncState(
                        busy = false,
                        result = "Added ${totals.liked} liked songs and ${totals.added} playlist " +
                            "tracks from ${totals.playlists} Spotify playlists."
                    )
                },
                onFailure = { error ->
                    _state.value = SpotifyWebSyncState(
                        busy = false,
                        error = error.message ?: app.withAppLocale().getString(R.string.music_spotify_library_sync_failed)
                    )
                }
            )
        }
    }

    private suspend fun searchSong(
        query: String,
        target: TrackMatcher.Target,
        app: Context,
    ): SongMatch {
        repeat(2) { attempt ->
            val result = runCatching {
                withTimeoutOrNull(SEARCH_TIMEOUT_MS) {
                    YtMusicRepository.search(query, SearchFilter.SONGS)
                }
            }.getOrNull() ?: return SongMatch(song = null, definitive = false)
            val rows = result.getOrNull()
            if (rows != null) {
                val candidates = rows.asSequence()
                    .mapNotNull { item ->
                        when (item) {
                            is SearchResult.TopTrack -> item.song
                            is SearchResult.Track -> item.song
                            else -> null
                        }
                    }
                    .filter { it.videoId.isNotBlank() }
                    .toList()
                // Prefer the recording the matcher is confident about; fall back to the
                // first catalogue track rather than leaving a playable track out.
                val song = TrackMatcher.best(candidates, target)
                    ?: candidates.firstOrNull { !it.isVideo }
                    ?: candidates.firstOrNull()
                return SongMatch(song = song, definitive = true)
            }
            val error = result.exceptionOrNull()
            if (isRateLimit(error)) {
                // Slow the whole run down rather than hammering the same endpoint.
                matchConcurrency = 1
                paceMs = (paceMs * 2).coerceAtMost(MAX_PACE_MS)
                if (attempt == 0) {
                    _state.value = _state.value.copy(
                        progress = app.withAppLocale().getString(R.string.music_youtube_music_busy)
                    )
                    delay(RATE_LIMIT_WAIT_MS)
                    return@repeat
                }
                throw IllegalStateException(app.withAppLocale().getString(R.string.music_youtube_music_limited))
            }
            // A timeout or a transient network error is not a definitive "not on YouTube
            // Music", so do not put it in the miss cache.
            return SongMatch(song = null, definitive = false)
        }
        return SongMatch(song = null, definitive = false)
    }

    private fun isRateLimit(error: Throwable?): Boolean {
        val message = error?.message.orEmpty()
        val lower = message.lowercase()
        return message.contains("403") || message.contains("429") ||
            lower.contains("automated queries") || lower.contains("unusual traffic") ||
            lower.contains("too many requests") || lower.contains("rate limit") ||
            message.contains("<html", ignoreCase = true)
    }

    /** Tracks already in this device's library, so they are added without a search. */
    private fun buildLocalIndex(): Map<String, Song> {
        val index = HashMap<String, Song>()
        fun add(stored: StoredSong) {
            if (stored.title.isBlank() || stored.artist.isBlank() || stored.videoId.isBlank()) return
            index.putIfAbsent(localIndexKey(stored.title, stored.artist), stored.toSong())
        }
        MusicLibrary.liked.value.forEach(::add)
        MusicLibrary.recents.value.forEach(::add)
        MusicLibrary.playlists.value.forEach { playlist ->
            playlist.songs.forEach(::add)
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

    private fun durationTextOf(durationMs: Long): String? {
        if (durationMs <= 0L) return null
        val seconds = (durationMs / 1000L).toInt()
        return String.format(java.util.Locale.ROOT, "%d:%02d", seconds / 60, seconds % 60)
    }

    internal fun localIndexKey(title: String, artist: String): String =
        "${normalizeMatchTitle(title)}|${normalizeMatchArtist(artist)}"

    /**
     * Spotify and YouTube spell the same recording differently: "(Official Audio)", "Remastered
     * 2011", "feat. ..." and punctuation are common on one and absent on the other. The index key
     * drops those so an already-known track does not need a search.
     */
    internal fun normalizeMatchTitle(value: String): String {
        var text = value.lowercase().trim()
        text = text.replace("'", "").replace("\u2019", "")
        text = text.replace(Regex("\\([^)]*\\)|\\[[^]]*\\]"), " ")
        text = text.replace(
            Regex(
                "\\s+-\\s+((\\d{4}\\s+)?remaster(ed)?|official[^-]*|audio|video|lyrics|live|" +
                    "radio edit|single version)(\\s+\\d{4})?\\s*$"
            ),
            " "
        )
        text = text.replace(Regex("\\b(feat\\.?|ft\\.?|featuring)\\b.*$"), " ")
        return text.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

    internal fun normalizeMatchArtist(value: String): String {
        var text = value.lowercase().trim()
        text = text.replace("'", "").replace("\u2019", "")
        text = text.substringBefore(",").substringBefore(" & ")
        text = text.substringBefore(" feat").substringBefore(" ft.")
        return text.replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    }

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

    private fun loadLongMap(raw: String?): MutableMap<String, Long> {
        val result = mutableMapOf<String, Long>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { key ->
            val value = json.optLong(key, 0L)
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
