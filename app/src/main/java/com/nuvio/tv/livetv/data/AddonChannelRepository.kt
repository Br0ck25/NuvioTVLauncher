package com.nuvio.tv.livetv.data

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.livetv.model.AddonChannelRef
import com.nuvio.tv.livetv.model.AddonChannelSource
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns the `tv` catalogs of the installed Stremio addons into guide channels.
 *
 * A catalog entry only gives the channel; its stream is looked up when it's opened, because
 * live links expire. The catalog itself is cached for a few minutes: several screens call
 * this while the guide starts, and one addon request per screen would be wasteful.
 */
@Singleton
class AddonChannelRepository @Inject constructor(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository
) {
    data class Loaded(
        val channels: List<LiveChannel>,
        /** Source id -> why it returned nothing (or fewer channels), when it failed. */
        val errors: Map<String, String>,
        /** Source id -> channels this load produced. */
        val counts: Map<String, Int>
    )

    private val cacheMs = 10 * 60_000L
    private var cacheKey: String? = null
    private var cacheAt = 0L
    private var cached = Loaded(emptyList(), emptyMap(), emptyMap())

    suspend fun load(
        sources: List<AddonChannelSource>,
        force: Boolean = false
    ): Loaded {
        val enabled = sources.filter { it.enabled && it.id.isNotBlank() }
        if (enabled.isEmpty()) return Loaded(emptyList(), emptyMap(), emptyMap())

        val key = enabled.joinToString("|") { "${it.id}:${it.enabled}" }
        val now = System.currentTimeMillis()
        if (!force && key == cacheKey && now - cacheAt < cacheMs) return cached

        val addons = addonRepository.getInstalledAddons()
            .first()
            .associateBy { it.baseUrl.trimEnd('/') }
        val out = ArrayList<LiveChannel>()
        val errors = HashMap<String, String>()
        val counts = HashMap<String, Int>()
        val usedKeys = HashSet<String>()

        for (source in enabled) {
            val addon = addons[source.addonBaseUrl.trimEnd('/')]
            if (addon == null) {
                errors[source.id] = "This addon is no longer installed."
                counts[source.id] = 0
                continue
            }
            val catalog = addon.catalogs.firstOrNull {
                it.id == source.catalogId && it.rawType.equals("tv", ignoreCase = true)
            }
            if (catalog == null) {
                errors[source.id] = "This Live TV catalog is no longer in the addon."
                counts[source.id] = 0
                continue
            }

            var made = 0
            when (val result = fetchCatalog(addon, catalog)) {
                is CatalogResult.Items -> {
                    result.items.forEach { item ->
                        val name = item.name.trim().takeIf { it.isNotBlank() } ?: return@forEach
                        val channelId = item.id.trim().takeIf { it.isNotBlank() } ?: return@forEach
                        // Event catalogs carry a clean title and start time in the id. Use the
                        // title for the card (the catalog name often has an emoji prefix) and
                        // remember the time for the guide.
                        val event = AddonGuideInfo.event(channelId)
                        val displayName = event?.title?.takeIf { it.isNotBlank() } ?: name
                        val sourceId = addonSourceId(addon.baseUrl)
                        val baseKey = "$sourceId|$channelId|$displayName"
                        var channelKey = baseKey
                        var n = 2
                        while (!usedKeys.add(channelKey)) {
                            channelKey = "$baseKey#${n++}"
                        }
                        out += LiveChannel(
                            key = channelKey,
                            sourceId = sourceId,
                            sourceName = addon.displayName,
                            name = displayName,
                            tvgId = channelId,
                            tvgName = displayName,
                            logo = item.logo ?: item.poster,
                            groupId = "$sourceId::${catalog.id}",
                            group = "${addon.displayName} · ${catalog.name}",
                            number = 0,
                            url = "",
                            addon = AddonChannelRef(
                                addonBaseUrl = addon.baseUrl,
                                addonName = addon.displayName,
                                addonLogo = addon.logo,
                                catalogId = catalog.id,
                                channelId = channelId,
                                channelType = "tv"
                            )
                        )
                        made++
                    }
                }
                is CatalogResult.Failed -> errors[source.id] = result.message
            }
            counts[source.id] = made
            android.util.Log.i(
                "LiveTvAddons",
                "catalog ${source.addonName}/${source.catalogName}: $made channels" +
                    (errors[source.id]?.let { " ($it)" } ?: "")
            )
        }

        cached = Loaded(out, errors, counts)
        cacheKey = key
        cacheAt = now
        return cached
    }

    /**
     * The first build stored one entry per addon. The guide now stores one per catalog; turn
     * the old entry into its addon's Live TV catalogs so an existing selection keeps working.
     */
    suspend fun migrateLegacy(sources: List<AddonChannelSource>): List<AddonChannelSource> {
        if (sources.none { it.catalogId.isBlank() }) return sources
        val addons = addonRepository.getInstalledAddons().first()
        return sources.flatMap { source ->
            if (source.catalogId.isNotBlank()) {
                listOf(source)
            } else {
                val base = source.addonBaseUrl.ifBlank { source.id }
                val addon = addons.firstOrNull { it.baseUrl.trimEnd('/') == base.trimEnd('/') }
                addon?.catalogs
                    ?.filter { it.rawType.equals("tv", ignoreCase = true) }
                    ?.map { catalog ->
                        source.copy(
                            id = "${addon.baseUrl}|${catalog.id}",
                            addonBaseUrl = addon.baseUrl,
                            addonName = addon.displayName,
                            addonLogo = addon.logo,
                            catalogId = catalog.id,
                            catalogName = catalog.name,
                            lastUpdatedMs = 0L,
                            lastError = null,
                            channelCount = 0
                        )
                    }.orEmpty()
            }
        }.distinctBy { it.id }
    }

    /** Drop the cache after the selected addons/catalogs change. */
    fun invalidate() {
        cacheKey = null
        cacheAt = 0L
        cached = Loaded(emptyList(), emptyMap(), emptyMap())
    }

    private sealed class CatalogResult {
        data class Items(val items: List<MetaPreview>) : CatalogResult()
        data class Failed(val message: String) : CatalogResult()
    }

    /** Fetches up to [MAX_PAGES] pages; a live catalog is usually one page. */
    private suspend fun fetchCatalog(
        addon: Addon,
        catalog: CatalogDescriptor
    ): CatalogResult {
        val items = ArrayList<MetaPreview>()
        val seenIds = HashSet<String>()
        var skip = 0
        var pages = 0
        var lastError: String? = null
        while (pages < MAX_PAGES) {
            val result = catalogRepository.getCatalog(
                addonBaseUrl = addon.baseUrl,
                addonId = addon.id,
                addonName = addon.displayName,
                catalogId = catalog.id,
                catalogName = catalog.name,
                type = "tv",
                skip = skip,
                skipStep = PAGE_SIZE,
                extraArgs = emptyMap(),
                supportsSkip = true
            ).first { it !is NetworkResult.Loading }

            when (result) {
                is NetworkResult.Success -> {
                    val row: CatalogRow = result.data
                    // Some addons ignore `skip` and return the first page again; stop instead
                    // of adding the same channels five times.
                    val fresh = row.items.filter { seenIds.add("${it.rawType}:${it.id}") }
                    items += fresh
                    pages++
                    if (!row.hasMore || fresh.isEmpty()) break
                    val next = row.nextSkip.takeIf { it > skip } ?: (skip + PAGE_SIZE)
                    if (next <= skip) break
                    skip = next
                }
                is NetworkResult.Error -> {
                    lastError = result.message
                    break
                }
                NetworkResult.Loading -> Unit
            }
        }
        return if (items.isNotEmpty()) CatalogResult.Items(items)
        else CatalogResult.Failed(lastError ?: "The addon returned no channels.")
    }

    private companion object {
        const val PAGE_SIZE = 100
        const val MAX_PAGES = 5
    }
}

/** The source id its channels carry, so a saved list can tell them from a turned-off playlist's. */
internal fun addonSourceId(baseUrl: String): String =
    "addon_${baseUrl.trimEnd('/').hashCode()}_${baseUrl.trimEnd('/').length}"
