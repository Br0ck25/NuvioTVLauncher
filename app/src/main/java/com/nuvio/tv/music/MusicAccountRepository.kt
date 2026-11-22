package com.nuvio.tv.music

import android.content.Context
import android.util.Log
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.liskovsoft.youtubeapi.service.data.YouTubeMediaItem
import com.nuvio.tv.youtube.AccountScope
import com.nuvio.tv.youtube.YouTubeAccountScopes
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.model.ShelfLayout
import com.music.bitchord.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** A playlist that lives on the signed-in YouTube Music account. */
data class MusicAccountPlaylist(
    val id: String,
    val title: String,
    val thumbnailUrl: String?,
    val count: Int? = null
)

/**
 * The signed-in half of the Music section.
 *
 * The catalogue browsing still comes from BitChord's InnerTube client. This repository only
 * bridges the account data the app's YouTube engine already fetches with the signed-in OAuth
 * token: the personalised Music home, Liked Music and the account's playlists. Sign-in itself
 * is the same TV device-code flow as the YouTube section.
 */
object MusicAccountRepository {
    private const val TAG = "MusicAccount"
    private const val CACHE_MS = 60_000L
    private const val MAX_PAGES = 300

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val groupItems = ConcurrentHashMap<String, MediaItem>()
    /** The playlist-list cards, so a private playlist can also be opened by its own group key. */
    private val playlistItems = ConcurrentHashMap<String, MediaItem>()

    @Volatile
    private var cachedGroups: List<MediaGroup> = emptyList()

    @Volatile
    private var cachedAt = 0L

    data class Library(
        val liked: List<Song>,
        val playlists: List<MusicAccountPlaylist>
    )

    @Volatile
    private var appContext: Context? = null

    @Synchronized
    fun init(context: Context) {
        appContext = context.applicationContext
        GlobalPreferences.instance(context.applicationContext)
        YouTubeServiceManager.instance()
    }

    fun isSignedIn(): Boolean =
        runCatching { service().signInService.isSigned }
            .getOrDefault(false)

    /** The account's personalised Music home, without the Liked Music row (Library shows it). */
    fun home(): Result<List<HomeShelf>> {
        val first = runCatching { loadHomeData() }
        if (first.isSuccess) {
            persistSession()
            return first
        }
        if (recoverSession()) {
            val retry = runCatching { loadHomeData() }
            if (retry.isSuccess) {
                persistSession()
                return retry
            }
        }
        return first
    }

    private fun loadHomeData(): List<HomeShelf> = loadGroups()
        .filterNot { it.isLikedGroup() }
        .mapNotNull { it.toHomeShelf() }
        .filter { it.items.isNotEmpty() }

    /** The account's Liked Music and playlists. */
    fun library(): Result<Library> {
        val first = runCatching { loadLibraryData() }
        if (first.isSuccess) {
            persistSession()
            return first
        }
        if (recoverSession()) {
            val retry = runCatching { loadLibraryData() }
            if (retry.isSuccess) {
                persistSession()
                return retry
            }
        }
        return first
    }

    private fun loadLibraryData(): Library {
        val groups = loadGroups()
        val likedGroup = groups.firstOrNull { it.isLikedGroup() }
        // The first Music row only carries the first page of liked songs. Read the rest too,
        // otherwise likes added on another device never appear after the first connection.
        val liked = loadAllGroupItems(likedGroup)
            .mapNotNull { it.toSong() }
        // A playlist-list failure shouldn't hide the liked songs that did load, but it can be
        // a stale token: try one renewal before giving up on the list.
        val playlists = runCatching { loadPlaylists() }.getOrElse {
            if (recoverSession()) runCatching { loadPlaylists() }.getOrDefault(emptyList())
            else emptyList()
        }
        return Library(liked = liked, playlists = playlists)
    }

    fun playlistSongs(playlistId: String): Result<List<Song>> {
        val first = loadPlaylistSongs(playlistId)
        if (first.isSuccess && !first.getOrNull().isNullOrEmpty()) {
            persistSession()
            return first
        }
        if (recoverSession()) {
            val retry = loadPlaylistSongs(playlistId)
            if (retry.isSuccess && !retry.getOrNull().isNullOrEmpty()) {
                persistSession()
                return retry
            }
        }
        return first
    }

    private fun loadPlaylistSongs(playlistId: String): Result<List<Song>> = runCatching {
        val raw = normalizePlaylistId(playlistId)
        // Some private playlists parse empty from the generic playlist page. The card that
        // listed the playlist still knows its own group key, so try that before giving up.
        val fromId = runCatching { service().contentService.getPlaylist(raw) }
            .getOrNull()
            ?.takeIf { !it.mediaItems.isNullOrEmpty() || !it.nextPageKey.isNullOrBlank() }
        val stored = playlistItems[playlistId]
            ?: playlistItems[raw]
            ?: groupItems["account:playlist:$raw"]
            ?: groupItems["account:playlist:$playlistId"]
        val fromGroup = stored
            ?.takeIf { it.reloadPageKey != null }
            ?.let { runCatching { service().contentService.getGroup(it) }.getOrNull() }
            ?.takeIf { !it.mediaItems.isNullOrEmpty() || !it.nextPageKey.isNullOrBlank() }
        val group = fromId ?: fromGroup ?: error("This playlist couldn't be opened.")
        loadAllGroupItems(group).mapNotNull { it.toSong() }
    }

    /** Opens a shelf card that was stored while the account home was loaded. */
    fun groupSongs(key: String): Result<List<Song>> = runCatching {
        val source = groupItems[key]
            ?: error("This shelf has expired. Reload Home and try again.")
        val group = service().contentService.getGroup(source)
            ?: error("This shelf couldn't be opened.")
        loadAllGroupItems(group).mapNotNull { it.toSong() }
    }

    /** Every item in [first], following the service's continuation pages. */
    private fun loadAllGroupItems(first: MediaGroup?): List<MediaItem> {
        if (first == null) return emptyList()
        val items = mutableListOf<MediaItem>()
        var group: MediaGroup? = first
        var pages = 0
        while (true) {
            val current = group ?: break
            items += current.mediaItems.orEmpty()
            if (current.nextPageKey.isNullOrBlank() || pages++ >= MAX_PAGES) break
            group = runCatching { service().contentService.continueGroup(current) }.getOrNull()
        }
        return items
    }

    /** Best-effort sync of the heart to the account's Liked Music. The local like is immediate. */
    fun syncLike(song: Song, liked: Boolean) {
        if (song.videoId.isBlank() || song.localUri != null || !isSignedIn()) return
        scope.launch {
            runCatching {
                val item = YouTubeMediaItem.fromIds(song.videoId, song.artistId)
                val call = if (liked) {
                    service().mediaItemService.setLikeObserve(item)
                } else {
                    service().mediaItemService.removeLikeObserve(item)
                }
                call.ignoreElements().blockingAwait()
            }.onSuccess {
                invalidate()
            }.onFailure {
                Log.w(TAG, "Could not sync like for ${song.videoId}", it)
            }
        }
    }

    fun invalidate() {
        cachedGroups = emptyList()
        cachedAt = 0L
        // The shelf map stays: its keys are still valid for this screen session, and clearing
        // them here would break an account detail page opened after the next like.
        runCatching { service().invalidateCache() }
    }

    /** The YouTube engine acting as the profile Music has chosen (OneTube keeps its own). */
    private fun service(): com.liskovsoft.mediaserviceinterfaces.ServiceManager {
        val context = checkNotNull(appContext) { "MusicAccountRepository.init() has not been called" }
        return YouTubeAccountScopes.engine(context, AccountScope.MUSIC)
    }

    /** Writes the refreshed account/token copy for Music (cheap when nothing changed). */
    private fun persistSession() {
        appContext?.let { runCatching { YouTubeAccountScopes.save(it, AccountScope.MUSIC) } }
    }

    /** Tries to renew Music's token after an account-data failure. */
    private fun recoverSession(): Boolean =
        appContext?.let {
            runCatching { YouTubeAccountScopes.recover(it, AccountScope.MUSIC) }.getOrDefault(false)
        } ?: false

    private fun loadGroups(): List<MediaGroup> {
        val now = System.currentTimeMillis()
        if (cachedGroups.isNotEmpty() && now - cachedAt < CACHE_MS) return cachedGroups
        check(isSignedIn()) { "Not signed in to Music" }
        val batches = service().contentService.getMusicObserve()
            .timeout(25, TimeUnit.SECONDS)
            .toList()
            .blockingGet()
        val groups = batches.flatten()
        cachedGroups = groups
        cachedAt = now
        persistSession()
        return groups
    }

    private fun loadPlaylists(): List<MusicAccountPlaylist> {
        var group = service().contentService.getPlaylistsObserve()
            .timeout(20, TimeUnit.SECONDS)
            .blockingFirst()
        val items = mutableListOf<MediaItem>()
        var pages = 0
        while (group != null) {
            items += group.mediaItems.orEmpty()
            if (group.nextPageKey.isNullOrBlank() || pages++ >= MAX_PAGES) break
            group = runCatching { service().contentService.continueGroup(group) }.getOrNull()
        }
        return items
            .mapNotNull { item ->
                val id = item.accountPlaylistId() ?: return@mapNotNull null
                if (item.title.orEmpty().contains("liked", ignoreCase = true)) {
                    return@mapNotNull null
                }
                playlistItems[id] = item
                MusicAccountPlaylist(
                    id = id,
                    title = item.title.orEmpty().ifBlank { "Playlist" },
                    thumbnailUrl = item.cardImageUrl ?: item.backgroundImageUrl
                )
            }
            .distinctBy { it.id }
    }

    /**
     * A playlist's plain id (`PL…`, `OLAK5uy_…`), however the card carries it: some Music
     * playlist tiles only expose the `VL…` browse id, which must not be fed back in.
     */
    private fun MediaItem.accountPlaylistId(): String? {
        val direct = playlistId?.takeIf { it.isNotBlank() }
        if (direct != null) return normalizePlaylistId(direct)
        val browse = reloadPageKey?.takeIf { it.isNotBlank() } ?: channelId?.takeIf { it.isNotBlank() }
        return browse?.takeIf { it.startsWith("VL") }?.removePrefix("VL")?.takeIf { it.isNotBlank() }
    }

    private fun normalizePlaylistId(id: String): String =
        if (id.startsWith("VL") && id.length > 2) id.removePrefix("VL") else id

    private fun MediaGroup.isLikedGroup(): Boolean =
        title.orEmpty().contains("liked", ignoreCase = true)

    private fun MediaGroup.toHomeShelf(): HomeShelf? {
        val items = mediaItems.orEmpty().mapNotNull { it.toShelfItem() }
        if (items.isEmpty()) return null
        val tracks = items.count { it.videoId != null }
        val videos = items.count { it.isVideo }
        val layout = when {
            tracks * 2 > items.size -> ShelfLayout.LIST
            videos * 2 > items.size -> ShelfLayout.VIDEO
            else -> ShelfLayout.CARDS
        }
        return HomeShelf(
            title = title.orEmpty().ifBlank { "Music" },
            items = items,
            layout = layout
        )
    }

    private fun MediaItem.toShelfItem(): ShelfItem? {
        val label = title?.takeIf { it.isNotBlank() } ?: return null
        val id = videoId?.takeIf { it.isNotBlank() }
        val playlist = accountPlaylistId()
        // A playlist card can also carry the first track as a preview. Prefer the playlist
        // browse key for it, otherwise tapping a mix/personal playlist played one track.
        val browseKey = when {
            type == MediaItem.TYPE_PLAYLIST && playlist != null -> {
                val key = "account:playlist:$playlist"
                groupItems[key] = this
                key
            }
            id != null -> null
            playlist != null -> {
                val key = "account:playlist:$playlist"
                groupItems[key] = this
                key
            }
            else -> {
                val key = "account:group:${reloadPageKey ?: channelId ?: label}"
                groupItems[key] = this
                key
            }
        }
        return ShelfItem(
            title = label,
            subtitle = author.orEmpty().ifBlank { secondTitle?.toString().orEmpty() },
            thumbnailUrl = cardImageUrl ?: backgroundImageUrl,
            videoId = id,
            browseId = browseKey,
            isVideo = type == MediaItem.TYPE_VIDEO
        )
    }

    private fun MediaItem.toSong(): Song? {
        val id = videoId?.takeIf { it.isNotBlank() } ?: return null
        val label = title?.takeIf { it.isNotBlank() } ?: return null
        val byline = author?.takeIf { it.isNotBlank() }
            ?: secondTitle?.toString()?.substringBefore(" • ")?.trim().orEmpty()
        return Song(
            videoId = id,
            title = label,
            artist = byline,
            thumbnailUrl = cardImageUrl ?: backgroundImageUrl,
            durationText = formatDuration(durationMs),
            artistId = channelId,
            isVideo = type == MediaItem.TYPE_VIDEO
        )
    }

    private fun formatDuration(milliseconds: Long): String? {
        if (milliseconds <= 0L) return null
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%d:%02d".format(minutes, seconds)
        }
    }
}
