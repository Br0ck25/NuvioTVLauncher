package com.nuvio.tv.music

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class SpotifyImportResult(
    val playlistName: String,
    val imported: Int,
    val matched: Int,
    val total: Int
)

data class SpotifySyncResult(
    val playlists: Int,
    val tracks: Int,
    val matched: Int
)

/**
 * Imports a public Spotify playlist by reading the track list from its embed page. Each entry is
 * matched to a YouTube Music song and written into a normal local playlist.
 *
 * No Spotify account, API key or token is involved; this only reads what the public embed page
 * already exposes.
 */
object MusicSpotifyImport {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .build()

    private val idPattern = Regex("""(?:playlist/|playlist:)([A-Za-z0-9]+)""")

    suspend fun import(
        context: Context,
        playlistUrl: String,
        onProgress: suspend (done: Int, total: Int) -> Unit
    ): Result<SpotifyImportResult> = withContext(Dispatchers.IO) {
        runCatching {
            val id = idPattern.find(playlistUrl.trim())?.groupValues?.getOrNull(1)
                ?: error("Paste a Spotify playlist link, for example https://open.spotify.com/playlist/…")
            val html = fetch("https://open.spotify.com/embed/playlist/$id")
            val entity = parseEntity(html)
            val name = entity.optString("name").ifBlank { "Spotify playlist" }
            val tracks = entity.optJSONArray("trackList") ?: error("This playlist has no tracks.")
            val total = tracks.length().coerceAtMost(100)
            val matchedSongs = mutableListOf<Song>()
            for (i in 0 until total) {
                val track = tracks.optJSONObject(i) ?: continue
                val title = track.optString("title").takeIf { it.isNotBlank() } ?: continue
                val artist = track.optString("subtitle").takeIf { it.isNotBlank() }.orEmpty()
                val query = listOf(title, artist).filter { it.isNotBlank() }.joinToString(" ")
                val found = runCatching {
                    withTimeoutOrNull(12_000L) {
                        YtMusicRepository.search(query, SearchFilter.SONGS)
                    }
                }.getOrNull()?.getOrNull()
                val song = found.orEmpty()
                    .asSequence()
                    .mapNotNull { result ->
                        when (result) {
                            is SearchResult.TopTrack -> result.song
                            is SearchResult.Track -> result.song
                            else -> null
                        }
                    }
                    .firstOrNull { it.videoId.isNotBlank() }
                if (song != null) matchedSongs += song
                onProgress(i + 1, total)
            }
            if (matchedSongs.isEmpty()) {
                error("None of those tracks could be matched in the music catalogue.")
            }
            val playlist = withContext(Dispatchers.Main) {
                MusicLibrary.createPlaylist(name)
            }
            val added = withContext(Dispatchers.Main) {
                matchedSongs.count { MusicLibrary.addToPlaylist(playlist.id, it) }
            }
            if (added == 0) {
                withContext(Dispatchers.Main) { MusicLibrary.deletePlaylist(playlist.id) }
                error("The playlist was already imported.")
            }
            SpotifyImportResult(
                playlistName = playlist.name,
                imported = added,
                matched = matchedSongs.size,
                total = total
            )
        }
    }

    private fun fetch(url: String): String {
        val request = Request.Builder()
            .url(url)
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                    "(KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
            )
            .build()
        client.newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Spotify returned HTTP ${response.code}." }
            return response.body?.string().orEmpty()
        }
    }

    private fun parseEntity(html: String): JSONObject {
        val match = Regex(
            """<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        ).find(html) ?: error("Spotify didn't return playlist details.")
        val root = JSONObject(match.groupValues[1])
        return root.getJSONObject("props")
            .getJSONObject("pageProps")
            .getJSONObject("state")
            .getJSONObject("data")
            .getJSONObject("entity")
    }

    // ---------------------------------------------------------------- account sync

    private const val SYNC_PREFS = "nuvio_music_spotify_sync"
    private const val KEY_PLAYLIST_MAP = "playlist_map"
    private const val KEY_TRACK_MAP = "track_map"
    private const val KEY_LAST_SYNC = "last_sync"
    private const val LIKED_PLAYLIST_ID = "__liked__"
    private const val LIKED_PLAYLIST_NAME = "Spotify Liked Songs"

    private data class SyncTarget(
        val spotifyId: String,
        val name: String,
        val tracks: List<SpotifyTrack>
    )

    /**
     * Reads the linked account's playlists and Liked Songs and adds the tracks that can be
     * matched to the local Music library. Re-syncing updates the playlists made by an earlier
     * sync instead of creating new ones, and never removes tracks the user added by hand.
     */
    suspend fun syncAccount(
        context: Context,
        accessToken: String,
        onProgress: suspend (String) -> Unit
    ): Result<SpotifySyncResult> = withContext(Dispatchers.IO) {
        runCatching {
            MusicLibrary.init(context)
            val app = context.applicationContext
            val store = app.getSharedPreferences(SYNC_PREFS, Context.MODE_PRIVATE)
            val playlistMap = loadStringMap(store.getString(KEY_PLAYLIST_MAP, null))
            val trackMap = loadTrackMap(store.getString(KEY_TRACK_MAP, null))

            val profile = runCatching { MusicSpotifyApi.profile(accessToken) }.getOrNull()
            onProgress("Reading ${profile?.displayName ?: "your Spotify"} library…")
            val playlists = MusicSpotifyApi.playlists(accessToken)
            val liked = MusicSpotifyApi.likedTracks(accessToken)
            val targets = buildList {
                if (liked.isNotEmpty()) add(SyncTarget(LIKED_PLAYLIST_ID, LIKED_PLAYLIST_NAME, liked))
                playlists.forEach { playlist ->
                    val tracks = MusicSpotifyApi.playlistTracks(accessToken, playlist.id)
                    if (tracks.isNotEmpty()) add(SyncTarget(playlist.id, playlist.name, tracks))
                }
            }
            if (targets.isEmpty()) {
                error("Spotify didn't return any playlists or Liked Songs.")
            }

            var playlistsSynced = 0
            var tracksSeen = 0
            var tracksAdded = 0
            targets.forEachIndexed { index, target ->
                onProgress("${target.name} (${index + 1} of ${targets.size})…")
                val localId = ensureLocalPlaylist(target, playlistMap)
                store.edit().putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString()).apply()

                val existing = withContext(Dispatchers.Main) {
                    MusicLibrary.playlists.value
                        .firstOrNull { it.id == localId }
                        ?.songs
                        ?.mapTo(mutableSetOf()) { it.videoId }
                        ?: mutableSetOf()
                }
                val knownTracks = trackMap.getOrPut(localId) { mutableMapOf() }
                var addedHere = 0

                target.tracks.forEachIndexed { trackIndex, track ->
                    tracksSeen++
                    if (track.isLocal || track.id.isBlank() || track.title.isBlank()) return@forEachIndexed
                    val knownVideoId = knownTracks[track.id]
                    if (knownVideoId != null && knownVideoId in existing) return@forEachIndexed

                    val query = listOf(track.title, track.artists)
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    val found = runCatching {
                        withTimeoutOrNull(12_000L) {
                            YtMusicRepository.search(query, SearchFilter.SONGS)
                        }
                    }.getOrNull()?.getOrNull()
                    val song = found.orEmpty()
                        .asSequence()
                        .mapNotNull { result ->
                            when (result) {
                                is SearchResult.TopTrack -> result.song
                                is SearchResult.Track -> result.song
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
                        knownTracks[track.id] = song.videoId
                    }
                    if (trackIndex % 10 == 9) {
                        onProgress(
                            "${target.name}: ${trackIndex + 1} of ${target.tracks.size}…"
                        )
                    }
                }

                tracksAdded += addedHere
                playlistsSynced++
                store.edit().putString(KEY_TRACK_MAP, JSONObject(trackMap).toString()).apply()
            }

            store.edit()
                .putString(KEY_PLAYLIST_MAP, JSONObject(playlistMap).toString())
                .putString(KEY_TRACK_MAP, JSONObject(trackMap).toString())
                .putLong(KEY_LAST_SYNC, System.currentTimeMillis())
                .apply()
            SpotifySyncResult(
                playlists = playlistsSynced,
                tracks = tracksSeen,
                matched = tracksAdded
            )
        }
    }

    private suspend fun ensureLocalPlaylist(
        target: SyncTarget,
        playlistMap: MutableMap<String, String>
    ): String {
        val mapped = playlistMap[target.spotifyId]
        if (mapped != null && MusicLibrary.playlists.value.any { it.id == mapped }) return mapped
        val playlist = withContext(Dispatchers.Main) {
            MusicLibrary.createPlaylist(target.name)
        }
        playlistMap[target.spotifyId] = playlist.id
        return playlist.id
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

    private fun loadTrackMap(raw: String?): MutableMap<String, MutableMap<String, String>> {
        val result = mutableMapOf<String, MutableMap<String, String>>()
        if (raw.isNullOrBlank()) return result
        val json = runCatching { JSONObject(raw) }.getOrNull() ?: return result
        json.keys().forEach { localId ->
            val tracks = json.optJSONObject(localId) ?: return@forEach
            val map = mutableMapOf<String, String>()
            tracks.keys().forEach { spotifyId ->
                tracks.optString(spotifyId).takeIf { it.isNotBlank() }?.let { map[spotifyId] = it }
            }
            result[localId] = map
        }
        return result
    }
}
