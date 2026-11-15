package com.nuvio.tv.youtube

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.liskovsoft.mediaserviceinterfaces.ContentService
import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup
import com.liskovsoft.mediaserviceinterfaces.data.MediaItem
import com.liskovsoft.mediaserviceinterfaces.data.MediaItemFormatInfo
import com.liskovsoft.mediaserviceinterfaces.data.SearchOptions
import com.liskovsoft.sharedutils.prefs.GlobalPreferences
import com.liskovsoft.youtubeapi.service.YouTubeServiceManager
import com.liskovsoft.youtubeapi.service.internal.MediaServiceData
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.ui.screens.player.seekpreview.YouTubeStoryboardCache
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

enum class YtKind { VIDEO, CHANNEL, PLAYLIST }

/** One card. Kept separate from MediaServiceCore's types so the UI never touches them. */
data class YtItem(
    val kind: YtKind,
    val videoId: String?,
    val title: String,
    val author: String?,
    /** Views and upload time, as YouTube words them. */
    val meta: String? = null,
    /** The engine's published-time text, used when the second line leaves it out. */
    val postTime: String? = null,
    val thumbnail: String?,
    val badge: String?,
    val isLive: Boolean,
    /** A vertical Short: its card keeps the tall shape instead of a 16:9 crop. */
    val isShorts: Boolean = false,
    val channelId: String?,
    /** How much YouTube says you've watched (0-100), from your account. */
    val percentWatched: Int,
    internal val source: MediaItem
)

data class YtRow(
    val title: String,
    val items: List<YtItem>,
    /** Where this row's next page comes from, so scrolling to its end can load more. */
    internal val group: MediaGroup? = null,
    val canLoadMore: Boolean = false,
)

/** The feeds along the top. Grid feeds are one long list; the others are rows of videos. */
enum class YtTab(val title: String, val needsSignIn: Boolean = false, val grid: Boolean = false) {
    // No Trending: YouTube retired that page, and its requests are now refused.
    HOME("Home"),
    SUBSCRIPTIONS("Subscriptions", needsSignIn = true, grid = true),
    HISTORY("History", needsSignIn = true, grid = true),
    PLAYLISTS("Playlists", needsSignIn = true, grid = true),
    LIVE("Live"),
    MUSIC("Music"),
    GAMING("Gaming"),
    NEWS("News"),
    SPORTS("Sports"),
    SHORTS("Shorts", grid = true),
    CHANNELS("Channels", needsSignIn = true, grid = true)
}

data class YtUiState(
    val tab: YtTab = YtTab.HOME,
    val signedIn: Boolean = false,
    val loading: Boolean = true,
    val loadingMore: Boolean = false,
    val error: String? = null,
    val rows: List<YtRow> = emptyList(),
    val grid: List<YtItem> = emptyList(),
    val canLoadMore: Boolean = false,
    /** Set while looking at a channel, playlist or search opened from a feed; Back returns to the feed. */
    val subTitle: String? = null
) {
    val isEmpty get() = rows.isEmpty() && grid.isEmpty()
}

/** A card's long-press menu: what it is, and what the menu has found out about it. */
data class YtItemMenu(
    val item: YtItem,
    val signedIn: Boolean = false,
    /** Whether the video's channel is subscribed, once it's known. */
    val subscribed: Boolean? = null,
    /** A feed card often arrives without its channel ID; the menu is looking it up. */
    val loadingChannel: Boolean = false,
    val playlists: List<YtPlaylistRow>? = null,
    val loadingPlaylists: Boolean = false,
    val busy: Boolean = false,
    /** A playlist card that is already in the pinned row on the YouTube page. */
    val pinned: Boolean = false,
    val message: String? = null
)

/** What the player needs to start a video. */
data class YtPlayable(
    val url: String,
    val title: String,
    val poster: String?,
    val videoId: String,
    val headers: Map<String, String> = emptyMap(),
    /** Whether this is a live stream; live uses VLC when it is the selected engine. */
    val isLive: Boolean = false,
    /** When this was looked up. YouTube's stream links stop working after a few hours. */
    val lookedUpAt: Long = android.os.SystemClock.elapsedRealtime()
) {
    /** Still safe to play from, without asking YouTube again. */
    val isFresh: Boolean get() = android.os.SystemClock.elapsedRealtime() - lookedUpAt < 20 * 60 * 1000L
}

/**
 * The YouTube section's data. Everything here is shown only inside the YouTube page; nothing is
 * handed to Home, Continue Watching or the library.
 */
@HiltViewModel
class YouTubeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : ViewModel() {
    private companion object {
        const val TAG = "YouTubeVM"
        const val MAX_CLIENT_SWITCHES = 5
        const val PREFETCH_DELAY_MS = 600L
        const val MAX_CACHED = 24
        const val MAX_HISTORY = 50
        /** How much of a video counts as watched for hiding it. */
        const val WATCHED_PERCENT = 90
        /** A feed older than this is reloaded when the app comes back to OneTube. */
        const val FEED_MAX_AGE_MS = 5 * 60 * 1000L

        /**
         * Clients the engine marks as needing a proof-of-origin token. Their plain stream URLs come
         * back HTTP 403 in the player often enough that they are not worth trying for finished
         * videos. Live HLS from them is still used, since that path works.
         */
        val BROKEN_CLIENTS = setOf(
            "WEB", "WEB_CREATOR", "WEB_REMIX", "WEB_EMBEDDED_PLAYER", "MWEB",
            "ANDROID_VR", "iOS", "TVHTML5_SIMPLY_EMBEDDED_PLAYER"
        )
    }

    private val _state = MutableStateFlow(YtUiState())
    val state: StateFlow<YtUiState> = _state.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** The video whose stream is being looked up right now, so the card can show a spinner. */
    private val _resolving = MutableStateFlow<String?>(null)
    val resolving: StateFlow<String?> = _resolving.asStateFlow()

    /** The card whose long-press menu is open. */
    private val _menu = MutableStateFlow<YtItemMenu?>(null)
    val menu: StateFlow<YtItemMenu?> = _menu.asStateFlow()

    private val service: com.liskovsoft.mediaserviceinterfaces.ServiceManager
        get() = YouTubeAccountScopes.engine(context, AccountScope.ONETUBE)
    private val content: ContentService get() = service.contentService

    /** The page being shown: pages opened from it go on top, and Back takes them off. */
    private class Page(val state: YtUiState, val group: MediaGroup?, val channelBlockOn: Boolean)
    private val backStack = ArrayDeque<Page>()
    private var currentGroup: MediaGroup? = null
    private var loadJob: Job? = null
    /** When the feed last finished loading, used to refresh after the app has been away. */
    private var lastFeedLoadedAt = 0L

    /**
     * Caption languages kept in YouTube's generated manifest. YouTube offers a translation for
     * every language it knows (often 150+), which ExoPlayer turns into a separate text track.
     * Only the subtitle preference, the device languages and English are kept; see
     * [YouTubeCaptionTracks]. Starts with the device languages so playback works before settings load.
     */
    private val captionKeepLanguages = MutableStateFlow(YouTubeCaptionTracks.keepLanguages(null, null))

    val canGoBack: Boolean get() = backStack.isNotEmpty()

    init {
        // Clear an old experiment flag: VLC's adaptive HLS path crashes on YouTube manifests.
        runCatching {
            service
            MediaServiceData.instance().setFormatEnabled(MediaServiceData.FORMATS_EXTENDED_HLS, false)
        }
        selectTab(YouTubeSettings.lastTab(context))
        YouTubeMpdServer.attach(context.cacheDir)
        // A killed process can leave sprite sheets behind; they are only useful while a
        // player is open, so sweep them before a new one starts.
        YouTubeStoryboardCache.cleanup(context)
        // Keep the caption keep-list in step with Playback > Subtitles. Reading the whole
        // settings flow is cheap here and avoids a DataStore read for every video lookup.
        viewModelScope.launch {
            runCatching {
                playerSettingsDataStore.playerSettings.collect { settings ->
                    val subtitle = settings.subtitleStyle
                    captionKeepLanguages.value = YouTubeCaptionTracks.keepLanguages(
                        preferred = subtitle.preferredLanguage,
                        secondary = subtitle.secondaryPreferredLanguage
                    )
                }
            }
        }
    }

    // ---- feeds ----

    fun selectTab(tab: YtTab) {
        _error.value = null
        YouTubeContentRegion.apply(context)
        backStack.clear()
        filtersOn = tab != YtTab.HISTORY && tab != YtTab.SHORTS
        channelBlockOn = true
        YouTubeSettings.setLastTab(context, tab)
        _state.update { YtUiState(tab = tab, signedIn = it.signedIn, loading = true) }
        val rowsSource: (() -> io.reactivex.Observable<List<MediaGroup>>)? = when (tab) {
            YtTab.HOME -> { { content.homeObserve } }
            YtTab.LIVE -> { { content.liveObserve } }
            YtTab.MUSIC -> { { content.musicObserve } }
            YtTab.GAMING -> { { content.gamingObserve } }
            YtTab.NEWS -> { { content.newsObserve } }
            YtTab.SPORTS -> { { content.sportsObserve } }
            else -> null
        }
        if (rowsSource != null) {
            loadRows(tab.title, rowsSource)
            return
        }
        load(tab.title) {
            val signed = runCatching { service.signInService.isSigned }.getOrDefault(false)
            _state.update { it.copy(signedIn = signed) }
            if (tab.needsSignIn && !signed) return@load Feed.Nothing
            withRetry {
            when (tab) {
                YtTab.HOME -> rows(content.homeObserve.blockingFirst())
                YtTab.LIVE -> rows(content.liveObserve.blockingFirst())
                YtTab.MUSIC -> rows(content.musicObserve.blockingFirst())
                YtTab.GAMING -> rows(content.gamingObserve.blockingFirst())
                YtTab.NEWS -> rows(content.newsObserve.blockingFirst())
                YtTab.SPORTS -> rows(content.sportsObserve.blockingFirst())
                YtTab.SUBSCRIPTIONS -> grid(content.subscriptionsObserve.blockingFirst())
                YtTab.SHORTS -> grid(content.shortsObserve.blockingFirst())
                YtTab.CHANNELS -> grid(content.subscribedChannelsObserve.blockingFirst())
                YtTab.HISTORY -> grid(content.historyObserve.blockingFirst())
                YtTab.PLAYLISTS -> grid(content.playlistsObserve.blockingFirst())
            }
            }
        }
    }

    /**
     * Reloads the current feed when it is older than [maxAgeMs]. Used when the app comes back to
     * OneTube: YouTube's recommendations change over time, but a long-lived process would otherwise
     * keep the rows it loaded days ago. Channel, playlist and search pages are left alone.
     */
    fun refreshIfStale(maxAgeMs: Long = FEED_MAX_AGE_MS) {
        val current = _state.value
        if (current.subTitle != null || backStack.isNotEmpty() || current.loading) return
        val now = android.os.SystemClock.elapsedRealtime()
        if (lastFeedLoadedAt > 0L && now - lastFeedLoadedAt < maxAgeMs) return
        selectTab(current.tab)
    }

    fun search(query: String) {
        if (query.isBlank()) return
        filtersOn = true
        channelBlockOn = true
        pushPage()
        _state.update {
            it.copy(loading = true, error = null, rows = emptyList(), grid = emptyList(), canLoadMore = false,
                subTitle = "Results for \"${query.trim()}\"")
        }
        loadRows(query) {
            io.reactivex.Observable.fromCallable {
                val mixed = content.getSearchObserve(query).blockingFirst()
                // Always ask for the channel-filtered answer. YouTube's default search often
                // returns video rows even when a channel card is expected, and the default
                // answer can contain an empty channel section that would otherwise hide it.
                val channels = runCatching {
                    content.getSearchObserve(query, SearchOptions.TYPE_CHANNEL).blockingFirst()
                }.getOrDefault(emptyList())
                Log.i(TAG, "search channels query=$query groups=${channels.size} items=${channels.sumOf { it.mediaItems?.size ?: 0 }}")
                if (channels.isEmpty()) mixed else channels + mixed
            }
        }
    }

    /** Opens a channel or playlist on top of the current page. */
    fun open(item: YtItem) {
        _error.value = null
        // A channel card with nothing behind it would make the engine return null ("fromNullable
        // result is null"), so say it plainly instead of trying to open it.
        if (item.kind == YtKind.CHANNEL && item.source.reloadPageKey == null && item.channelId == null) {
            _error.value = "This channel can't be opened."
            return
        }
        // Cards from the feeds often carry only a playlist id, with no continuation key.
        // Open those by id instead.
        if (item.kind == YtKind.PLAYLIST && item.source.reloadPageKey == null) {
            val playlistId = item.source.playlistId
            if (playlistId.isNullOrBlank()) {
                _error.value = "This playlist can't be opened."
                return
            }
            filtersOn = true
            channelBlockOn = true
            pushPage()
            _state.update {
                it.copy(loading = true, error = null, rows = emptyList(), grid = emptyList(), canLoadMore = false,
                    subTitle = item.title)
            }
            load(item.title) {
                val byId = runCatching { content.getPlaylist(playlistId) }.getOrNull()
                if (byId != null) {
                    grid(byId)
                } else {
                    // Home cards sometimes only have a reload/page key behind them, not a
                    // playable playlist id. Failing over to that page beats a dead error.
                    grid(content.getGroupObserve(item.source).blockingFirst())
                }
            }
            return
        }
        filtersOn = true
        channelBlockOn = item.kind != YtKind.CHANNEL
        pushPage()
        _state.update {
            it.copy(loading = true, error = null, rows = emptyList(), grid = emptyList(), canLoadMore = false,
                subTitle = item.title)
        }
        load(item.title) {
            when (item.kind) {
                // A channel from the Channels list opens its uploads by its reload key; any other by its id.
                YtKind.CHANNEL ->
                    if (item.channelId == null && item.source.reloadPageKey != null) grid(content.getGroupObserve(item.source).blockingFirst())
                    else rows(content.getChannelObserve(item.source).blockingFirst())
                else -> grid(content.getGroupObserve(item.source).blockingFirst())
            }
        }
    }

    /** Opens a channel by its id, on top of the current page. Used from the player's YouTube panel. */
    fun openChannel(channelId: String, name: String) {
        pushPage()
        filtersOn = true
        channelBlockOn = false
        _state.update {
            it.copy(loading = true, error = null, rows = emptyList(), grid = emptyList(), canLoadMore = false, subTitle = name)
        }
        loadRows(name) { content.getChannelObserve(channelId) }
    }

    /** Opens a pinned playlist straight from its id. */
    fun openPlaylistById(playlistId: String, title: String) {
        _error.value = null
        filtersOn = true
        channelBlockOn = true
        pushPage()
        _state.update {
            it.copy(loading = true, error = null, rows = emptyList(), grid = emptyList(), canLoadMore = false, subTitle = title)
        }
        load(title) { grid(content.getPlaylist(playlistId)) }
    }

    private fun pushPage() {
        // A page that's still loading has nothing to come back to.
        if (!_state.value.loading) backStack.addLast(Page(_state.value, currentGroup, channelBlockOn))
    }

    /** Takes the top page off. Returns false when there's nothing to go back to. */
    fun back(): Boolean {
        _error.value = null
        val page = backStack.removeLastOrNull() ?: return false
        loadJob?.cancel()
        currentGroup = page.group
        channelBlockOn = page.channelBlockOn
        _state.value = page.state.copy(loading = false)
        return true
    }

    private val rowsLoading: MutableSet<MediaGroup> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Loads the next page of row [index], when you're getting near its end. */
    fun loadMoreRow(index: Int) {
        val row = _state.value.rows.getOrNull(index) ?: return
        val group = row.group ?: return
        if (!row.canLoadMore || !rowsLoading.add(group)) return
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { content.continueGroupObserve(group).blockingFirst() }.getOrNull()
            }
            val more = next?.let(::toItems).orEmpty()
            _state.update { s ->
                s.copy(rows = s.rows.map { r ->
                    if (r.group === group) r.copy(
                        items = (r.items + more).distinctBy { item -> item.uniqueKey() },
                        group = next ?: group,
                        canLoadMore = next?.nextPageKey != null && more.isNotEmpty()
                    ) else r
                })
            }
            rowsLoading.remove(group)
            fetchDeArrow()
        }
    }

    /** The videos of the channel highlighted in the Channels list, shown beside it. */
    data class ChannelPreview(
        val title: String = "",
        val items: List<YtItem> = emptyList(),
        val loading: Boolean = false,
        val canLoadMore: Boolean = false,
        internal val group: MediaGroup? = null
    )

    private val _preview = MutableStateFlow(ChannelPreview())
    val preview: StateFlow<ChannelPreview> = _preview.asStateFlow()
    private val previews = java.util.concurrent.ConcurrentHashMap<String, ChannelPreview>()
    private var previewJob: Job? = null

    /** Shows [channel]'s videos beside the list. Waits a moment, so scrolling past channels doesn't ask for each one. */
    fun previewChannel(channel: YtItem) {
        previewJob?.cancel()
        previews[channel.title]?.let { _preview.value = it; return }
        _preview.value = ChannelPreview(title = channel.title, loading = true)
        previewJob = viewModelScope.launch {
            delay(350)
            val group = withContext(Dispatchers.IO) {
                runCatching { content.getGroupObserve(channel.source).blockingFirst() }.getOrNull()
            }
            val result = ChannelPreview(
                title = channel.title,
                items = group?.let(::toItems).orEmpty(),
                canLoadMore = group?.nextPageKey != null,
                group = group
            )
            if (group != null) previews[channel.title] = result
            _preview.value = result
            fetchDeArrow()
        }
    }

    fun previewMore() {
        val cur = _preview.value
        val group = cur.group ?: return
        if (cur.loading || !cur.canLoadMore) return
        _preview.value = cur.copy(loading = true)
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { content.continueGroupObserve(group).blockingFirst() }.getOrNull()
            }
            val more = next?.let(::toItems).orEmpty()
            val merged = cur.copy(
                items = (cur.items + more).distinctBy { item -> item.uniqueKey() },
                loading = false,
                canLoadMore = next?.nextPageKey != null && more.isNotEmpty(),
                group = next ?: group
            )
            previews[cur.title] = merged
            if (_preview.value.title == cur.title) _preview.value = merged
            fetchDeArrow()
        }
    }

    fun loadMore() {
        val group = currentGroup ?: return
        val s = _state.value
        if (s.loading || s.loadingMore || !s.canLoadMore) return
        _state.update { it.copy(loadingMore = true) }
        viewModelScope.launch {
            val next = withContext(Dispatchers.IO) {
                runCatching { content.continueGroupObserve(group).blockingFirst() }.getOrNull()
            }
            val more = next?.let(::toItems).orEmpty()
            currentGroup = next ?: group
            _state.update {
                it.copy(
                    grid = (it.grid + more).distinctBy { item -> item.uniqueKey() },
                    loadingMore = false,
                    canLoadMore = next?.nextPageKey != null && more.isNotEmpty()
                )
            }
            fetchDeArrow()
        }
    }

    /**
     * Runs a feed request, trying again if YouTube answers with nothing or an error. Right after a
     * clean install the first answers can be empty until the engine has set up its session, so the
     * first retry also asks it to refresh that.
     */
    private fun toRows(groups: List<MediaGroup>?): List<YtRow> =
        groups.orEmpty().map { YtRow(it.title.orEmpty(), toItems(it), it, it.nextPageKey != null) }.filter { it.items.isNotEmpty() }

    /** Home follows SmartTube's useful order; unknown rows keep their place after the known ones. */
    private fun orderedHomeRows(rows: List<YtRow>): List<YtRow> {
        val priority = listOf("recommended", "recently uploaded", "continue watching", "trending", "live", "music")
        return rows.sortedBy { row ->
            val title = row.title.lowercase()
            priority.indexOfFirst { title.contains(it) }.let { if (it < 0) priority.size else it }
        }
    }

    /**
     * Loads a page made of rows. YouTube sends it in batches of rows, and SmartTube keeps taking batches until
     * there are none left; so the first batch shows straight away, and the rest are added underneath as they come.
     */
    private fun loadRows(label: String, source: () -> io.reactivex.Observable<List<MediaGroup>>) {
        refreshHidden()
        loadJob?.cancel()
        currentGroup = null
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val signed = runCatching { service.signInService.isSigned }.getOrDefault(false)
                    _state.update { it.copy(signedIn = signed) }
                    val tab = _state.value.tab
                    if (_state.value.subTitle == null && tab.needsSignIn && !signed) return@runCatching
                    var got = false
                    var lastError: Throwable? = null
                    for (attempt in 0 until 3) {
                        try {
                            source().blockingForEach { groups ->
                                val incoming = toRows(groups)
                                if (incoming.isEmpty()) return@blockingForEach
                                val batch = if (label == YtTab.HOME.title) orderedHomeRows(incoming) else incoming
                                val firstEver = !got
                                got = true
                                _state.update { s ->
                                    val rows = if (firstEver) batch else s.rows + batch
                                    s.copy(
                                        loading = false,
                                        grid = emptyList(),
                                        canLoadMore = false,
                                        rows = if (label == YtTab.HOME.title) orderedHomeRows(rows) else rows
                                    )
                                }
                                if (firstEver) {
                                    // The first lookup after the app starts is slow, so do it while you're still choosing.
                                    batch.firstOrNull()?.items?.firstOrNull { it.kind == YtKind.VIDEO }?.let { lookup(it) }
                                }
                            }
                            lastError = null
                            break
                        } catch (e: Throwable) {
                            lastError = e
                            if (got) break // what's already shown stays
                            if (attempt == 0) runCatching { service.refreshCacheIfNeeded() }
                            if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
                        }
                    }
                    lastError?.let { if (!got) throw it }
                }
            }
            if (result.isSuccess) lastFeedLoadedAt = android.os.SystemClock.elapsedRealtime()
            result.onFailure { e ->
                Log.e(TAG, "couldn't load $label", e)
                _state.update {
                    if (e is NoSuchElementException) it.copy(loading = false)
                    else it.copy(loading = false, error = e.message ?: "Couldn't load $label")
                }
            }
            _state.update { it.copy(loading = false) }
            fetchDeArrow()
        }
    }

    private fun <T> withRetry(block: () -> T): T {
        var last: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                return block()
            } catch (e: Throwable) {
                last = e
                if (attempt == 0) runCatching { service.refreshCacheIfNeeded() }
                if (attempt < 2) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: IllegalStateException("No answer from OneTube")
    }

    private sealed interface Feed {
        data object Nothing : Feed
        class Rows(val rows: List<YtRow>) : Feed
        class Grid(val group: MediaGroup) : Feed
    }

    private fun rows(groups: List<MediaGroup>?): Feed =
        Feed.Rows(groups.orEmpty().map { YtRow(it.title.orEmpty(), toItems(it), it, it.nextPageKey != null) }.filter { it.items.isNotEmpty() })

    private fun grid(group: MediaGroup?): Feed = if (group == null) Feed.Nothing else Feed.Grid(group)

    private fun load(label: String, fetch: () -> Feed) {
        refreshHidden()
        loadJob?.cancel()
        currentGroup = null
        loadJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetch() } }
            result.fold(
                onSuccess = { feed ->
                    lastFeedLoadedAt = android.os.SystemClock.elapsedRealtime()
                    when (feed) {
                        Feed.Nothing -> _state.update { it.copy(loading = false) }
                        is Feed.Rows -> {
                            _state.update { it.copy(loading = false, rows = feed.rows, grid = emptyList(), canLoadMore = false) }
                            // The first lookup after the app starts is slow (YouTube's player scripts
                            // load then), so do it now, while you're still choosing.
                            feed.rows.firstOrNull()?.items?.firstOrNull { it.kind == YtKind.VIDEO }?.let { lookup(it) }
                        }
                        is Feed.Grid -> {
                            currentGroup = feed.group
                            _state.update {
                                it.copy(
                                    loading = false,
                                    rows = emptyList(),
                                    grid = toItems(feed.group),
                                    canLoadMore = feed.group.nextPageKey != null
                                )
                            }
                        }
                    }
                    fetchDeArrow()
                },
                onFailure = { e ->
                    Log.e(TAG, "couldn't load $label", e)
                    _state.update {
                        // "Nothing came back" is an empty page, not a broken connection.
                        if (e is NoSuchElementException) it.copy(loading = false)
                        else it.copy(loading = false, error = e.message ?: "Couldn't load $label")
                    }
                }
            )
        }
    }

    // ---- DeArrow: community titles and thumbnails ----

    private val deArrow = java.util.concurrent.ConcurrentHashMap<String, Pair<String?, String?>>()
    private val deArrowAsked: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** [item] with its DeArrow title and thumbnail, if they've been looked up and you've left them on. */
    private fun deArrowCached(item: YtItem): YtItem {
        val id = item.videoId ?: return item
        val (title, thumb) = deArrow[id] ?: return item
        return item.copy(
            title = title?.takeIf { it.isNotBlank() && YouTubeSettings.deArrowTitles(context) } ?: item.title,
            thumbnail = thumb?.takeIf { it.isNotBlank() && YouTubeSettings.deArrowThumbnails(context) } ?: item.thumbnail
        )
    }

    /** Looks up DeArrow data for what's on screen, and swaps it in as each answer arrives. */
    private fun fetchDeArrow() {
        if (!YouTubeSettings.deArrowTitles(context) && !YouTubeSettings.deArrowThumbnails(context)) return
        val s = _state.value
        val ids = (s.rows.flatMap { it.items } + s.grid).mapNotNull { it.videoId }.filter { deArrowAsked.add(it) }.take(60)
        if (ids.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                service.mediaItemService.getDeArrowDataObserve(ids).blockingForEach { d ->
                    val id = d.videoId ?: return@blockingForEach
                    deArrow[id] = d.title to d.thumbnailUrl
                    _state.update { st ->
                        st.copy(
                            rows = st.rows.map { r -> r.copy(items = r.items.map { if (it.videoId == id) deArrowCached(it) else it }) },
                            grid = st.grid.map { if (it.videoId == id) deArrowCached(it) else it }
                        )
                    }
                }
            }.onFailure { Log.w(TAG, "DeArrow lookup failed", it) }
        }
    }

    /** Feed filters apply everywhere except History, which is meant to show what you've watched. */
    private var filtersOn = true
    /** Hidden channels are left out of feeds, but not out of that channel's own page. */
    private var channelBlockOn = true
    private var hiddenWords: List<String> = emptyList()
    private var hiddenChannelIds: Set<String> = emptySet()

    private fun refreshHidden() {
        hiddenWords = YouTubeSettings.hiddenKeywords(context)
        hiddenChannelIds = YouTubeSettings.hiddenChannels(context).map { it.first }.toSet()
    }

    // ---- card menu ----

    /** Opens the long-press menu for [item] and starts looking up its channel and playlist state. */
    fun openMenu(item: YtItem) {
        val playlistId = item.source.playlistId
        val pinned = item.kind == YtKind.PLAYLIST && playlistId != null &&
            YouTubeSettings.pinnedPlaylists(context).any { it.first == playlistId }
        _menu.value = YtItemMenu(item = item, signedIn = _state.value.signedIn, pinned = pinned)
        // A channel card has no video id; ask the engine for its subscription state directly.
        if (item.kind == YtKind.CHANNEL) {
            val channelId = item.channelId ?: return
            viewModelScope.launch {
                val meta = withContext(Dispatchers.IO) {
                    runCatching { service.mediaItemService.getMetadata(item.source) }.getOrNull()
                }
                _menu.update { menu ->
                    if (menu?.item?.channelId == channelId) menu.copy(subscribed = meta?.isSubscribed) else menu
                }
            }
            return
        }
        val videoId = item.videoId ?: return
        if (item.kind != YtKind.VIDEO) return

        // Home and search cards usually carry the author name but not the channel ID. The
        // metadata call fills it in so Open channel / Subscribe / Block can appear.
        if (item.channelId == null) _menu.update { it?.copy(loadingChannel = true) }
        viewModelScope.launch {
            val meta = withContext(Dispatchers.IO) {
                runCatching { service.mediaItemService.getMetadata(videoId) }.getOrNull()
            }
            Log.i(TAG, "menu video=$videoId channel=${meta?.channelId} subscribed=${meta?.isSubscribed}")
            _menu.update { menu ->
                if (menu?.item?.videoId != videoId) {
                    menu
                } else {
                    menu.copy(
                        item = if (menu.item.channelId == null && !meta?.channelId.isNullOrBlank()) {
                            menu.item.copy(channelId = meta?.channelId)
                        } else {
                            menu.item
                        },
                        subscribed = meta?.isSubscribed ?: menu.subscribed,
                        loadingChannel = false
                    )
                }
            }
        }

        if (!_state.value.signedIn) return
        loadMenuPlaylists(videoId)
    }

    private fun loadMenuPlaylists(videoId: String) {
        if (!_state.value.signedIn) return
        _menu.update { it?.copy(loadingPlaylists = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { service.mediaItemService.getPlaylistsInfoObserve(videoId).blockingFirst() }
            }
            _menu.update { menu ->
                if (menu?.item?.videoId != videoId) {
                    menu
                } else {
                    menu.copy(
                        loadingPlaylists = false,
                        playlists = result.getOrNull().orEmpty()
                            .withoutDuplicatePlaylists()
                            .map {
                                YtPlaylistRow(it.playlistId.orEmpty(), it.title.orEmpty(), it.isSelected)
                            },
                        message = if (result.isFailure) "Couldn't load your playlists." else null
                    )
                }
            }
        }
    }

    /** Creates a playlist named [name] and puts the open card's video in it. */
    fun menuCreatePlaylist(name: String) {
        val menu = _menu.value ?: return
        val videoId = menu.item.videoId ?: return
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        _menu.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    service.mediaItemService.createPlaylistObserve(trimmed, videoId)
                        .ignoreElements().blockingAwait()
                }.isSuccess
            }
            _menu.update { current ->
                if (current?.item?.videoId != videoId) {
                    current
                } else {
                    current.copy(
                        busy = false,
                        message = if (ok) "Saved to $trimmed" else "Couldn't create the playlist."
                    )
                }
            }
            if (ok) loadMenuPlaylists(videoId)
        }
    }

    /** Deletes a playlist card from your YouTube account. */
    fun menuDeletePlaylist() {
        val menu = _menu.value ?: return
        if (menu.item.kind != YtKind.PLAYLIST) return
        val playlistId = menu.item.source.playlistId
        if (playlistId.isNullOrBlank()) {
            _menu.update { it?.copy(message = "This playlist can't be deleted from here.") }
            return
        }
        if (!_state.value.signedIn) {
            _menu.update { it?.copy(message = "Sign in to OneTube first, with the account button on the OneTube page.") }
            return
        }
        _menu.update { it?.copy(busy = true, message = null) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    service.mediaItemService.removePlaylistObserve(playlistId)
                        .ignoreElements().blockingAwait()
                }.isSuccess
            }
            if (ok) {
                YouTubeSettings.unpinPlaylist(context, playlistId)
                _menu.value = null
                // Reload the feed so the deleted card disappears.
                selectTab(_state.value.tab)
            } else {
                _menu.update { it?.copy(busy = false, message = "Couldn't delete the playlist.") }
            }
        }
    }

    fun closeMenu() {
        _menu.value = null
    }

    /** Subscribe to, or leave, the channel of the card whose menu is open. */
    fun menuToggleSubscribe() {
        val menu = _menu.value ?: return
        val channelId = menu.item.channelId ?: return
        val wasSubscribed = menu.subscribed ?: return
        _menu.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val call = if (wasSubscribed) {
                        service.mediaItemService.unsubscribeObserve(channelId)
                    } else {
                        service.mediaItemService.subscribeObserve(channelId)
                    }
                    call.ignoreElements().blockingAwait()
                }.isSuccess
            }
            _menu.update { current ->
                if (current?.item?.channelId != channelId) {
                    current
                } else {
                    current.copy(
                        busy = false,
                        subscribed = if (ok) !wasSubscribed else current.subscribed,
                        message = if (ok) {
                            if (wasSubscribed) "Unsubscribed" else "Subscribed"
                        } else {
                            "That didn't go through. Try again."
                        }
                    )
                }
            }
        }
    }

    /** Adds the open card's video to a playlist, or takes it out again. */
    fun menuTogglePlaylist(row: YtPlaylistRow) {
        val menu = _menu.value ?: return
        val videoId = menu.item.videoId ?: return
        _menu.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    val call = if (row.contains) {
                        service.mediaItemService.removeFromPlaylistObserve(row.id, videoId)
                    } else {
                        service.mediaItemService.addToPlaylistObserve(row.id, videoId)
                    }
                    call.ignoreElements().blockingAwait()
                }.isSuccess
            }
            _menu.update { current ->
                if (current?.item?.videoId != videoId) {
                    current
                } else {
                    current.copy(
                        busy = false,
                        playlists = if (ok) {
                            current.playlists?.map {
                                if (it.id == row.id) it.copy(contains = !row.contains) else it
                            }
                        } else {
                            current.playlists
                        },
                        message = if (ok) {
                            if (row.contains) "Removed from ${row.title}" else "Saved to ${row.title}"
                        } else {
                            "That didn't go through. Try again."
                        }
                    )
                }
            }
        }
    }

    /** Pins a playlist card to the row at the top of the YouTube page, or takes it off. */
    fun menuTogglePinned() {
        val menu = _menu.value ?: return
        if (menu.item.kind != YtKind.PLAYLIST) return
        val playlistId = menu.item.source.playlistId ?: return
        if (menu.pinned) {
            YouTubeSettings.unpinPlaylist(context, playlistId)
        } else {
            YouTubeSettings.pinPlaylist(context, playlistId, menu.item.title)
        }
        _menu.update { current ->
            current?.copy(
                pinned = !menu.pinned,
                message = if (menu.pinned) "Removed from the OneTube page" else "Pinned to the OneTube page"
            )
        }
    }

    /** Hides the open card's channel everywhere on the YouTube page. */
    fun menuBlockChannel() {
        val item = _menu.value?.item ?: return
        val channelId = item.channelId ?: return
        val name = item.author?.takeIf { it.isNotBlank() } ?: item.title
        YouTubeSettings.hideChannel(context, channelId, name)
        refreshHidden()
        closeMenu()
        // Take the channel's cards off what's on screen right away.
        _state.update { st ->
            st.copy(
                rows = st.rows
                    .map { row -> row.copy(items = row.items.filter { it.channelId != channelId }) }
                    .filter { it.items.isNotEmpty() },
                grid = st.grid.filter { it.channelId != channelId }
            )
        }
    }

    /** Removes a video card from the feed using YouTube's "Not interested" feedback. */
    fun menuNotInterested() {
        val menu = _menu.value ?: return
        val token = menu.item.source.feedbackToken?.takeIf { it.isNotBlank() }
        if (token == null) {
            _menu.update { it?.copy(message = "This card can't be removed.") }
            return
        }
        _menu.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    service.mediaItemService.markAsNotInterestedObserve(token)
                        .ignoreElements().blockingAwait()
                }.isSuccess
            }
            if (ok) {
                val key = menu.item.uniqueKey()
                _menu.value = null
                _state.update { st ->
                    st.copy(
                        rows = st.rows
                            .map { row -> row.copy(items = row.items.filterNot { it.uniqueKey() == key }) }
                            .filter { it.items.isNotEmpty() },
                        grid = st.grid.filterNot { it.uniqueKey() == key }
                    )
                }
            } else {
                _menu.update { it?.copy(busy = false, message = "Couldn't remove this card.") }
            }
        }
    }

    private fun toItems(g: MediaGroup): List<YtItem> = g.mediaItems.orEmpty().filter(::allowed).mapNotNull(::toItem)
        // The engine can list a playlist twice (for example, once from the account and once from a
        // local group); keep the first card for each id.
        .distinctBy { it.uniqueKey() }

    /** A card's identity for de-duplication. Playlists go by title: the engine's two copies can differ in id. */
    private fun YtItem.uniqueKey(): String = when (kind) {
        YtKind.PLAYLIST -> "p:" + title.trim().lowercase()
        YtKind.CHANNEL -> "c:" + (channelId ?: source.reloadPageKey ?: title)
        YtKind.VIDEO -> "v:" + (videoId ?: title)
    }

    /** Whether your filters in Settings let [i] through. Channels and playlists always do. */
    private fun allowed(i: MediaItem): Boolean {
        if (!filtersOn) return true
        if (i.type == MediaItem.TYPE_CHANNEL || i.type == MediaItem.TYPE_PLAYLIST) return true
        if (i.isShorts && YouTubeSettings.hideShorts(context)) return false
        // The Sports shelves come with Shorts in them; they aren't sports fixtures.
        if (i.isShorts && _state.value.tab == YtTab.SPORTS) return false
        if (i.isUpcoming && YouTubeSettings.hideUpcoming(context)) return false
        if (hiddenWords.isNotEmpty()) {
            val text = "${i.title.orEmpty()} ${i.author.orEmpty()}".lowercase()
            if (hiddenWords.any { it in text }) return false
        }
        if (channelBlockOn && i.channelId != null && i.channelId in hiddenChannelIds) return false
        if (YouTubeSettings.hideWatched(context)) {
            val watched = maxOf(i.percentWatched, YouTubeProgress.localPercent(context, i.videoId))
            if (watched >= WATCHED_PERCENT) return false
        }
        return true
    }

    private fun toItem(i: MediaItem): YtItem? {
        val kind = when {
            i.type == MediaItem.TYPE_CHANNEL -> YtKind.CHANNEL
            i.type == MediaItem.TYPE_PLAYLIST -> YtKind.PLAYLIST
            // A subscribed channel in the Channels list: no video, opened through its reload key.
            i.videoId == null && i.reloadPageKey != null -> YtKind.CHANNEL
            else -> YtKind.VIDEO
        }
        if (kind == YtKind.VIDEO && i.videoId == null) return null
        val base = YtItem(
            kind = kind,
            videoId = i.videoId,
            title = i.title.orEmpty(),
            author = i.author,
            meta = i.secondTitle?.toString()?.takeIf { it.isNotBlank() && it != i.author },
            postTime = i.productionDate?.takeIf { it.isNotBlank() && it != i.title },
            thumbnail = i.cardImageUrl,
            badge = i.badgeText,
            isLive = i.isLive,
            isShorts = i.isShorts,
            channelId = i.channelId,
            percentWatched = i.percentWatched.coerceIn(0, 100),
            source = i
        )
        return deArrowCached(base)
    }

    // ---- playback ----

    private val resolved = java.util.concurrent.ConcurrentHashMap<String, YtPlayable>()
    private var prefetchJob: Job? = null
    private var prefetchLookup: Deferred<Result<YtPlayable>>? = null
    private var prefetchLookupId: String? = null
    private val inflight = java.util.concurrent.ConcurrentHashMap<String, Deferred<Result<YtPlayable>>>()

    /** Looks up the stream details for [video]; slow (several requests), so call off the main thread. */
    private fun resolveBlocking(video: YtItem): YtPlayable {
        val videoId = video.videoId ?: error("Not a video")
        // Start from the default client every time. The TV client can spend 20+ seconds on its
        // SABR/PO-token path before we switch; VISIONOS returns plain DASH quickly.
        runCatching { service.resetPlaybackClient() }
        val startedAt = android.os.SystemClock.elapsedRealtime()
        fun since() = android.os.SystemClock.elapsedRealtime() - startedAt
        var info = service.mediaItemService.getFormatInfoObserve(videoId).blockingFirst()
        Log.i(TAG, "first lookup $videoId client=${info.clientInfo?.clientName} at ${since()}ms")
        // YouTube answers some client types with SABR only (no plain stream URLs), which the stock
        // player can't read. Step through the engine's other clients until one returns streams we can play.
        var attempts = 0
        while (!playable(info) && attempts++ < MAX_CLIENT_SWITCHES) {
            Log.i(TAG, "client ${info.clientInfo?.clientName} gave no plain streams " +
                "(sabr=${info.containsSabrFormats()} unplayable=${info.isUnplayable}); switching")
            service.switchNextClientNow()
            info = service.mediaItemService.getFormatInfoObserve(videoId).blockingFirst()
            Log.i(TAG, "lookup with client=${info.clientInfo?.clientName} at ${since()}ms")
        }
        if (info.isUnplayable) error(friendlyReason(info.playabilityReason))
        // The seek preview uses YouTube's sprite sheets, so it has no need for a second decoder.
        // Some playback clients omit storyboards from their response; fetch those in the background
        // through the older video-info path rather than delaying playback.
        if (info.isLive) {
            YouTubeStoryboards.clear(videoId)
        } else {
            val directStoryboard = runCatching { info.createStoryboard() }.getOrNull()
            Log.i(TAG, "storyboard video=$videoId direct=${directStoryboard != null}")
            YouTubeStoryboards.put(videoId, directStoryboard)
            if (directStoryboard == null) YouTubeStoryboards.ensure(videoId)
        }
        Log.i(TAG, "formats video=$videoId dash=${info.containsDashFormats()} " +
            "sabr=${info.containsSabrFormats()} hls=${info.containsHlsUrl()} " +
            "adaptive=${info.adaptiveFormats?.size} urlFormats=${info.urlFormats?.size ?: 0} " +
            "live=${info.isLive} client=${info.clientInfo?.clientName}")
        Log.i(TAG, "live sources video=$videoId hls=${info.hlsManifestUrl != null} " +
            "dashManifest=${!info.dashManifestUrl.isNullOrBlank()} urlFormats=${info.urlFormats?.size ?: 0}")
        // YouTube VODs play through ExoPlayer, which can use the generated DASH manifest for
        // quality switching and seeking. Live streams use HLS.
        val url = if (info.isLive) {
            info.hlsManifestUrl
                ?: dashUrl(videoId, info)
                ?: info.dashManifestUrl
                ?: info.urlFormats?.firstOrNull()?.url
        } else {
            dashUrl(videoId, info)
                ?: info.hlsManifestUrl
                ?: info.dashManifestUrl
                ?: info.urlFormats?.firstOrNull()?.url
        } ?: error("OneTube didn't return a stream for this video. Try again in a moment.")
        Log.i(TAG, "ready $videoId after ${since()}ms")
        // Stream URLs are tied to the client that asked for them, so the player has to present
        // the same user-agent.
        val headers = info.clientInfo?.userAgent?.takeIf { it.isNotBlank() }
            ?.let { mapOf("User-Agent" to it) }.orEmpty()
        return YtPlayable(
            url,
            video.title,
            video.thumbnail,
            videoId,
            headers,
            isLive = info.isLive
        )
    }

    /** One lookup per video at a time: hovering a card and then pressing OK share the same request. */
    private fun lookup(video: YtItem): Deferred<Result<YtPlayable>> {
        val id = video.videoId ?: return CompletableDeferred(Result.failure(IllegalStateException("Not a video")))
        resolved[id]?.takeIf { it.isFresh }?.let { return CompletableDeferred(Result.success(it)) }
        return inflight.getOrPut(id) {
            viewModelScope.async(Dispatchers.IO) {
                runCatching { resolveBlocking(video) }.also { r ->
                    r.getOrNull()?.let {
                        if (resolved.size >= MAX_CACHED) resolved.clear()
                        resolved[id] = it
                    }
                    inflight.remove(id)
                }
            }
        }
    }

    suspend fun previewVideo(item: YtItem): YtPlayable? = lookup(item).await().getOrNull()

    /**
     * Starts looking up [item]'s stream while its card is highlighted, so pressing OK is instant.
     * Waits a moment first so scrolling past cards doesn't fire a request for each one, and drops
     * the previous card's lookup so a few quick moves can't queue up behind each other.
     */
    fun prefetch(item: YtItem) {
        val id = item.videoId ?: return
        if (item.kind != YtKind.VIDEO || resolved[id]?.isFresh == true) return
        cancelPrefetchLookup()
        prefetchJob?.cancel()
        prefetchJob = viewModelScope.launch {
            delay(PREFETCH_DELAY_MS)
            val lookup = lookup(item)
            prefetchLookup = lookup
            prefetchLookupId = id
            lookup.await()
        }
    }

    /** Stops the lookup started for a card that is no longer highlighted, unless it was opened. */
    private fun cancelPrefetchLookup() {
        val lookup = prefetchLookup ?: return
        val id = prefetchLookupId
        prefetchLookup = null
        prefetchLookupId = null
        if (id == null || _resolving.value != id) lookup.cancel()
    }

    /** A preview left the screen before its stream was found: stop waiting on it. */
    fun cancelPreview(item: YtItem) {
        val id = item.videoId ?: return
        if (item.kind != YtKind.VIDEO) return
        if (_resolving.value == id || prefetchLookupId == id || resolved[id]?.isFresh == true) return
        inflight.remove(id)?.cancel()
    }

    /** What plays after the video that's on now: the rest of the list it was picked from, then YouTube's pick. */
    private var queue: List<YtItem> = emptyList()
    private val played = HashSet<String>()

    /** What will play after the video on now, once it's been picked. Shown as the "up next" card. */
    private val _upNext = MutableStateFlow<YtItem?>(null)
    val upNext: StateFlow<YtItem?> = _upNext.asStateFlow()
    private var upNextFor: String? = null

    /**
     * Picks what plays after [currentVideoId] ahead of time, and starts looking up its stream, so the
     * card can show it and the switch is instant when the video ends.
     */
    fun prepareNext(currentVideoId: String) {
        if (upNextFor == currentVideoId) return
        upNextFor = currentVideoId
        _upNext.value = null
        viewModelScope.launch {
            val next = pickNext(currentVideoId)
            if (upNextFor == currentVideoId) _upNext.value = next
            next?.let { lookup(it) }
        }
    }

    /** The video after [currentVideoId]: on the feeds YouTube's related pick, else the list's next. */
    private suspend fun pickNext(currentVideoId: String): YtItem? {
        played += currentVideoId
        val fromList = queue.dropWhile { it.videoId != currentVideoId }.drop(1)
            .firstOrNull { it.kind == YtKind.VIDEO && !it.isLive && it.videoId !in played }
        // On the feeds, follow YouTube's related video (SmartTube's behaviour) rather than whatever
        // card happens to be next on the page. Channel, playlist and search pages keep their order.
        if (_state.value.subTitle == null) {
            withContext(Dispatchers.IO) { runCatching { upNext(currentVideoId) }.getOrNull() }
                ?.let { return it }
        }
        return fromList ?: withContext(Dispatchers.IO) { runCatching { upNext(currentVideoId) }.getOrNull() }
    }

    /**
     * Plays the video after [currentVideoId], and calls [onReady] with it on the main thread, or
     * [onNone] if there isn't one. Used when a video ends and autoplay is on.
     */
    fun playNext(currentVideoId: String, onReady: (YtPlayable) -> Unit, onNone: () -> Unit) {
        Log.i(TAG, "playNext requested after=$currentVideoId")
        viewModelScope.launch {
            // The one the card showed, if it was picked in time.
            val next = _upNext.value?.takeIf { upNextFor == currentVideoId } ?: pickNext(currentVideoId)
            if (next == null) {
                onNone()
                return@launch
            }
            lookup(next).await().fold(
                onSuccess = {
                    rememberPlayed(next)
                    onReady(it)
                },
                onFailure = { onNone() }
            )
        }
    }

    /** Videos played in this session, most recent last, so Previous can walk back. */
    private val history = ArrayDeque<YtItem>()

    private fun rememberPlayed(item: YtItem) {
        val id = item.videoId ?: return
        history.removeAll { it.videoId == id }
        history.addLast(item)
        while (history.size > MAX_HISTORY) history.removeFirst()
    }

    /**
     * Plays the video before [currentVideoId] in this session. Going back drops the current video
     * from the history, so repeated presses keep walking back instead of bouncing between two.
     */
    fun playPrevious(currentVideoId: String, onReady: (YtPlayable) -> Unit, onNone: () -> Unit) {
        history.removeAll { it.videoId == currentVideoId }
        var previous: YtItem? = null
        while (history.isNotEmpty()) {
            val candidate = history.removeLast()
            if (candidate.kind == YtKind.VIDEO && !candidate.isLive && candidate.videoId != null) {
                previous = candidate
                break
            }
        }
        val target = previous
        if (target == null) {
            onNone()
            return
        }
        viewModelScope.launch {
            lookup(target).await().fold(
                onSuccess = { onReady(it) },
                onFailure = { onNone() }
            )
        }
    }

    /** YouTube's own "up next", or the first suggestion that hasn't played yet. */
    private fun upNext(currentVideoId: String): YtItem? {
        val meta = service.mediaItemService.getMetadataObserve(currentVideoId).blockingFirst()
        val candidates = listOfNotNull(meta.nextVideo) + meta.suggestions.orEmpty().flatMap { it.mediaItems.orEmpty() }
        val items = candidates.filter(::allowed).mapNotNull(::toItem).filter { it.kind == YtKind.VIDEO && !it.isLive && it.videoId !in played }
        // Whatever follows keeps playing on from this list.
        queue = listOfNotNull(toItem(candidates.firstOrNull() ?: return null)) + items
        return items.firstOrNull()
    }

    /**
     * If your account says you stopped part-way through, and this device hasn't played it since, start
     * from there. The player resumes from what it has saved, so put the account's position there.
     */
    private fun seedResume(item: YtItem) {
        val id = item.videoId ?: return
        val durationMs = item.source.durationMs
        if (item.isLive || durationMs <= 0 || item.percentWatched !in 3..94) return
        val contentId = YouTubeProgress.ID_PREFIX + id
        if (com.nuvio.tv.livetv.record.RecordingResume.load(context, contentId) != null) return
        com.nuvio.tv.livetv.record.RecordingResume.save(context, contentId, durationMs * item.percentWatched / 100, durationMs)
    }

    /** Plays a video, or opens a channel or playlist. [onReady] runs on the main thread. */
    var lastPlayedVideoId: String? = null
        private set

    fun activate(item: YtItem, from: List<YtItem>, onReady: (YtPlayable) -> Unit) {
        if (item.kind != YtKind.VIDEO) {
            open(item)
            return
        }
        val id = item.videoId ?: return
        Log.i(TAG, "activate video=$id title=${item.title}")
        // A second press while this video is already being looked up is the same request, not a new one.
        // Checking the marker rather than the cache also covers the gap before the lookup is launched.
        if (_resolving.value == id) return
        // Drop a lookahead started for another card so this video isn't stuck behind it.
        if (prefetchLookupId != id) cancelPrefetchLookup()
        seedResume(item)
        // What was on screen with it becomes the list that plays on from here.
        queue = from.filter { it.kind == YtKind.VIDEO }
        played.clear()
        prefetchJob?.cancel()
        _resolving.value = id
        viewModelScope.launch {
            try {
                val result = lookup(item).await()
                // Another card was picked while this one was still looking up, so this one is stale.
                if (_resolving.value != id) return@launch
                result.fold(
                    onSuccess = {
                        lastPlayedVideoId = item.videoId
                        rememberPlayed(item)
                        onReady(it)
                    },
                    onFailure = { _error.value = it.message ?: "Couldn't play this video" }
                )
            } finally {
                if (_resolving.value == id) _resolving.value = null
            }
        }
    }

    /** Turns YouTube's reason for refusing a video into something that says what to do. */
    private fun friendlyReason(reason: String?): String {
        val r = reason.orEmpty().lowercase()
        val signedIn = runCatching { service.signInService.isSigned }.getOrDefault(false)
        return when {
            "age" in r || "confirm your age" in r || "inappropriate" in r ->
                if (signedIn) "This video is age-restricted, and your account can't play it."
                else "This video is age-restricted. Sign in to OneTube to watch it."
            "country" in r || "region" in r || "location" in r -> "This video isn't available in your country."
            "private" in r -> "This video is private."
            "member" in r || "join this channel" in r -> "This video is for channel members only."
            "premiere" in r || "will begin" in r || "live event" in r -> "This video hasn't started yet."
            "copyright" in r -> "This video was removed over a copyright claim."
            "removed" in r || "terminated" in r || "no longer available" in r || "unavailable" in r ->
                "This video has been removed or isn't available."
            "sign in" in r -> "OneTube wants you to sign in to watch this one."
            reason.isNullOrBlank() -> "This video can't be played."
            else -> reason
        }
    }

    private fun playable(info: MediaItemFormatInfo): Boolean {
        if (info.isUnplayable) return false
        // Live HLS works from these clients; their URLs for finished videos usually give a 403.
        if (!info.isLive && info.clientInfo?.clientName in BROKEN_CLIENTS) return false
        if (info.isLive) {
            // A live answer that only has adaptive DASH can be a dynamic manifest the player
            // can't use. Try the next client: another one usually has HLS or YouTube's own
            // manifest for the same stream.
            return info.containsHlsUrl() || !info.dashManifestUrl.isNullOrBlank() || info.containsUrlFormats()
        }
        return info.containsDashFormats()
    }

    private fun dashUrl(videoId: String, info: MediaItemFormatInfo): String? {
        if (!info.containsDashFormats()) return null
        val mpd = runCatching { info.createMpdStream()?.use { it.readBytes() } }.getOrNull()
        Log.i(TAG, "mpd bytes=${mpd?.size}")
        if (mpd == null || mpd.isEmpty()) return null
        // The engine writes a live manifest as dynamic, without the availability time the player's
        // DASH engine requires, so it would fail with "Unexpected IllegalStateException". Leave those
        // to HLS or YouTube's own manifest.
        if (mpd.toString(Charsets.UTF_8).contains("type=\"dynamic\"")) {
            Log.i(TAG, "mpd is dynamic; not using it")
            return null
        }
        return YouTubeMpdServer.publish(videoId, mpd, info.visitorCookie, captionKeepLanguages.value)
    }

    // ---- search suggestions and recent searches ----

    private val _suggestions = MutableStateFlow<List<String>>(emptyList())
    val suggestions: StateFlow<List<String>> = _suggestions.asStateFlow()
    private var suggestJob: Job? = null

    /** What to offer under the search box for [text]; with nothing typed, your recent searches. */
    fun suggest(text: String) {
        suggestJob?.cancel()
        suggestJob = viewModelScope.launch {
            delay(250)
            val found = withContext(Dispatchers.IO) {
                runCatching { content.getSearchTagsObserve(text.trim()).blockingFirst() }.getOrNull().orEmpty()
            }
            _suggestions.value = found.filter { it.isNotBlank() }.take(8)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { content.clearSearchHistory() }
            _suggestions.value = emptyList()
        }
    }

    fun clearError() { _error.value = null }
}
