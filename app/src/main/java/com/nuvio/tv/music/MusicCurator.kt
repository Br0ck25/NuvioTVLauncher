package com.nuvio.tv.music

import android.content.Context
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.durationMillis
import com.nuvio.tv.music.MusicFilters.isUsable
import com.nuvio.tv.music.MusicFilters.isYouTubeTrack
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.IsoFields
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.random.Random
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** A titled, playable set of tracks (a Daily Mix or Discover Weekly). */
data class MusicCuratedMix(
    val id: String,
    val name: String,
    /** The artists the mix is mostly made of, lead first. */
    val artists: List<String>,
    val tracks: List<Song>
)

/** A "Because you listened to…" row. */
data class MusicCuratedFeed(
    val title: String,
    val tracks: List<Song>
)

/** An album or single from an artist page. The UI opens [browseId]. */
data class MusicRelease(
    val title: String,
    val artist: String,
    val year: Int?,
    val thumbnailUrl: String?,
    val browseId: String,
    val videoId: String?
)

/** A related artist from an artist page, opened as an artist circle. */
data class MusicRelatedArtist(
    val name: String,
    val browseId: String,
    val thumbnailUrl: String?
)

/**
 * Everything the personal Music tabs show, each piece with its own job:
 *  - Home: familiar music shaped around the listener (quick picks, daily mixes,
 *    on repeat, forgotten favourites, "because you listened to").
 *  - Discover: music that is new to the listener (Discover Weekly, Release Radar,
 *    fans also like).
 *  - Explore stays non-personal.
 */
data class MusicCuration(
    val quickPicks: List<Song> = emptyList(),
    val dailyMixes: List<MusicCuratedMix> = emptyList(),
    val onRepeat: List<Song> = emptyList(),
    val forgotten: List<Song> = emptyList(),
    val because: List<MusicCuratedFeed> = emptyList(),
    val discoverWeekly: MusicCuratedMix? = null,
    val releaseRadar: List<MusicRelease> = emptyList(),
    val fansAlsoLike: List<MusicRelatedArtist> = emptyList(),
    val builtAtMs: Long = 0L,
    val fingerprint: String = ""
) {
    val isEmpty: Boolean
        get() = quickPicks.isEmpty() && dailyMixes.isEmpty() && onRepeat.isEmpty() &&
            forgotten.isEmpty() && because.isEmpty() && discoverWeekly == null &&
            releaseRadar.isEmpty() && fansAlsoLike.isEmpty()
}

/**
 * Builds the personal Home and Discover shelves from the local library, the local
 * listening history and a small number of YouTube Music radios/artist pages.
 *
 * This is a port of the desktop MusicCurator. The TV adaptation keeps the same
 * taste weights and section rules, but uses [Song]/[StoredPlaylist] and the TV's
 * [YtMusicRepository]. Nothing here touches the UI; the ViewModel decides when to
 * build, cache and show it.
 */
internal object MusicCurator {
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000L
    private const val CACHE_FILE = "music_curation.json"

    private val radioGate = Semaphore(4)
    private val pageGate = Semaphore(3)
    private val artistIdCache = ConcurrentHashMap<String, String>()

    private data class ArtistTaste(val key: String, val name: String, val weight: Double)

    private class Taste(
        val artists: List<ArtistTaste>,
        val weightOf: Map<String, Double>,
        val heard: Set<String>,
        val liked: Set<String>,
        /** The listener's own tracks per artist, strongest first. */
        val ownByArtist: Map<String, List<Song>>,
        /** Tracks to grow radios from, strongest first, one per artist. */
        val seeds: List<Song>,
        val artistIds: Map<String, String>,
        val fingerprint: String
    )

    private data class ArtistOverview(
        val releases: List<MusicRelease>,
        val related: List<MusicRelatedArtist>
    )

    private fun artistKey(song: Song): String =
        MusicFilters.normalizeArtistName(
            MusicFilters.splitArtistNames(song.artist).firstOrNull() ?: song.artist
        )

    private fun decay(lastPlayedMs: Long, now: Long, halfLifeDays: Double = 21.0): Double {
        if (lastPlayedMs <= 0L) return 0.5
        val days = (now - lastPlayedMs).coerceAtLeast(0L) / DAY_MS.toDouble()
        return exp(-days * 0.693 / halfLifeDays)
    }

    private fun listenSong(track: MusicTrackListen): Song = Song(
        videoId = track.videoId,
        title = track.title,
        artist = track.artist,
        thumbnailUrl = thumbnailFor(track.videoId),
        artistId = track.artistId,
        isVideo = false
    )

    private fun buildTaste(
        liked: List<Song>,
        recents: List<Song>,
        playlists: List<StoredPlaylist>
    ): Taste {
        val now = System.currentTimeMillis()
        val weights = HashMap<String, Double>()
        val names = HashMap<String, String>()
        val artistIds = HashMap<String, String>()

        fun add(song: Song, weight: Double) {
            val key = artistKey(song)
            if (key.isBlank()) return
            weights[key] = (weights[key] ?: 0.0) + weight
            names.putIfAbsent(
                key,
                MusicFilters.cleanArtistName(
                    MusicFilters.splitArtistNames(song.artist).firstOrNull() ?: song.artist
                )
            )
            val id = song.artistId
                ?: song.artists.firstOrNull()?.browseId?.takeIf { it.isNotBlank() }
            if (!id.isNullOrBlank()) artistIds.putIfAbsent(key, id)
        }

        // Plays weigh most and fade over ~3 weeks; likes and saves give a base.
        val listens = MusicListeningStore.topTracks(1_000)
        listens.forEach { track ->
            add(listenSong(track), 3.0 * track.plays * decay(track.lastPlayedMs, now))
        }
        liked.forEachIndexed { index, song -> add(song, if (index < 60) 1.6 else 0.8) }
        playlists.forEach { playlist ->
            playlist.songs.forEach { stored -> add(stored.toSong(), 0.5) }
        }
        recents.take(20).forEach { add(it, 1.0) }

        val artists = weights.entries.sortedByDescending { it.value }
            .map { ArtistTaste(it.key, names[it.key] ?: it.key, it.value) }

        val playsById = listens.associateBy { it.videoId }
        val own = (recents + liked + playlists.flatMap { playlist -> playlist.songs.map { it.toSong() } })
            .filter { it.isYouTubeTrack() }
            .distinctBy { it.videoId }
        val likedIds = liked.mapTo(HashSet()) { it.videoId }
        val ownByArtist = own.groupBy(::artistKey).mapValues { (_, items) ->
            items.sortedByDescending { song ->
                val track = playsById[song.videoId]
                (track?.let { it.plays * decay(it.lastPlayedMs, now) } ?: 0.0) +
                    if (song.videoId in likedIds) 0.5 else 0.0
            }
        }

        // Seeds: most-played-lately tracks first, then fresh likes, one per artist.
        val seedPool = listens.sortedByDescending { it.plays * decay(it.lastPlayedMs, now) }
            .mapNotNull { track ->
                own.firstOrNull { it.videoId == track.videoId } ?: listenSong(track)
            } + recents + liked
        val seeds = seedPool.filter { !MusicFilters.isVariant(it.title) }
            .distinctBy(::artistKey)
            .take(12)
        val heard = buildSet {
            addAll(MusicListeningStore.heardIds())
            own.forEach { add(it.videoId) }
        }
        val fingerprint = artists.take(6).joinToString("|") { it.key } +
            "#" + seeds.take(4).joinToString("|") { it.videoId } +
            "#" + liked.size + "#" + playlists.sumOf { it.songs.size }
        return Taste(artists, weights, heard, likedIds, ownByArtist, seeds, artistIds, fingerprint)
    }

    /** Local-only fingerprint check, used by the ViewModel before rebuilding. */
    internal fun fingerprint(
        liked: List<StoredSong>,
        recents: List<StoredSong>,
        playlists: List<StoredPlaylist>
    ): String = buildTaste(
        liked.map { it.toSong() },
        recents.map { it.toSong() },
        playlists
    ).fingerprint

    private suspend fun radio(seed: Song): List<Song> = radioGate.withPermit {
        YtMusicRepository.radio(seed.videoId)
            .getOrNull()
            .orEmpty()
            .filter { it.videoId != seed.videoId && it.isUsable() }
            .distinctBy { it.videoId }
    }

    private suspend fun artistIdFor(name: String): String? {
        val want = MusicFilters.normalizeArtistName(name)
        if (want.isBlank()) return null
        artistIdCache[want]?.let { return it }
        val page = YtMusicRepository.searchPage(name, SearchFilter.ARTISTS).getOrNull() ?: return null
        val id = page.rows
            .filterIsInstance<SearchResult.Browse>()
            .map { it.item }
            .firstOrNull {
                it.type == BrowseType.ARTIST &&
                    MusicFilters.normalizeArtistName(it.title) == want
            }
            ?.browseId
            ?.takeIf { it.isNotBlank() }
        if (id != null) artistIdCache[want] = id
        return id
    }

    private suspend fun artistOverview(name: String, knownId: String?): ArtistOverview? =
        pageGate.withPermit {
            val id = knownId?.takeIf { it.startsWith("UC") } ?: artistIdFor(name) ?: return@withPermit null
            val page = YtMusicRepository.artistLanding(id).getOrNull() ?: return@withPermit null
            val yearPattern = Regex("\\b(19|20)\\d{2}\\b")
            val releases = page.sections
                .filter { shelf ->
                    val title = shelf.title.lowercase()
                    "album" in title || "single" in title || " ep" in title || title.startsWith("ep")
                }
                .flatMap { it.items }
                .mapNotNull { item ->
                    val browseId = item.browseId?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    MusicRelease(
                        title = item.title,
                        artist = name,
                        year = yearPattern.find(item.subtitle)?.value?.toIntOrNull(),
                        thumbnailUrl = item.thumbnailUrl,
                        browseId = browseId,
                        videoId = item.videoId
                    )
                }
                .distinctBy { it.browseId }
            val related = page.sections
                .filter { shelf ->
                    val title = shelf.title.lowercase()
                    "also like" in title || "similar" in title || "related" in title
                }
                .flatMap { it.items }
                .mapNotNull { item ->
                    val relatedId = item.browseId?.takeIf { it.startsWith("UC") } ?: return@mapNotNull null
                    MusicRelatedArtist(item.title, relatedId, item.thumbnailUrl)
                }
                .distinctBy { it.browseId }
            ArtistOverview(releases, related)
        }

    /** Picks up to [limit] items, at most [perArtist] per artist, skipping same-song duplicates. */
    private fun pick(
        pool: List<Song>,
        limit: Int,
        perArtist: Int,
        used: MutableSet<String>,
        exclude: (Song) -> Boolean = { false }
    ): List<Song> {
        val out = ArrayList<Song>()
        val perArtistCount = HashMap<String, Int>()
        for (item in pool) {
            if (out.size >= limit) break
            if (item.videoId in used || exclude(item)) continue
            if (out.any { MusicFilters.sameSong(it, item) }) continue
            val key = artistKey(item)
            if ((perArtistCount[key] ?: 0) >= perArtist) continue
            perArtistCount[key] = (perArtistCount[key] ?: 0) + 1
            out += item
            used += item.videoId
        }
        return out
    }

    /** Alternates between lists: a1, b1, c1, a2, b2… */
    private fun <T> interleave(vararg lists: List<T>): List<T> {
        val out = ArrayList<T>()
        val max = lists.maxOfOrNull { it.size } ?: 0
        for (i in 0 until max) lists.forEach { list -> list.getOrNull(i)?.let(out::add) }
        return out
    }

    private fun thumbnailFor(videoId: String) = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

    internal suspend fun build(
        likedStored: List<StoredSong>,
        recentsStored: List<StoredSong>,
        playlists: List<StoredPlaylist>
    ): MusicCuration = withContext(Dispatchers.IO) {
        val liked = likedStored.map { it.toSong() }
        val recents = recentsStored.map { it.toSong() }
        val taste = buildTaste(liked, recents, playlists)
        if (taste.artists.isEmpty()) return@withContext MusicCuration()

        val today = LocalDate.now()
        val daySeed = today.toEpochDay()
        val weekSeed = today.get(IsoFields.WEEK_BASED_YEAR) * 100L +
            today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
        val dayRandom = Random(daySeed * 31 + taste.fingerprint.hashCode())
        val weekRandom = Random(weekSeed * 31 + taste.artists.take(3).joinToString().hashCode())
        val now = System.currentTimeMillis()

        // ---- Network: song radios for the top seeds, artist pages for the top artists.
        val anchors = taste.artists.take(4)
        val anchorSeeds = anchors.mapNotNull { artist -> taste.ownByArtist[artist.key]?.firstOrNull() }
        val quickSeeds = taste.seeds.take(3)
        val recentSeeds = recents.filter { it.isUsable() }.distinctBy(::artistKey)
            .filter { recent -> quickSeeds.none { it.videoId == recent.videoId } }
            .take(2)
        // Edge seeds: liked songs by artists the listener barely plays, so Discover
        // Weekly reaches past the usual circle. Rotates weekly.
        val edgeSeeds = liked.filter {
            (taste.weightOf[artistKey(it)] ?: 0.0) < 3.0 && it.isUsable()
        }.shuffled(weekRandom).distinctBy(::artistKey).take(2)
        val radioSeeds = (quickSeeds + anchorSeeds + recentSeeds + edgeSeeds).distinctBy { it.videoId }

        val (radios, overviews) = coroutineScope {
            val radioJobs = radioSeeds.associate { seed ->
                seed.videoId to async { runCatching { radio(seed) }.getOrDefault(emptyList()) }
            }
            val overviewJobs = taste.artists.take(6).map { artist ->
                async {
                    runCatching {
                        artistOverview(artist.name, taste.artistIds[artist.key])
                    }.getOrNull()
                }
            }
            radioJobs.mapValues { it.value.await() } to overviewJobs.awaitAll().filterNotNull()
        }
        fun radioOf(seed: Song) = radios[seed.videoId].orEmpty()
        val known = taste.weightOf.filterValues { it >= 1.0 }.keys
        val used = HashSet<String>()

        // ---- Home: Quick picks. Fresh songs from the top radios, laced with
        // the listener's own favourites; reshuffled daily.
        val freshQuick = pick(
            interleave(*quickSeeds.map { radioOf(it) }.toTypedArray()).shuffled(dayRandom),
            limit = 12, perArtist = 2, used = used
        ) { it.videoId in taste.heard }
        val familiarQuick = pick(
            taste.artists.take(8).flatMap { taste.ownByArtist[it.key].orEmpty().take(3) }
                .shuffled(dayRandom),
            limit = 6, perArtist = 1, used = used
        )
        val quickPicks = interleave(freshQuick.take(9), familiarQuick).take(15)

        // ---- Home: Daily Mixes. One per top artist: their songs the listener
        // loves, songs by artists around them already known, and fresh radio tracks.
        val dailyMixes = anchors.mapIndexedNotNull { index, anchor ->
            val seed = taste.ownByArtist[anchor.key]?.firstOrNull() ?: return@mapIndexedNotNull null
            val pool = radioOf(seed)
            val own = pick(taste.ownByArtist[anchor.key].orEmpty().shuffled(dayRandom), 8, 8, used)
            val neighbours = pool.map(::artistKey)
                .filter { it in known && it != anchor.key }
                .distinct()
            val neighbourOwn = pick(
                neighbours.flatMap { taste.ownByArtist[it].orEmpty().take(2) }.shuffled(dayRandom),
                8, 2, used
            )
            val fresh = pick(pool, 12, 2, used) { it.videoId in taste.heard }
            val tracks = interleave(own, fresh, neighbourOwn).take(30)
            if (tracks.size < 6) return@mapIndexedNotNull null
            val mixArtists = tracks.map {
                MusicFilters.cleanArtistName(
                    MusicFilters.splitArtistNames(it.artist).firstOrNull() ?: it.artist
                )
            }.filter { it.isNotBlank() }
                .groupingBy { it }.eachCount().entries
                .sortedByDescending { it.value }.map { it.key }
            MusicCuratedMix(
                id = "daily-${index + 1}",
                name = "Daily Mix ${index + 1}",
                artists = (listOf(anchor.name) +
                    mixArtists.filter { MusicFilters.normalizeArtistName(it) != anchor.key })
                    .take(4),
                tracks = tracks
            )
        }.mapIndexed { index, mix ->
            // Number the mixes that made it, so a skipped anchor leaves no gap.
            mix.copy(id = "daily-${index + 1}", name = "Daily Mix ${index + 1}")
        }

        // ---- Home: On repeat. What the listener played most in the last month.
        val ownById = (recents + liked + playlists.flatMap { playlist ->
            playlist.songs.map { it.toSong() }
        }).associateBy { it.videoId }
        val onRepeat = MusicListeningStore.topTracks(1_000)
            .filter { it.plays >= 2 && now - it.lastPlayedMs < 30 * DAY_MS }
            .filter { it.videoId !in used }
            .sortedByDescending { it.plays }
            .take(20)
            .map { track -> ownById[track.videoId] ?: listenSong(track) }
        used += onRepeat.map { it.videoId }

        // ---- Home: Forgotten favourites. Liked a while ago, not played lately.
        val lastPlayed = MusicListeningStore.topTracks(1_000)
            .associate { it.videoId to it.lastPlayedMs }
        val forgotten = liked.drop(30)
            .filter { (lastPlayed[it.videoId] ?: 0L) < now - 45 * DAY_MS }
            .filter { it.videoId !in used }
            .shuffled(weekRandom)
            .distinctBy(::artistKey)
            .take(20)
        used += forgotten.map { it.videoId }

        // ---- Home: Because you listened to…
        val because = recentSeeds.mapNotNull { seed ->
            val items = pick(radioOf(seed), 12, 2, used) { it.videoId in taste.heard }
            if (items.size < 4) {
                null
            } else {
                MusicCuratedFeed(
                    "Because you listened to " +
                        MusicFilters.cleanSongTitle(seed.title, seed.artist).ifBlank { seed.title },
                    items
                )
            }
        }

        // ---- Discover: Discover Weekly. Songs never played, by artists the
        // listener does not already play, ranked by how many radios agree.
        val allRadio = radios.values.flatten()
        val consensus = allRadio.groupingBy(::artistKey).eachCount()
        val discoverPool = allRadio
            .filter { it.videoId !in taste.heard && it.videoId !in taste.liked }
            .filter { (taste.weightOf[artistKey(it)] ?: 0.0) < 1.5 }
            .distinctBy { it.videoId }
            .shuffled(weekRandom)
            .sortedByDescending {
                (consensus[artistKey(it)] ?: 0) + if (artistKey(it) !in known) 1 else 0
            }
        val discoverTracks = pick(discoverPool, 30, 2, HashSet(used))
        val discoverWeekly = discoverTracks.takeIf { it.size >= 8 }?.let { tracks ->
            MusicCuratedMix(
                id = "discover-weekly-$weekSeed",
                name = "Discover Weekly",
                artists = tracks.map {
                    MusicFilters.cleanArtistName(
                        MusicFilters.splitArtistNames(it.artist).firstOrNull() ?: it.artist
                    )
                }.filter { it.isNotBlank() }.distinct().take(4),
                tracks = tracks
            )
        }

        // ---- Discover: Release Radar. Latest albums and singles from the top artists.
        val thisYear = today.year
        val releaseRadar = overviews.flatMap { it.releases }
            .filter { it.year == null || it.year >= thisYear - 1 }
            .sortedByDescending { it.year ?: 0 }
            .distinctBy { it.browseId }
            .take(20)

        // ---- Discover: Fans also like. Artists the listener's artists point to,
        // most-recommended first, excluding artists already known.
        val relatedCounts = overviews.flatMap { it.related }
            .groupingBy { it.browseId }.eachCount()
        val fansAlsoLike = overviews.flatMap { it.related }
            .distinctBy { it.browseId }
            .filter { MusicFilters.normalizeArtistName(it.name) !in known }
            .sortedByDescending { relatedCounts[it.browseId] ?: 0 }
            .take(15)

        MusicCuration(
            quickPicks = quickPicks,
            dailyMixes = dailyMixes,
            onRepeat = onRepeat,
            forgotten = forgotten,
            because = because,
            discoverWeekly = discoverWeekly,
            releaseRadar = releaseRadar,
            fansAlsoLike = fansAlsoLike,
            builtAtMs = now,
            fingerprint = taste.fingerprint
        )
    }

    // ------------------------------------------------------------------
    // Disk cache: the last curation, shown instantly on the next launch.
    // ------------------------------------------------------------------

    @Serializable
    private data class CachedMix(
        val id: String,
        val name: String,
        val artists: List<String>,
        val tracks: List<StoredSong>
    )

    @Serializable
    private data class CachedFeed(val title: String, val tracks: List<StoredSong>)

    @Serializable
    private data class CachedRelease(
        val title: String,
        val artist: String,
        val year: Int? = null,
        val thumbnailUrl: String? = null,
        val browseId: String,
        val videoId: String? = null
    )

    @Serializable
    private data class CachedRelated(
        val name: String,
        val browseId: String,
        val thumbnailUrl: String? = null
    )

    @Serializable
    private data class CachedCuration(
        val version: Int = 1,
        val quickPicks: List<StoredSong> = emptyList(),
        val dailyMixes: List<CachedMix> = emptyList(),
        val onRepeat: List<StoredSong> = emptyList(),
        val forgotten: List<StoredSong> = emptyList(),
        val because: List<CachedFeed> = emptyList(),
        val discoverWeekly: CachedMix? = null,
        val releaseRadar: List<CachedRelease> = emptyList(),
        val fansAlsoLike: List<CachedRelated> = emptyList(),
        val builtAtMs: Long = 0L,
        val fingerprint: String = ""
    )

    private val cacheJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    private fun cacheFile(context: Context) = File(context.filesDir, CACHE_FILE)

    private fun Song.toCache(): StoredSong = StoredSong(
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

    private fun MusicCuratedMix.toCache() = CachedMix(
        id = id,
        name = name,
        artists = artists,
        tracks = tracks.map { it.toCache() }
    )

    private fun CachedMix.toMix() = MusicCuratedMix(
        id = id,
        name = name,
        artists = artists,
        tracks = tracks.map { it.toSong() }
    )

    fun loadCached(context: Context): MusicCuration? = runCatching {
        val file = cacheFile(context)
        if (!file.isFile) return null
        val cached = cacheJson.decodeFromString<CachedCuration>(file.readText())
        if (cached.version != 1) return null
        MusicCuration(
            quickPicks = cached.quickPicks.map { it.toSong() },
            dailyMixes = cached.dailyMixes.map { it.toMix() },
            onRepeat = cached.onRepeat.map { it.toSong() },
            forgotten = cached.forgotten.map { it.toSong() },
            because = cached.because.map { feed ->
                MusicCuratedFeed(feed.title, feed.tracks.map { it.toSong() })
            },
            discoverWeekly = cached.discoverWeekly?.toMix(),
            releaseRadar = cached.releaseRadar.map {
                MusicRelease(
                    it.title, it.artist, it.year, it.thumbnailUrl, it.browseId, it.videoId
                )
            },
            fansAlsoLike = cached.fansAlsoLike.map {
                MusicRelatedArtist(it.name, it.browseId, it.thumbnailUrl)
            },
            builtAtMs = cached.builtAtMs,
            fingerprint = cached.fingerprint
        )
    }.getOrNull()

    fun save(context: Context, curation: MusicCuration) = runCatching {
        val cached = CachedCuration(
            quickPicks = curation.quickPicks.map { it.toCache() },
            dailyMixes = curation.dailyMixes.map { it.toCache() },
            onRepeat = curation.onRepeat.map { it.toCache() },
            forgotten = curation.forgotten.map { it.toCache() },
            because = curation.because.map { feed ->
                CachedFeed(feed.title, feed.tracks.map { it.toCache() })
            },
            discoverWeekly = curation.discoverWeekly?.toCache(),
            releaseRadar = curation.releaseRadar.map {
                CachedRelease(
                    it.title, it.artist, it.year, it.thumbnailUrl, it.browseId, it.videoId
                )
            },
            fansAlsoLike = curation.fansAlsoLike.map {
                CachedRelated(it.name, it.browseId, it.thumbnailUrl)
            },
            builtAtMs = curation.builtAtMs,
            fingerprint = curation.fingerprint
        )
        val file = cacheFile(context)
        val temp = File(file.parentFile, "${file.name}.tmp")
        temp.writeText(cacheJson.encodeToString(cached))
        if (!temp.renameTo(file)) {
            file.delete()
            temp.renameTo(file)
        }
    }

    fun clearCache(context: Context) {
        runCatching { cacheFile(context).delete() }
    }

    /** True when the cached curation is from a previous day or older than 6 hours. */
    fun isStale(curation: MusicCuration): Boolean {
        val built = curation.builtAtMs
        if (built <= 0L) return true
        val builtDay = Instant.ofEpochMilli(built).atZone(ZoneId.systemDefault()).toLocalDate()
        return builtDay != LocalDate.now() || System.currentTimeMillis() - built > 6 * HOUR_MS
    }

    /** Stale by day/age or because likes, playlists, top artists or seeds changed. */
    fun isStale(curation: MusicCuration, currentFingerprint: String): Boolean =
        isStale(curation) || curation.fingerprint != currentFingerprint
}
