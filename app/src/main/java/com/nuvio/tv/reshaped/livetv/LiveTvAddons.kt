package com.nuvio.tv.reshaped.livetv

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

@EntryPoint
@InstallIn(SingletonComponent::class)
interface LiveTvAddonsEntryPoint {
    fun addonRepository(): AddonRepository
    fun catalogRepository(): CatalogRepository
    fun streamRepository(): StreamRepository
}

/**
 * Channels from addons the viewer already has installed: the addon's `tv` / `channel` catalogs are
 * listed for picking, and a picked channel's link is asked of the addon each time it is played.
 */
internal object LiveTvAddons {
    private const val TAG = "LiveTvAddons"
    private const val MAX_PAGES = 6
    private const val PAGE_TIMEOUT_MS = 15_000L
    private const val STREAM_TIMEOUT_MS = 15_000L
    private val CHANNEL_TYPES = setOf("tv", "channel")

    private fun deps(context: Context): LiveTvAddonsEntryPoint =
        EntryPointAccessors.fromApplication(context.applicationContext, LiveTvAddonsEntryPoint::class.java)

    private val LEADING_SYMBOLS = Regex("""^[\p{So}\p{Sk}\u200D\uFE0F\u20E3\s]+""")

    /** [text] without the emoji addons put in front of names ("📺 ABC"); unchanged if nothing else is left. */
    fun cleanLabel(text: String): String = text.replace(LEADING_SYMBOLS, "").trim().ifBlank { text.trim() }

    /** A catalog that lists channels and can be read without a search term or other required input. */
    private fun CatalogDescriptor.isChannelCatalog(): Boolean =
        apiType.lowercase() in CHANNEL_TYPES && !isSearchOnlyCatalog() && extra.none { it.isRequired }

    /** The installed, enabled addons that offer at least one channel catalog. */
    suspend fun channelAddons(context: Context): List<Addon> =
        deps(context).addonRepository().getInstalledAddons().first()
            .filter { it.enabled && it.catalogs.any { catalog -> catalog.isChannelCatalog() } }

    /** Every channel the addon's channel catalogs list (first few pages of each), without repeats. */
    suspend fun channelsOf(context: Context, addon: Addon): List<LiveTvAddonChannel> {
        val catalogs = deps(context).catalogRepository()
        val seen = HashSet<String>()
        val result = ArrayList<LiveTvAddonChannel>()
        addon.catalogs.filter { it.isChannelCatalog() }.forEach { catalog ->
            val step = catalog.pageSize ?: 100
            var skip = 0
            repeat(MAX_PAGES) {
                val response = withTimeoutOrNull(PAGE_TIMEOUT_MS) {
                    catalogs.getCatalog(
                        addonBaseUrl = addon.baseUrl,
                        addonId = addon.id,
                        addonName = addon.displayName,
                        catalogId = catalog.id,
                        catalogName = catalog.name,
                        type = catalog.apiType,
                        skip = skip,
                        skipStep = step,
                        supportsSkip = true,
                    ).first { it !is NetworkResult.Loading }
                }
                val row = (response as? NetworkResult.Success)?.data ?: return@forEach
                var added = 0
                row.items.forEach { item ->
                    if (seen.add("${catalog.apiType}|${item.id}")) {
                        added++
                        result += LiveTvAddonChannel(
                            id = item.id,
                            type = catalog.apiType,
                            name = cleanLabel(item.name),
                            logo = item.logo ?: item.poster,
                            group = cleanLabel(catalog.name),
                        )
                    }
                }
                if (added == 0 || !row.hasMore) return@forEach
                skip += step
            }
        }
        return result
    }

    /** [channel] with the link the addon gives for it now, or [channel] as it is when none could be found. */
    suspend fun resolve(context: Context, settings: LiveTvAddonSettings, channel: LiveTvChannel): LiveTvChannel {
        val (type, id) = channel.addonRef?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return channel
        val addon = deps(context).addonRepository().getInstalledAddons().first()
            .firstOrNull { it.baseUrl.trimEnd('/').equals(settings.addonUrl.trimEnd('/'), ignoreCase = true) }
            ?: run {
                Log.w(TAG, "Addon ${settings.addonName} is no longer installed")
                return channel
            }
        val result = withTimeoutOrNull(STREAM_TIMEOUT_MS) {
            deps(context).streamRepository().getStreamsFromAddon(addon, type, id)
        }
        val streams = (result as? NetworkResult.Success)?.data.orEmpty()
        val stream = streams.firstOrNull { it.url?.startsWith("http", ignoreCase = true) == true }
            ?: streams.firstOrNull { it.getStreamUrl()?.startsWith("http", ignoreCase = true) == true }
            ?: return channel
        val url = stream.url?.takeIf { it.startsWith("http", ignoreCase = true) } ?: stream.getStreamUrl() ?: return channel
        return channel.copy(
            streamUrl = url,
            headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
        )
    }
}
