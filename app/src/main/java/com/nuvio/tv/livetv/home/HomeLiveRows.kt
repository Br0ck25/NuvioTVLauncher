package com.nuvio.tv.livetv.home

import android.content.Context
import androidx.compose.runtime.Immutable
import com.nuvio.tv.R
import com.nuvio.tv.core.util.withAppLocale
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.livetv.data.LiveTvChannelStore
import com.nuvio.tv.livetv.data.LiveTvPreferences
import com.nuvio.tv.livetv.model.ChannelGroup
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** Per-profile row configuration and the current content/ordering metadata Home needs. */
@Singleton
class HomeLiveRows @Inject constructor(
    @ApplicationContext private val context: Context,
    private val prefs: LiveTvPreferences,
    private val channelStore: LiveTvChannelStore,
    private val layoutPrefs: LayoutPreferenceDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val rows: Flow<List<HomeLiveRow>> = prefs.homeRowsJson
        .map(HomeLiveRow::decode)
        .distinctUntilChanged()

    val shownRows: Flow<List<HomeLiveRow>> = combine(rows, prefs.settings) { configured, settings ->
        if (settings.showInSidebar) configured else emptyList()
    }.distinctUntilChanged()

    val names: StateFlow<Map<String, String>> = combine(
        rows,
        prefs.userState,
        channelStore.displayChannels
    ) { configured, user, channels ->
        if (configured.isEmpty()) return@combine emptyMap<String, String>()
        val groups = configured.filter { it.kind == HomeLiveRow.KIND_CHANNELS }.mapTo(HashSet()) { it.source }
        val discoveredNames = HashMap<String, String>()
        channels.forEach { channel ->
            if (channel.groupId in groups && channel.groupId !in discoveredNames) {
                discoveredNames[channel.groupId] = channel.group
            }
        }
        val localized = context.withAppLocale()
        configured.associate { row ->
            row.id to row.title.ifBlank {
                when (row.kind) {
                    HomeLiveRow.KIND_SPORTS -> sportsTitle(localized, row.source)
                    HomeLiveRow.KIND_VOD -> when (row.source) {
                        HomeLiveRow.VOD_RECENT_MOVIES -> localized.getString(R.string.home_rows_vod_recent_movies_title)
                        HomeLiveRow.VOD_RECENT_SERIES -> localized.getString(R.string.home_rows_vod_recent_series_title)
                        HomeLiveRow.VOD_CONTINUE -> localized.getString(R.string.home_rows_vod_continue_title)
                        else -> {
                            val uid = row.source.removePrefix(HomeLiveRow.VOD_GROUP_PREFIX)
                            val title = user.vodGroupNames[uid]?.takeIf(String::isNotBlank)
                                ?: row.label.ifBlank { localized.getString(R.string.home_rows_type_vod) }
                            title
                        }
                    }
                    else -> when (row.source) {
                        ChannelGroup.FAVORITES -> localized.getString(R.string.home_rows_favorites_title)
                        ChannelGroup.RECENT -> localized.getString(R.string.home_rows_recent_title)
                        else -> user.customGroups.firstOrNull { it.id == row.source }?.name
                            ?: (user.groupNames[row.source]?.takeIf(String::isNotBlank) ?: discoveredNames[row.source])
                            ?: row.source.substringAfter("::").ifBlank { localized.getString(R.string.home_rows_channels_title) }
                    }
                }
            }
        }
    }.flowOn(Dispatchers.Default).stateIn(scope, SharingStarted.Eagerly, emptyMap<String, String>())

    private val emptyLiveIds = kotlinx.coroutines.flow.MutableStateFlow<Set<String>>(emptySet())
    private val vodRowsState = kotlinx.coroutines.flow.MutableStateFlow<Map<String, CatalogRow>>(emptyMap())
    val vodRows: StateFlow<Map<String, CatalogRow>> = vodRowsState

    val emptyRowIds: StateFlow<Set<String>> = combine(rows, emptyLiveIds, vodRowsState) { configured, live, vod ->
        configured.filter { row ->
            if (row.kind == HomeLiveRow.KIND_VOD) vod[row.id]?.items.isNullOrEmpty() else row.id in live
        }.mapTo(HashSet<String>()) { it.id }
    }.distinctUntilChanged().stateIn(scope, SharingStarted.Eagerly, emptySet<String>())

    fun setEmptyLiveRows(ids: Set<String>) { emptyLiveIds.value = ids }
    fun setVodRows(rows: Map<String, CatalogRow>) { vodRowsState.value = rows }

    @Volatile private var placeTrackingStarted = false
    private var seenAnchors: Map<String, String> = emptyMap()

    /** Synchronizes configured-row placement with the catalog order without disturbing other rows. */
    fun keepPlaces() {
        if (placeTrackingStarted) return
        placeTrackingStarted = true
        scope.launch {
            combine(layoutPrefs.homeCatalogOrderKeys, rows) { order, configured -> order to configured }
                .debounce(1_500)
                .collectLatest { (order, configured) ->
                    if (configured.isEmpty()) return@collectLatest
                    val missing = configured.filter { it.key !in order }
                    if (missing.isNotEmpty() && order.isNotEmpty()) {
                        val updated = order.toMutableList()
                        missing.forEach { row ->
                            val index = if (row.after.isBlank()) 0 else updated.indexOf(row.after).let { if (it < 0) -1 else it + 1 }
                            if (index >= 0) updated.add(index, row.key)
                        }
                        if (updated.size != order.size) {
                            layoutPrefs.setHomeCatalogOrderKeys(updated)
                            return@collectLatest
                        }
                    }
                    val anchors = configured.mapNotNull { row ->
                        val index = order.indexOf(row.key)
                        if (index < 0) null else row.id to order.subList(0, index)
                            .lastOrNull { !HomeLiveRow.isKey(it) }.orEmpty()
                    }.toMap()
                    val previous = seenAnchors
                    seenAnchors = anchors
                    val moved = anchors.filter { (id, anchor) -> previous[id]?.let { it != anchor } == true }
                    if (moved.isNotEmpty()) updateAll { list ->
                        list.map { row -> moved[row.id]?.let { row.copy(after = it) } ?: row }
                    }
                }
        }
    }

    suspend fun add(row: HomeLiveRow) = updateAll { it + row }
    suspend fun update(row: HomeLiveRow) = updateAll { rows -> rows.map { if (it.id == row.id) row else it } }
    suspend fun remove(id: String) = updateAll { rows -> rows.filterNot { it.id == id } }
    suspend fun setAll(rows: List<HomeLiveRow>) = updateAll { rows.distinctBy { it.id } }

    private suspend fun updateAll(transform: (List<HomeLiveRow>) -> List<HomeLiveRow>) {
        prefs.editHomeRows { raw -> HomeLiveRow.encode(transform(HomeLiveRow.decode(raw))) }
    }

    private fun sportsTitle(context: Context, source: String): String = when {
        source.startsWith("sport:") -> com.nuvio.tv.livetv.sports.Sport.entries
            .firstOrNull { it.name == source.removePrefix("sport:") }
            ?.let { context.getString(R.string.home_rows_sports_one_title, it.label) }
            ?: context.getString(R.string.home_rows_sports_title)
        source.startsWith("league:") -> com.nuvio.tv.livetv.sports.SportsCatalog.league(source.removePrefix("league:"))
            ?.let { context.getString(R.string.home_rows_sports_one_title, it.name) }
            ?: context.getString(R.string.home_rows_sports_title)
        else -> context.getString(R.string.home_rows_sports_title)
    }
}
