package com.nuvio.tv.music

import android.content.Context
import android.content.SharedPreferences
import com.music.bitchord.data.model.Song
import com.nuvio.tv.music.MusicFilters.isYouTubeTrack
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class MusicTrackListen(
    val videoId: String,
    val title: String,
    val artist: String,
    val artistId: String? = null,
    val plays: Int = 1,
    val lastPlayedMs: Long = 0L
)

@Serializable
internal data class MusicArtistListen(
    val normalized: String,
    val name: String,
    val artistId: String? = null,
    val plays: Int = 1,
    val lastPlayedMs: Long = 0L
)

@Serializable
private data class MusicListeningPayload(
    val tracks: List<MusicTrackListen> = emptyList(),
    val artists: List<MusicArtistListen> = emptyList()
)

/**
 * Local play history for the Music curator.
 *
 * A play is counted only after the listener has actually heard the track: 30 seconds,
 * or half of a shorter track. Skipping through a queue therefore does not skew the
 * taste profile. The history stays on the device; only artist/title names are sent to
 * YouTube when the curator asks for related music.
 */
internal object MusicListeningStore {
    private const val PREFS_NAME = "nuvio_music_listening"
    private const val KEY_PAYLOAD = "payload"
    private const val MAX_TRACKS = 1_000
    private const val MAX_ARTISTS = 400

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private var prefs: SharedPreferences? = null
    private var loadedProfile = -1
    private var tracks: List<MusicTrackListen> = emptyList()
    private var artists: List<MusicArtistListen> = emptyList()

    var totalPlays: Int = 0
        private set
    var trackCount: Int = 0
        private set
    var artistCount: Int = 0
        private set

    @Synchronized
    fun init(context: Context) {
        if (prefs != null && loadedProfile == com.nuvio.tv.core.profile.ActiveProfile.id) return
        val store = com.nuvio.tv.core.profile.ActiveProfile.prefs(context.applicationContext, PREFS_NAME)
        loadedProfile = com.nuvio.tv.core.profile.ActiveProfile.id
        prefs = store
        tracks = emptyList()
        artists = emptyList()
        recalculateStats()
        runCatching {
            val raw = store.getString(KEY_PAYLOAD, null) ?: return@runCatching
            val payload = json.decodeFromString<MusicListeningPayload>(raw)
            tracks = payload.tracks
            artists = payload.artists
            recalculateStats()
        }
    }

    /** Records one real listen of [song]. Safe to call from the player's main thread. */
    @Synchronized
    fun record(song: Song) {
        val title = song.title.trim()
        if (song.videoId.isBlank() || title.isBlank()) return
        // Local files cannot seed a YouTube radio or be replayed from a personal row.
        if (!song.isYouTubeTrack()) return
        val now = System.currentTimeMillis()
        val leadArtist = MusicFilters.splitArtistNames(song.artist).firstOrNull() ?: song.artist
        val artist = MusicFilters.cleanArtistName(leadArtist).ifBlank { "Unknown Artist" }
        val artistKey = MusicFilters.normalizeArtistName(leadArtist)
        val artistId = song.artistId
            ?: song.artists.firstOrNull()?.browseId?.takeIf { it.isNotBlank() }

        val existingTrack = tracks.firstOrNull { it.videoId == song.videoId }
        val updatedTrack = MusicTrackListen(
            videoId = song.videoId,
            title = title,
            artist = artist,
            artistId = artistId ?: existingTrack?.artistId,
            plays = (existingTrack?.plays ?: 0) + 1,
            lastPlayedMs = now
        )
        tracks = (listOf(updatedTrack) + tracks.filterNot { it.videoId == song.videoId })
            .sortedWith(compareByDescending<MusicTrackListen> { it.plays }.thenByDescending { it.lastPlayedMs })
            .take(MAX_TRACKS)

        if (artistKey.isNotBlank()) {
            val existingArtist = artists.firstOrNull { it.normalized == artistKey }
            val updatedArtist = MusicArtistListen(
                normalized = artistKey,
                name = artist.ifBlank { existingArtist?.name ?: artistKey },
                artistId = artistId ?: existingArtist?.artistId,
                plays = (existingArtist?.plays ?: 0) + 1,
                lastPlayedMs = now
            )
            artists = (listOf(updatedArtist) + artists.filterNot { it.normalized == artistKey })
                .sortedWith(compareByDescending<MusicArtistListen> { it.plays }.thenByDescending { it.lastPlayedMs })
                .take(MAX_ARTISTS)
        }

        recalculateStats()
        persist()
    }

    fun heardIds(): Set<String> = synchronized(this) {
        tracks.mapTo(HashSet()) { it.videoId }
    }

    fun topTracks(limit: Int = 8): List<MusicTrackListen> = synchronized(this) {
        tracks.take(limit)
    }

    fun topArtists(limit: Int = 6): List<MusicArtistListen> = synchronized(this) {
        artists.take(limit)
    }

    fun clear() = synchronized(this) {
        tracks = emptyList()
        artists = emptyList()
        recalculateStats()
        prefs?.edit()?.remove(KEY_PAYLOAD)?.apply()
    }

    private fun recalculateStats() {
        totalPlays = tracks.sumOf { it.plays }
        trackCount = tracks.size
        artistCount = artists.size
    }

    private fun persist() {
        val store = prefs ?: return
        runCatching {
            store.edit()
                .putString(KEY_PAYLOAD, json.encodeToString(MusicListeningPayload(tracks, artists)))
                .apply()
        }
    }
}
