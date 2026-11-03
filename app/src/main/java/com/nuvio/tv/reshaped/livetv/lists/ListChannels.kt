package com.nuvio.tv.reshaped.livetv.lists

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import com.nuvio.tv.core.debrid.DirectDebridPlayableResult
import com.nuvio.tv.core.debrid.DirectDebridResolver
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.tmdb.TmdbCollectionSourceResolver
import com.nuvio.tv.core.trakt.TraktPublicListSourceResolver
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.AddonCatalogCollectionSource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TraktCollectionSource
import com.nuvio.tv.domain.model.Video
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.LibraryRepository
import com.nuvio.tv.domain.repository.MetaRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.reshaped.livetv.LiveTvChannel
import com.nuvio.tv.reshaped.livetv.LiveTvProgramme
import com.nuvio.tv.reshaped.livetv.liveTvHideKey
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ListChannelsEntryPoint {
    fun addonRepository(): AddonRepository
    fun catalogRepository(): CatalogRepository
    fun metaRepository(): MetaRepository
    fun streamRepository(): StreamRepository
    fun libraryRepository(): LibraryRepository
    fun collectionsDataStore(): CollectionsDataStore
    fun tmdbResolver(): TmdbCollectionSourceResolver
    fun traktResolver(): TraktPublicListSourceResolver
    fun watchedItems(): WatchedItemsPreferences
    fun playerSettings(): PlayerSettingsDataStore
    fun directDebridResolver(): DirectDebridResolver
}

/** No stream could be found for what is on a list channel right now. */
class ListChannelUnavailableException(message: String) : Exception(message)

/** An entry of a list, as far as scheduling needs it. */
internal data class ListItem(val id: String, val type: String, val name: String, val poster: String?, val description: String?)

/**
 * Channels made from the viewer's lists, played as if they were live. Each channel has a
 * schedule: its lists' items (movies and shows mixed) in a fixed shuffled order, one after the
 * other, each lasting as long as it runs. A show plays its next episode that neither the viewer
 * nor this channel has played. The schedule is kept on the device and carried on as time
 * passes; tuning in joins the programme on now part-way through, and the next one starts when
 * it ends. Streams come from the viewer's addons, the way a film's do.
 */
internal object ListChannels {
    private const val TAG = "ListChannels"
    private const val HORIZON_MS = 3L * 60 * 60 * 1000
    private const val KEEP_PAST_MS = 6L * 60 * 60 * 1000
    private const val MAX_SLOTS = 40
    private const val MAX_PER_LIST = 60
    private const val POOL_TTL_MS = 20L * 60 * 1000
    private const val TICK_MS = 60_000L
    private const val NETWORK_TIMEOUT_MS = 20_000L
    private const val STREAMS_TIMEOUT_MS = 25_000L
    private const val DEFAULT_MOVIE_MIN = 100
    private const val DEFAULT_EPISODE_MIN = 45
    private const val MAX_BUILD_FAILURES = 6
    private const val MAX_STREAM_ATTEMPTS = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val mutex = Mutex()
    private val _channels = MutableStateFlow<List<ListChannel>>(emptyList())
    val channels: StateFlow<List<ListChannel>> = _channels.asStateFlow()

    @Volatile private var slotsById: Map<String, List<ListSlot>> = emptyMap()
    @Volatile private var store: ListChannelStore? = null
    @Volatile private var profileId = -1
    private var appContext: Context? = null
    private var tickJob: Job? = null
    private val poolCache = HashMap<String, Pair<Long, List<ListItem>>>()
    /** The programme each channel was last opened on, for where in it playback starts. */
    private val opened = ConcurrentHashMap<String, ListSlot>()

    /** Set by Live TV: the list of channels changed. */
    @Volatile var onChannelsChanged: () -> Unit = {}
    /** Set by Live TV: what is on now moved on. */
    @Volatile var onProgrammesChanged: () -> Unit = {}

    private fun deps(): ListChannelsEntryPoint =
        EntryPointAccessors.fromApplication(appContext!!, ListChannelsEntryPoint::class.java)

    /** Loads [profileId]'s channels and starts keeping their schedules up to date. Reads files: not on the main thread. */
    fun attach(context: Context, profileId: Int) {
        if (this.profileId == profileId && store != null) return
        detach()
        val app = context.applicationContext
        appContext = app
        this.profileId = profileId
        val saved = ListChannelStore(app, profileId)
        val list = saved.channels()
        slotsById = list.associate { it.id to saved.slots(it.id) }
        store = saved
        _channels.value = list
        tickJob = scope.launch {
            while (isActive) {
                keepSchedulesCurrent()
                delay(TICK_MS)
            }
        }
    }

    fun detach() {
        tickJob?.cancel()
        tickJob = null
        store = null
        profileId = -1
        slotsById = emptyMap()
        opened.clear()
        synchronized(poolCache) { poolCache.clear() }
        _channels.value = emptyList()
    }

    // region Channels

    fun create(name: String, refs: List<ListRef>): ListChannel =
        ListChannel(
            id = "${System.currentTimeMillis()}-${(0..9999).random()}",
            name = name.trim(),
            refs = refs,
            seed = java.util.Random().nextLong(),
        ).also(::save)

    /** Adds [channel] or replaces the one with its id. Other lists mean a new schedule. */
    fun save(channel: ListChannel) {
        val saved = store ?: return
        val before = _channels.value.firstOrNull { it.id == channel.id }
        val list = if (before == null) _channels.value + channel else _channels.value.map { if (it.id == channel.id) channel else it }
        if (before != null && before.refs.map(ListRef::key) != channel.refs.map(ListRef::key)) resetSchedule(channel.id, saved)
        saved.saveChannels(list)
        _channels.value = list
        onChannelsChanged()
        scope.launch { runCatching { extend(channel, System.currentTimeMillis()) }; onProgrammesChanged() }
    }

    fun remove(id: String) {
        val saved = store ?: return
        val list = _channels.value.filterNot { it.id == id }
        resetSchedule(id, saved)
        saved.saveChannels(list)
        _channels.value = list
        onChannelsChanged()
    }

    private fun resetSchedule(id: String, saved: ListChannelStore) {
        saved.delete(id)
        slotsById = slotsById - id
        opened.remove(id)
        synchronized(poolCache) { poolCache.remove(id) }
    }

    fun liveTvChannels(): List<LiveTvChannel> = _channels.value.map { channel ->
        LiveTvChannel(
            id = listChannelGuideKey(channel.id),
            name = channel.name,
            streamUrl = listChannelUrl(channel.id),
            group = LIST_CHANNELS_GROUP,
            sourceId = LIST_CHANNELS_SOURCE_ID,
            hideKey = liveTvHideKey(LIST_CHANNELS_SOURCE_ID, LIST_CHANNELS_GROUP, channel.id),
            guideKey = listChannelGuideKey(channel.id),
        )
    }

    // endregion

    // region Guide

    /** What is on now for each list channel, by guide key. A memory lookup. */
    fun nowProgrammes(nowMs: Long = System.currentTimeMillis()): Map<String, LiveTvProgramme> {
        val result = HashMap<String, LiveTvProgramme>()
        slotsById.forEach { (id, slots) ->
            slots.firstOrNull { nowMs in it.startMs until it.endMs }?.let { result[listChannelGuideKey(id)] = it.toProgramme() }
        }
        return result
    }

    /** The scheduled programmes of the channel with [guideKey], earliest first. */
    fun schedule(guideKey: String): List<LiveTvProgramme> {
        if (!guideKey.startsWith("list:")) return emptyList()
        return slotsById[guideKey.removePrefix("list:")].orEmpty().map { it.toProgramme() }
    }

    private fun ListSlot.toProgramme() = LiveTvProgramme(title, startMs, endMs, description, image)

    /** How far into the programme opened last on [channelUrl] playback should start. */
    fun startOffsetMs(channelUrl: String): Long {
        val slot = opened[listChannelIdOf(channelUrl)] ?: return 0L
        val offset = System.currentTimeMillis() - slot.startMs
        return offset.coerceIn(0L, (slot.durationMs - 20_000L).coerceAtLeast(0L))
    }

    private suspend fun keepSchedulesCurrent() {
        val now = System.currentTimeMillis()
        _channels.value.forEach { channel ->
            val last = slotsById[channel.id]?.lastOrNull()?.endMs ?: 0L
            if (last < now + HORIZON_MS / 2) {
                try {
                    extend(channel, now)
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    Log.w(TAG, "Could not carry on the schedule of ${channel.name}", error)
                }
            }
        }
        onProgrammesChanged()
    }

    // endregion

    // region Schedule

    /**
     * Carries [channel]'s schedule on until it covers the next few hours. A schedule the viewer
     * was away from for longer than it covered starts again from now, part-way through its first item.
     */
    private suspend fun extend(channel: ListChannel, now: Long): List<ListSlot> = mutex.withLock {
        val saved = store ?: return@withLock emptyList()
        var slots = (slotsById[channel.id] ?: saved.slots(channel.id)).filter { it.endMs > now - KEEP_PAST_MS }
        if (slots.isNotEmpty() && slots.last().endMs < now) slots = emptyList()
        if ((slots.lastOrNull()?.endMs ?: 0L) >= now + HORIZON_MS) return@withLock slots

        val items = pool(channel)
        if (items.isEmpty()) return@withLock slots
        var state = saved.state(channel.id)
        var failures = 0
        while ((slots.lastOrNull()?.endMs ?: 0L) < now + HORIZON_MS && failures < MAX_BUILD_FAILURES && slots.size < MAX_SLOTS) {
            val item = items[state.cursor % items.size]
            state = state.copy(cursor = state.cursor + 1)
            val built = buildSlot(item, state.played)
            if (built == null) {
                failures++
                continue
            }
            val (slot, playedId) = built
            if (playedId != null) state = state.copy(played = state.played + playedId)
            // A new schedule joins its first programme part-way, the way a channel left on would be.
            val start = slots.lastOrNull()?.endMs
                ?: (now - (slot.durationMs * (((channel.seed and Long.MAX_VALUE) + state.cursor) % 55) / 100))
            slots = slots + slot.copy(startMs = start, endMs = start + slot.durationMs)
        }
        saved.saveSchedule(channel.id, slots, state)
        slotsById = slotsById + (channel.id to slots)
        slots
    }

    /** The programme for [item]: a film, or the show's next unplayed episode. Times are filled in by the caller. */
    private suspend fun buildSlot(item: ListItem, played: Set<String>): Pair<ListSlot, String?>? {
        val d = deps()
        val meta: Meta = fetch { d.metaRepository().getMetaFromAllAddons(item.type, item.id) } ?: return null
        val episodes = meta.videos
            .filter { it.season != null && it.episode != null && isReleased(it) }
            .let { all -> all.filter { (it.season ?: 0) > 0 }.ifEmpty { all } }
            .sortedWith(compareBy<Video>({ it.season }, { it.episode }))
        if (episodes.isEmpty()) {
            if (meta.videos.isNotEmpty()) return null // a show with nothing out yet
            val minutes = (parseRuntimeMinutes(meta.runtime) ?: DEFAULT_MOVIE_MIN).coerceIn(5, 240)
            return ListSlot(
                itemId = item.id, itemType = item.type, videoId = item.id, season = null, episode = null,
                title = meta.name.ifBlank { item.name }, description = meta.description ?: item.description,
                image = meta.background ?: meta.poster ?: item.poster,
                startMs = 0L, endMs = minutes * 60_000L,
            ) to null
        }
        val watched = runCatching { d.watchedItems().getWatchedEpisodesForContent(item.id).first() }.getOrDefault(emptySet())
        val fresh = episodes.filter { video ->
            video.id !in played && (video.season to video.episode) !in watched
        }
        // Everything seen: the show starts over from its first episode not played on this channel.
        val pick = fresh.firstOrNull() ?: episodes.firstOrNull { it.id !in played } ?: episodes.first()
        val minutes = (pick.runtime ?: parseRuntimeMinutes(meta.runtime) ?: DEFAULT_EPISODE_MIN).coerceIn(5, 240)
        val label = "S${pick.season}E${pick.episode}"
        return ListSlot(
            itemId = item.id, itemType = item.type, videoId = pick.id, season = pick.season, episode = pick.episode,
            title = "${meta.name.ifBlank { item.name }} · $label ${pick.title}".trim(),
            description = pick.overview ?: meta.description ?: item.description,
            image = pick.thumbnail ?: meta.background ?: meta.poster ?: item.poster,
            startMs = 0L, endMs = minutes * 60_000L,
        ) to pick.id
    }

    private fun isReleased(video: Video): Boolean {
        if (video.available == false) return false
        val released = video.released?.take(10)?.takeIf { it.length == 10 } ?: return true
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        return released <= today
    }

    private fun parseRuntimeMinutes(text: String?): Int? {
        val t = text?.lowercase()?.trim().orEmpty()
        if (t.isEmpty()) return null
        val hours = Regex("""(\d+)\s*h""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        val minutes = Regex("""(\d+)\s*m""").find(t)?.groupValues?.get(1)?.toIntOrNull()
        return when {
            hours != null -> hours * 60 + (minutes ?: 0)
            minutes != null -> minutes
            else -> Regex("""\d+""").find(t)?.value?.toIntOrNull()
        }?.takeIf { it > 0 }
    }

    // endregion

    // region Items

    /** The channel's items: its lists mixed, in the channel's own fixed shuffled order. */
    private suspend fun pool(channel: ListChannel): List<ListItem> {
        val cached = synchronized(poolCache) { poolCache[channel.id] }
        if (cached != null && System.currentTimeMillis() - cached.first < POOL_TTL_MS) return cached.second
        val merged = LinkedHashMap<String, ListItem>()
        channel.refs.forEach { ref ->
            val items = try {
                items(ref).take(MAX_PER_LIST)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not read ${ref.title}", error)
                emptyList()
            }
            items.forEach { merged.putIfAbsent("${it.type}:${it.id}", it) }
        }
        val shuffled = merged.values.toList().shuffled(java.util.Random(channel.seed))
        if (shuffled.isNotEmpty()) synchronized(poolCache) { poolCache[channel.id] = System.currentTimeMillis() to shuffled }
        return shuffled
    }

    private suspend fun items(ref: ListRef): List<ListItem> {
        val d = deps()
        return when (ref) {
            is ListRef.Catalog -> catalogItems(ref.addonId, ref.type, ref.catalogId, ref.genre, ref.title)
            is ListRef.Library -> d.libraryRepository().libraryItems.first()
                .filter { ref.listKey in it.listKeys }
                .map { ListItem(it.id, it.type, it.name, it.poster, it.description) }
            is ListRef.Folder -> {
                val folder = d.collectionsDataStore().getCurrentCollections()
                    .firstOrNull { it.id == ref.collectionId }?.folders?.firstOrNull { it.id == ref.folderId }
                    ?: return emptyList()
                folder.sources.flatMap { source ->
                    when (source) {
                        is AddonCatalogCollectionSource ->
                            catalogItems(source.addonId, source.type, source.catalogId, source.genre, ref.title)
                        is TmdbCollectionSource -> rowItems(fetch { d.tmdbResolver().resolve(source, 1) })
                        is TraktCollectionSource -> rowItems(fetch { d.traktResolver().resolve(source, 1) })
                        else -> emptyList()
                    }
                }
            }
        }
    }

    private suspend fun catalogItems(addonId: String, type: String, catalogId: String, genre: String?, title: String): List<ListItem> {
        val d = deps()
        val addon = d.addonRepository().getInstalledAddons().first().enabledAddons().firstOrNull { it.id == addonId }
            ?: return emptyList()
        val catalog = addon.catalogs.firstOrNull { it.id == catalogId && it.apiType == type }
            ?: addon.catalogs.firstOrNull { it.id == catalogId }
        val row = fetch {
            d.catalogRepository().getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalogId,
                catalogName = catalog?.name ?: title,
                type = catalog?.apiType ?: type,
                skip = 0,
                skipStep = catalog?.skipStep() ?: 100,
                extraArgs = genre?.let { mapOf("genre" to it) } ?: emptyMap(),
                supportsSkip = catalog?.supportsExtra("skip") ?: false,
            )
        }
        return rowItems(row)
    }

    private fun rowItems(row: CatalogRow?): List<ListItem> =
        row?.items.orEmpty().map { ListItem(it.id, it.apiType, it.name, it.poster, it.description) }

    /** The last result a repository flow gave, within the time allowed; null when it gave none. */
    private suspend fun <T> fetch(block: () -> Flow<NetworkResult<T>>): T? {
        var latest: T? = null
        withTimeoutOrNull(NETWORK_TIMEOUT_MS) {
            block().collect { if (it is NetworkResult.Success) latest = it.data }
        }
        return latest
    }

    /** Every list a channel can be made from: addon catalogs, collection folders and library lists. */
    suspend fun availableLists(): List<ListRef> {
        val d = deps()
        val result = ArrayList<ListRef>()
        d.addonRepository().getInstalledAddons().first().enabledAddons().forEach { addon ->
            addon.catalogs
                .filter { it.extraRequired.isEmpty() && it.apiType != "channel" && it.apiType != "tv" }
                .forEach { catalog ->
                    result += ListRef.Catalog(addon.id, catalog.apiType, catalog.id, "${catalog.name} · ${catalog.apiType} · ${addon.displayName}")
                }
        }
        d.collectionsDataStore().getCurrentCollections().forEach { collection ->
            collection.folders.forEach { folder ->
                result += ListRef.Folder(collection.id, folder.id, "${collection.title} / ${folder.title}")
            }
        }
        runCatching { d.libraryRepository().listTabs.first() }.getOrDefault(emptyList()).forEach { tab ->
            result += ListRef.Library(tab.key, tab.title)
        }
        return result
    }

    // endregion

    // region Playback

    /**
     * The channel as the player should open it: the stream for what is on now, found through the
     * viewer's addons and, where needed, made playable by their debrid service (see [pickStream]).
     * Throws [ListChannelUnavailableException] when none is found.
     */
    suspend fun resolve(channel: LiveTvChannel): LiveTvChannel {
        val id = listChannelIdOf(channel.streamUrl)
        val definition = _channels.value.firstOrNull { it.id == id }
            ?: throw ListChannelUnavailableException("Channel is gone")
        toast(R.string.live_tv_list_finding, definition.name)
        val now = System.currentTimeMillis()
        val slots = extend(definition, now)
        if (slots.isEmpty()) throw ListChannelUnavailableException("Nothing to play")
        var index = slots.indexOfFirst { now in it.startMs until it.endMs }.takeIf { it >= 0 } ?: slots.lastIndex
        var tries = 0
        while (index in slots.indices && tries < 3) {
            val slot = slots[index]
            val stream = pickStream(slot)
            val url = stream?.getStreamUrl()
            if (stream != null && url != null) {
                opened[id] = slot
                onProgrammesChanged()
                return channel.copy(streamUrl = url, headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty())
            }
            index++
            tries++
        }
        throw ListChannelUnavailableException("No stream found")
    }

    /**
     * The stream to play for [slot], ready to open. Streams the viewer's debrid service can
     * play (Torbox, Real-Debrid, Premiumize: addon-provided ones and torrent hashes resolved
     * with their own key) are prepared the way the player would, and plain links are used as
     * they are. The autoplay choice is tried first; if its link cannot be made (not cached,
     * key missing) the next few are tried. Peer-to-peer torrents and web pages are left out.
     */
    private suspend fun pickStream(slot: ListSlot): Stream? {
        val d = deps()
        var found: List<AddonStreams> = emptyList()
        withTimeoutOrNull(STREAMS_TIMEOUT_MS) {
            d.streamRepository().getStreamsFromAllAddons(slot.itemType, slot.videoId, slot.season, slot.episode).collect { result ->
                if (result is NetworkResult.Success) found = result.data
            }
        }
        val resolver = d.directDebridResolver()
        val candidates = ArrayList<Stream>()
        for (stream in found.flatMap { it.streams }) {
            if (stream.serverTarget != null || stream.youTubeIdToResolve() != null || stream.isExternal()) continue
            if (stream.debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED) continue
            val url = stream.getStreamUrl()
            val direct = url != null && (url.startsWith("http://", true) || url.startsWith("https://", true)) && !stream.isTorrent()
            if (direct || resolver.shouldResolveToPlayableStream(stream)) candidates += stream
        }
        if (candidates.isEmpty()) return null

        val settings = d.playerSettings().playerSettings.first()
        val installed = d.addonRepository().getInstalledAddons().first().map { it.displayName }.toSet()
        // A channel cannot ask which stream to play: manual picking falls back to the first one.
        val mode = if (settings.streamAutoPlayMode == StreamAutoPlayMode.MANUAL) StreamAutoPlayMode.FIRST_STREAM else settings.streamAutoPlayMode
        val preferred = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = candidates,
            mode = mode,
            regexPattern = settings.streamAutoPlayRegex,
            source = settings.streamAutoPlaySource,
            installedAddonNames = installed,
            selectedAddons = settings.streamAutoPlaySelectedAddons,
            selectedPlugins = settings.streamAutoPlaySelectedPlugins,
        )
        // Then known-cached and plain links before the rest, since a miss costs a round trip.
        val rest = candidates.filter { it !== preferred }.sortedBy { stream ->
            if (stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED || stream.getStreamUrl() != null) 0 else 1
        }
        for (stream in listOfNotNull(preferred) + rest.take(MAX_STREAM_ATTEMPTS)) {
            val playable = try {
                resolver.resolveToPlayableStream(stream, slot.season, slot.episode)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(TAG, "Could not prepare a stream for ${slot.title}", error)
                continue
            }
            if (playable is DirectDebridPlayableResult.Success && playable.stream.getStreamUrl() != null) return playable.stream
        }
        return null
    }

    private fun toast(resId: Int, vararg args: Any) {
        val app = appContext ?: return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(app, app.getString(resId, *args), Toast.LENGTH_SHORT).show()
        }
    }

    // endregion
}
