package com.nuvio.tv.music

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.MoodGenre
import com.music.bitchord.data.model.MoodGenreSection
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** The top tabs of the Music section. Library and Settings join these in a later wave. */
enum class MusicTab(val title: String) {
    HOME("Home"),
    EXPLORE("Explore"),
    LIBRARY("Library"),
    LOCAL("Local"),
    SEARCH("Search")
}

data class MusicHomeState(
    val loading: Boolean = true,
    val shelves: List<HomeShelf> = emptyList(),
    val error: String? = null,
    val loadingMore: Boolean = false,
    val continuation: String? = null,
    /** True when these shelves came from the signed-in YouTube Music account. */
    val account: Boolean = false
)

data class MusicSearchState(
    val query: String = "",
    val filter: SearchFilter = SearchFilter.ALL,
    val submitted: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val rows: List<SearchResult> = emptyList(),
    /** Rows from the user's music addons, kept separate so a YouTube page can be replaced. */
    val addonRows: List<SearchResult> = emptyList(),
    val continuation: String? = null,
    val error: String? = null,
    val suggestions: List<String> = emptyList()
) {
    /** The playable tracks in this result page, in display order. */
    val songs: List<Song>
        get() = rows.mapNotNull {
            when (it) {
                is SearchResult.TopTrack -> it.song
                is SearchResult.Track -> it.song
                is SearchResult.Browse -> null
            }
        }
}

data class MusicDetailState(
    val open: Boolean = false,
    val loading: Boolean = false,
    val loadingMore: Boolean = false,
    val browseId: String = "",
    val title: String = "",
    val subtitle: String = "",
    val thumbnailUrl: String? = null,
    val type: BrowseType = BrowseType.OTHER,
    val songs: List<Song> = emptyList(),
    /** The artist page's own blurb, when it has one. */
    val description: String = "",
    val suggested: List<Song> = emptyList(),
    val sections: List<HomeShelf> = emptyList(),
    val continuation: String? = null,
    val error: String? = null
)

data class MusicExploreState(
    val loading: Boolean = true,
    val sections: List<MoodGenreSection> = emptyList(),
    val error: String? = null,
    val selected: MoodGenre? = null,
    val selectedLoading: Boolean = false,
    val selectedShelves: List<HomeShelf> = emptyList(),
    val selectedError: String? = null
)

data class MusicAccountLibraryState(
    val signedIn: Boolean = false,
    val loading: Boolean = false,
    val liked: List<Song> = emptyList(),
    val playlists: List<MusicAccountPlaylist> = emptyList(),
    val error: String? = null
)

data class MusicUiState(
    val tab: MusicTab = MusicTab.HOME,
    val home: MusicHomeState = MusicHomeState(),
    val search: MusicSearchState = MusicSearchState(),
    val detail: MusicDetailState = MusicDetailState(),
    val explore: MusicExploreState = MusicExploreState(),
    val accountLibrary: MusicAccountLibraryState = MusicAccountLibraryState()
)

@HiltViewModel
class MusicViewModel @Inject constructor(
    @ApplicationContext private val context: Context
) : ViewModel() {
    private companion object {
        const val TAG = "MusicVM"
    }

    private val _state = MutableStateFlow(MusicUiState())
    val state: StateFlow<MusicUiState> = _state.asStateFlow()

    private var homeJob: Job? = null
    private var homeMoreJob: Job? = null
    private var searchJob: Job? = null
    private var searchMoreJob: Job? = null
    private var addonSearchJob: Job? = null
    private var suggestionJob: Job? = null
    private var detailJob: Job? = null
    private var detailMoreJob: Job? = null
    private var exploreJob: Job? = null
    private var moodJob: Job? = null
    private var accountJob: Job? = null

    init {
        MusicAccountRepository.init(context)
        MusicAddonRegistry.init(context)
        loadHome()
    }

    // ---- tabs ----

    fun selectTab(tab: MusicTab) {
        if (_state.value.tab == tab) return
        _state.update { it.copy(tab = tab) }
        when (tab) {
            MusicTab.HOME -> if (_state.value.home.shelves.isEmpty() && _state.value.home.error == null) {
                loadHome()
            }
            MusicTab.EXPLORE -> if (_state.value.explore.sections.isEmpty() &&
                _state.value.explore.error == null
            ) {
                loadExplore()
            }
            MusicTab.SEARCH -> Unit
            MusicTab.LIBRARY -> loadAccountLibrary()
            MusicTab.LOCAL -> Unit
        }
    }

    // ---- home ----

    fun loadHome() {
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            _state.update { it.copy(home = it.home.copy(loading = true, error = null)) }
            val signedIn = MusicAccountRepository.isSignedIn()
            val personal = if (signedIn) {
                withContext(Dispatchers.IO) {
                    runCatching { MusicAccountRepository.home() }.getOrNull()
                }?.getOrNull()?.takeIf { it.isNotEmpty() }
            } else {
                null
            }
            if (personal != null) {
                android.util.Log.i(TAG, "account home: ${personal.size} shelves")
                _state.update {
                    it.copy(
                        home = it.home.copy(
                            loading = false,
                            shelves = personal,
                            continuation = null,
                            error = null,
                            account = true
                        )
                    )
                }
                return@launch
            }
            val result = withContext(Dispatchers.IO) { YtMusicRepository.home() }
            result.fold(
                onSuccess = { feed ->
                    android.util.Log.i(TAG, "home: ${feed.shelves.size} shelves")
                    _state.update {
                        it.copy(
                            home = it.home.copy(
                                loading = false,
                                shelves = feed.shelves.filter { shelf -> shelf.items.isNotEmpty() },
                                continuation = feed.continuation,
                                error = null,
                                account = false
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            home = it.home.copy(
                                loading = false,
                                error = error.message ?: "Couldn't load your music.",
                                account = false
                            )
                        )
                    }
                }
            )
        }
    }

    fun loadMoreHome() {
        val current = _state.value.home
        if (current.account) return
        val token = current.continuation ?: return
        if (current.loadingMore || current.loading) return
        homeMoreJob?.cancel()
        homeMoreJob = viewModelScope.launch {
            _state.update { it.copy(home = it.home.copy(loadingMore = true)) }
            val result = withContext(Dispatchers.IO) { YtMusicRepository.moreHome(token) }
            result.fold(
                onSuccess = { feed ->
                    _state.update {
                        val seen = it.home.shelves.map { shelf -> shelf.title }.toSet()
                        it.copy(
                            home = it.home.copy(
                                loadingMore = false,
                                shelves = it.home.shelves +
                                    feed.shelves.filter { shelf ->
                                        shelf.items.isNotEmpty() && shelf.title !in seen
                                    },
                                continuation = feed.continuation
                            )
                        )
                    }
                },
                onFailure = {
                    _state.update { it.copy(home = it.home.copy(loadingMore = false)) }
                }
            )
        }
    }

    /** Reloads both halves after a sign-in, sign-out or account switch. */
    fun reloadForAccount() {
        MusicAccountRepository.invalidate()
        loadHome()
        loadAccountLibrary()
    }

    /** Pulls likes and playlists again without reloading the whole Music home. */
    fun refreshAccountLibrary() {
        if (!MusicAccountRepository.isSignedIn()) {
            loadAccountLibrary()
            return
        }
        MusicAccountRepository.invalidate()
        loadAccountLibrary()
    }

    fun loadAccountLibrary() {
        val signedIn = MusicAccountRepository.isSignedIn()
        if (!signedIn) {
            accountJob?.cancel()
            _state.update {
                it.copy(accountLibrary = MusicAccountLibraryState(signedIn = false))
            }
            return
        }
        accountJob?.cancel()
        accountJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    accountLibrary = it.accountLibrary.copy(
                        signedIn = true,
                        loading = true,
                        error = null
                    )
                )
            }
            val result = withContext(Dispatchers.IO) {
                MusicAccountRepository.library()
            }
            result.fold(
                onSuccess = { library ->
                    _state.update {
                        it.copy(
                            accountLibrary = MusicAccountLibraryState(
                                signedIn = true,
                                loading = false,
                                liked = library.liked,
                                playlists = library.playlists
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            accountLibrary = it.accountLibrary.copy(
                                loading = false,
                                error = error.message ?: "Couldn't load your music library."
                            )
                        )
                    }
                }
            )
        }
    }

    // ---- search ----

    fun onSearchQueryChanged(value: String) {
        _state.update { it.copy(search = it.search.copy(query = value)) }
        scheduleSuggestions(value)
    }

    fun submitSearch(query: String = _state.value.search.query) {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            addonSearchJob?.cancel()
            _state.update {
                it.copy(
                    search = it.search.copy(
                        submitted = false,
                        rows = emptyList(),
                        addonRows = emptyList()
                    )
                )
            }
            return
        }
        searchJob?.cancel()
        searchMoreJob?.cancel()
        addonSearchJob?.cancel()
        _state.update {
            it.copy(
                search = it.search.copy(
                    query = trimmed,
                    submitted = true,
                    loading = true,
                    rows = emptyList(),
                    addonRows = emptyList(),
                    continuation = null,
                    error = null,
                    suggestions = emptyList()
                )
            )
        }
        searchAddons(trimmed, _state.value.search.filter)
        searchJob = viewModelScope.launch {
            val filter = _state.value.search.filter
            val result = withContext(Dispatchers.IO) {
                YtMusicRepository.searchPage(trimmed, filter)
            }
            result.fold(
                onSuccess = { page ->
                    android.util.Log.i(TAG, "search '$trimmed': ${page.rows.size} rows")
                    _state.update {
                        if (it.search.query != trimmed || it.search.filter != filter) it
                        else it.copy(
                            search = it.search.copy(
                                loading = false,
                                rows = it.search.addonRows + page.rows,
                                continuation = page.continuation,
                                error = null
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        if (it.search.query != trimmed || it.search.filter != filter) it
                        else it.copy(
                            search = it.search.copy(
                                loading = false,
                                error = error.message ?: "Search failed."
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Addon results arrive independently of YouTube's, so a slow addon never delays the normal
     * catalogue. Only songs and the combined view ask for them; albums and artists are YouTube-only.
     */
    private fun searchAddons(query: String, filter: SearchFilter) {
        if (filter != SearchFilter.ALL && filter != SearchFilter.SONGS) return
        if (MusicAddonRegistry.configs.value.none { it.enabled }) return
        addonSearchJob = viewModelScope.launch {
            val hits = withContext(Dispatchers.IO) { MusicAddonRegistry.search(query) }
            val rows = hits.map { SearchResult.Track(it.track.toSong(it.config)) }
            _state.update { current ->
                if (current.search.query != query || current.search.filter != filter) current
                else {
                    val old = current.search.addonRows
                    val youtubeRows = current.search.rows.filterNot { row -> old.any { it == row } }
                    current.copy(
                        search = current.search.copy(
                            addonRows = rows,
                            rows = rows + youtubeRows
                        )
                    )
                }
            }
        }
    }

    fun setSearchFilter(filter: SearchFilter) {
        if (_state.value.search.filter == filter) return
        _state.update { it.copy(search = it.search.copy(filter = filter)) }
        val query = _state.value.search.query.trim()
        if (query.isNotEmpty() && _state.value.search.submitted) submitSearch(query)
    }

    fun loadMoreSearch() {
        val current = _state.value.search
        val token = current.continuation ?: return
        if (current.loading || current.loadingMore) return
        searchMoreJob?.cancel()
        searchMoreJob = viewModelScope.launch {
            _state.update { it.copy(search = it.search.copy(loadingMore = true)) }
            val filter = _state.value.search.filter
            val result = withContext(Dispatchers.IO) {
                YtMusicRepository.searchContinuation(token, filter)
            }
            result.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            search = it.search.copy(
                                loadingMore = false,
                                rows = it.search.rows + page.rows,
                                continuation = page.continuation
                            )
                        )
                    }
                },
                onFailure = {
                    _state.update { it.copy(search = it.search.copy(loadingMore = false)) }
                }
            )
        }
    }

    private fun scheduleSuggestions(value: String) {
        suggestionJob?.cancel()
        if (value.isBlank()) {
            _state.update { it.copy(search = it.search.copy(suggestions = emptyList())) }
            return
        }
        suggestionJob = viewModelScope.launch {
            delay(220L)
            val found = withContext(Dispatchers.IO) {
                runCatching { YtMusicRepository.searchSuggestions(value).getOrNull() }.getOrNull()
            }.orEmpty()
            _state.update {
                if (it.search.query != value) it
                else it.copy(search = it.search.copy(suggestions = found.take(8)))
            }
        }
    }

    // ---- detail ----

    fun openDetail(
        browseId: String,
        title: String,
        subtitle: String,
        thumbnailUrl: String?,
        type: BrowseType
    ) {
        if (browseId.isBlank()) return
        if (browseId.startsWith("account:")) {
            openAccountDetail(browseId, title, subtitle, thumbnailUrl, type)
            return
        }
        detailJob?.cancel()
        detailMoreJob?.cancel()
        _state.update {
            it.copy(
                detail = MusicDetailState(
                    open = true,
                    loading = true,
                    browseId = browseId,
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnailUrl,
                    type = type
                )
            )
        }
        detailJob = viewModelScope.launch {
            val isArtist = type == BrowseType.ARTIST || browseId.startsWith("UC")
            val result = withContext(Dispatchers.IO) {
                if (isArtist) {
                    YtMusicRepository.artistPage(browseId).map { page ->
                        MusicDetailState(
                            open = true,
                            loading = false,
                            browseId = browseId,
                            title = title,
                            subtitle = subtitle,
                            thumbnailUrl = page.thumbnailUrl ?: thumbnailUrl,
                            type = BrowseType.ARTIST,
                            songs = page.songs,
                            sections = page.sections,
                            description = page.description.orEmpty()
                        )
                    }
                } else {
                    YtMusicRepository.browseSongs(browseId).map { page ->
                        MusicDetailState(
                            open = true,
                            loading = false,
                            browseId = browseId,
                            // The page's own header is more accurate than the card that opened
                            // it: a tapped album link arrives with only a browse id, and a
                            // playlist card can carry a preview track's title.
                            title = page.header?.title?.takeIf { it.isNotBlank() } ?: title,
                            subtitle = page.header?.subtitle?.takeIf { it.isNotBlank() }
                                ?: subtitle,
                            thumbnailUrl = page.header?.thumbnailUrl ?: thumbnailUrl,
                            type = type,
                            songs = page.songs,
                            description = page.description.orEmpty(),
                            suggested = page.suggested,
                            continuation = page.continuation
                        )
                    }
                }
            }
            result.fold(
                onSuccess = { detail ->
                    android.util.Log.i(TAG, "detail ${detail.browseId}: ${detail.songs.size} songs")
                    _state.update {
                        if (it.detail.browseId != browseId) it
                        else it.copy(detail = detail)
                    }
                },
                onFailure = { error ->
                    _state.update {
                        if (it.detail.browseId != browseId) it
                        else it.copy(
                            detail = it.detail.copy(
                                loading = false,
                                error = error.message ?: "Couldn't open this page."
                            )
                        )
                    }
                }
            )
        }
    }

    /**
     * Opens [song]'s artist page. Rows from Home and some playlists carry no channel id, so
     * the name is looked up (exact match only) before giving up. [onOpened] runs when the
     * page really opens, so the caller can close Now Playing without leaving a dead screen
     * when no artist page exists.
     */
    fun openArtist(song: Song, onOpened: () -> Unit = {}) {
        val name = song.artist.trim()
        if (name.isEmpty()) return
        val direct = song.artistId?.takeIf { it.isNotBlank() }
            ?: song.artists.firstOrNull { it.name.equals(name, ignoreCase = true) }
                ?.browseId?.takeIf { it.isNotBlank() }
        if (direct != null) {
            openDetail(
                browseId = direct,
                title = name,
                subtitle = "Artist",
                thumbnailUrl = song.thumbnailUrl,
                type = BrowseType.ARTIST
            )
            onOpened()
            return
        }
        viewModelScope.launch {
            val id = withContext(Dispatchers.IO) {
                runCatching { YtMusicRepository.findArtistPageId(name) }
                    .getOrNull()?.getOrNull()
            }
            if (!id.isNullOrBlank()) {
                openDetail(
                    browseId = id,
                    title = name,
                    subtitle = "Artist",
                    thumbnailUrl = song.thumbnailUrl,
                    type = BrowseType.ARTIST
                )
                onOpened()
            }
        }
    }

    /** Opens [song]'s album page, looking the link up when the row didn't carry one. */
    fun openAlbum(song: Song, onOpened: () -> Unit = {}) {
        if (song.localUri != null) return
        val direct = song.albumId?.takeIf { it.isNotBlank() }
        if (direct != null) {
            openDetail(
                browseId = direct,
                title = song.albumName?.takeIf { it.isNotBlank() } ?: "Album",
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                type = BrowseType.ALBUM
            )
            onOpened()
            return
        }
        viewModelScope.launch {
            val full = withContext(Dispatchers.IO) {
                runCatching { YtMusicRepository.trackLinks(song.videoId) }
                    .getOrNull()?.getOrNull()
            }
            val id = full?.albumId?.takeIf { it.isNotBlank() } ?: return@launch
            openDetail(
                browseId = id,
                title = full.albumName?.takeIf { it.isNotBlank() } ?: "Album",
                subtitle = song.artist,
                thumbnailUrl = song.thumbnailUrl,
                type = BrowseType.ALBUM
            )
            onOpened()
        }
    }

    private fun openAccountDetail(
        browseId: String,
        title: String,
        subtitle: String,
        thumbnailUrl: String?,
        type: BrowseType
    ) {
        detailJob?.cancel()
        detailMoreJob?.cancel()
        _state.update {
            it.copy(
                detail = MusicDetailState(
                    open = true,
                    loading = true,
                    browseId = browseId,
                    title = title,
                    subtitle = subtitle,
                    thumbnailUrl = thumbnailUrl,
                    type = type
                )
            )
        }
        detailJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                when {
                    browseId.startsWith("account:playlist:") ->
                        MusicAccountRepository.playlistSongs(
                            browseId.removePrefix("account:playlist:")
                        )
                    browseId.startsWith("account:group:") ->
                        MusicAccountRepository.groupSongs(browseId)
                    else -> Result.failure(IllegalArgumentException("Unknown account shelf"))
                }
            }
            result.fold(
                onSuccess = { songs ->
                    _state.update {
                        if (it.detail.browseId != browseId) it
                        else it.copy(
                            detail = it.detail.copy(loading = false, songs = songs)
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        if (it.detail.browseId != browseId) it
                        else it.copy(
                            detail = it.detail.copy(
                                loading = false,
                                error = error.message ?: "Couldn't open this page."
                            )
                        )
                    }
                }
            )
        }
    }

    fun loadMoreDetail() {
        val current = _state.value.detail
        if (current.browseId.startsWith("account:")) return
        val token = current.continuation ?: return
        if (current.loading || current.loadingMore || !current.open) return
        detailMoreJob?.cancel()
        detailMoreJob = viewModelScope.launch {
            _state.update { it.copy(detail = it.detail.copy(loadingMore = true)) }
            val result = withContext(Dispatchers.IO) { YtMusicRepository.moreSongs(token) }
            result.fold(
                onSuccess = { page ->
                    _state.update {
                        it.copy(
                            detail = it.detail.copy(
                                loadingMore = false,
                                songs = it.detail.songs + page.songs,
                                continuation = page.continuation
                            )
                        )
                    }
                },
                onFailure = {
                    _state.update { it.copy(detail = it.detail.copy(loadingMore = false)) }
                }
            )
        }
    }

    fun closeDetail() {
        detailJob?.cancel()
        detailMoreJob?.cancel()
        _state.update { it.copy(detail = MusicDetailState()) }
    }

    // ---- explore ----

    fun loadExplore() {
        exploreJob?.cancel()
        exploreJob = viewModelScope.launch {
            _state.update { it.copy(explore = it.explore.copy(loading = true, error = null)) }
            val result = withContext(Dispatchers.IO) { YtMusicRepository.moodAndGenres() }
            result.fold(
                onSuccess = { sections ->
                    _state.update {
                        it.copy(
                            explore = it.explore.copy(
                                loading = false,
                                sections = sections,
                                error = null
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        it.copy(
                            explore = it.explore.copy(
                                loading = false,
                                error = error.message ?: "Couldn't load Explore."
                            )
                        )
                    }
                }
            )
        }
    }

    fun openMood(mood: MoodGenre) {
        moodJob?.cancel()
        _state.update {
            it.copy(
                explore = it.explore.copy(
                    selected = mood,
                    selectedLoading = true,
                    selectedShelves = emptyList(),
                    selectedError = null
                )
            )
        }
        moodJob = viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                YtMusicRepository.moodGenreShelves(mood.browseId, mood.params)
            }
            result.fold(
                onSuccess = { shelves ->
                    _state.update {
                        if (it.explore.selected?.browseId != mood.browseId) it
                        else it.copy(
                            explore = it.explore.copy(
                                selectedLoading = false,
                                selectedShelves = shelves.filter { shelf -> shelf.items.isNotEmpty() }
                            )
                        )
                    }
                },
                onFailure = { error ->
                    _state.update {
                        if (it.explore.selected?.browseId != mood.browseId) it
                        else it.copy(
                            explore = it.explore.copy(
                                selectedLoading = false,
                                selectedError = error.message ?: "Couldn't open this category."
                            )
                        )
                    }
                }
            )
        }
    }

    fun closeMood() {
        moodJob?.cancel()
        _state.update {
            it.copy(
                explore = it.explore.copy(
                    selected = null,
                    selectedShelves = emptyList(),
                    selectedError = null
                )
            )
        }
    }

    // ---- playback helpers ----

    /** Plays a song card and the rest of the row or list it came from. */
    fun play(items: List<Song>, startIndex: Int) {
        MusicPlayer.playQueue(items, startIndex)
    }

    fun playShelfItem(shelf: HomeShelf, itemIndex: Int) {
        val songs = shelf.items.mapNotNull { item ->
            item.videoId?.let { id ->
                Song(
                    videoId = id,
                    title = item.title,
                    artist = item.subtitle,
                    thumbnailUrl = item.thumbnailUrl,
                    isVideo = item.isVideo
                )
            }
        }
        if (songs.isEmpty()) return
        val clicked = shelf.items.getOrNull(itemIndex) ?: return
        val start = songs.indexOfFirst { it.videoId == clicked.videoId }.coerceAtLeast(0)
        MusicPlayer.playQueue(songs, start)
    }

    /** Plays the whole detail page from [startIndex]. */
    fun playDetail(startIndex: Int) {
        val detail = _state.value.detail
        if (detail.songs.isEmpty()) return
        MusicPlayer.playQueue(detail.songs, startIndex)
    }
}
