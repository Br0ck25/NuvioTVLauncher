package com.nuvio.tv.reshaped.livetv.lists

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.repository.AddonRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.net.URLEncoder
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A 24/7 channel offered by an installed My Lists addon. There is no account or sync in the launcher:
 * installing the addon (e.g. `https://mylistsaddon.com/<id>/manifest.json`) is all it takes, and its
 * channels are read straight from that addon's manifest.
 */
internal data class MyListsTvChannel(
    val id: String,
    val name: String,
    val catalogId: String,
    /** Base URL of the installed addon that offers the channel (no trailing slash, no `manifest.json`). */
    val addonBaseUrl: String,
    val category: String = MY_LISTS_CHANNELS_GROUP,
    val logoUrl: String? = null,
    val posterUrl: String? = null,
    val backdropUrl: String? = null,
)

internal object MyListsAddon {
    const val ORIGIN = "https://mylistsaddon.com"

    fun isAddonUrl(url: String): Boolean = url.trim().lowercase().startsWith(ORIGIN)

    fun isAddon(addon: Addon): Boolean = isAddonUrl(addon.baseUrl) || addon.id.startsWith("app.my-list", ignoreCase = true)

    /** The account segment of an addon URL such as `https://mylistsaddon.com/3tvELzOsQFXM`, which the server's per-account routes are asked for. */
    fun targetId(baseUrl: String): String? {
        val path = baseUrl.trim().trimEnd('/').removeSuffix("/manifest.json").removePrefix(ORIGIN).trim('/')
        val segments = path.split('/').filter { it.isNotBlank() && !it.equals("tv", ignoreCase = true) }
        return when {
            segments.size >= 2 && (segments[0] == "u" || segments[0] == "i") -> segments[1]
            segments.size == 1 -> segments[0]
            else -> null
        }
    }

    /** A catalog is a channel when the addon names it so (`channel_…`, `channel-…` or type `channel`). */
    private fun isChannelCatalog(id: String, type: String): Boolean =
        id.startsWith("channel_", ignoreCase = true) ||
            id.startsWith("channel-", ignoreCase = true) ||
            type.equals("channel", ignoreCase = true)

    fun channelsOf(addon: Addon): List<MyListsTvChannel> {
        val base = addon.baseUrl.trim().trimEnd('/')
        return addon.catalogs
            .filter { isChannelCatalog(it.id, it.apiType) && it.name.isNotBlank() }
            .map { catalog ->
                MyListsTvChannel(id = catalog.id, name = catalog.name, catalogId = catalog.id, addonBaseUrl = base)
            }
    }

    private fun absolute(url: String): String = when {
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true) -> url
        url.startsWith("/") -> "$ORIGIN$url"
        else -> "$ORIGIN/$url"
    }

    fun resolveLogoUrl(logoUrl: String?): String? = logoUrl?.trim()?.takeIf { it.isNotEmpty() }?.let(::absolute)

    /** Landscape artwork the server draws for a channel by name. */
    fun backdropUrl(channelName: String): String {
        val encoded = URLEncoder.encode(channelName.trim().ifBlank { "Channel" }, "UTF-8")
        return "$ORIGIN/api/channel-poster?name=$encoded&format=landscape&v=6"
    }

    /** Portrait artwork the server draws for a channel by name. */
    fun posterUrl(channelName: String): String {
        val encoded = URLEncoder.encode(channelName.trim().ifBlank { "Channel" }, "UTF-8")
        return "$ORIGIN/api/channel-poster?name=$encoded&v=6"
    }
}

/** The channels of every enabled My Lists addon the viewer has installed; empty when there is none. */
@Singleton
class MyListsChannelSource @Inject constructor(
    private val addonRepository: AddonRepository,
) {
    internal val channelsFlow: Flow<List<MyListsTvChannel>> = addonRepository.getInstalledAddons()
        .map { addons ->
            addons.filter { it.enabled && MyListsAddon.isAddon(it) }
                .flatMap(MyListsAddon::channelsOf)
                .distinctBy { it.id }
        }
        .distinctUntilChanged()
}
