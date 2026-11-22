package com.nuvio.tv.livetv.player

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import com.nuvio.tv.livetv.model.LiveChannel
import kotlinx.coroutines.flow.first
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** One playable link returned by an addon for a Live TV channel. */
data class AddonStreamSource(
    val url: String,
    val headers: Map<String, String>,
    val label: String,
    val qualityValue: Int
)

/**
 * Looks up an addon-backed channel's streams at play time. Live links are short-lived, so
 * they are cached only briefly; the channel list itself is cached by [com.nuvio.tv.livetv.data.AddonChannelRepository].
 */
@Singleton
class AddonChannelStreamResolver @Inject constructor(
    private val addonRepository: AddonRepository,
    private val streamRepository: StreamRepository
) {
    private val cache = ConcurrentHashMap<String, Pair<Long, List<AddonStreamSource>>>()

    suspend fun resolve(channel: LiveChannel): Result<List<AddonStreamSource>> {
        val ref = channel.addon ?: return Result.failure(IllegalStateException("Not an add-on channel"))
        val now = System.currentTimeMillis()
        cache[channel.key]?.let { (at, sources) ->
            if (now - at < CACHE_MS) return Result.success(sources)
        }

        val addon = addonRepository.getInstalledAddons()
            .first()
            .firstOrNull { it.baseUrl.trimEnd('/') == ref.addonBaseUrl.trimEnd('/') }
            ?: return Result.failure(IllegalStateException("${ref.addonName} is no longer installed"))

        return when (val result = streamRepository.getStreamsFromAddon(addon, ref.channelType, ref.channelId)) {
            is NetworkResult.Success -> {
                val sources = result.data.mapNotNull { stream ->
                    // `externalUrl` is usually the provider's page, not a video: only the
                    // direct `url` can be handed to the Live TV player.
                    val url = stream.url?.takeIf { it.startsWith("http", ignoreCase = true) }
                        ?: return@mapNotNull null
                    AddonStreamSource(
                        url = url,
                        headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
                        label = stream.name ?: stream.title ?: "Source",
                        qualityValue = stream.qualityValue
                    )
                }.sortedByDescending { it.qualityValue }
                if (sources.isEmpty()) {
                    Result.failure(IllegalStateException("${ref.addonName} didn't return a playable link"))
                } else {
                    android.util.Log.i(
                        "LiveTvAddons",
                        "resolved ${channel.name} from ${ref.addonName}: ${sources.size} links, using ${sources.first().url}"
                    )
                    cache[channel.key] = now to sources
                    if (cache.size > 32) cache.clear()
                    Result.success(sources)
                }
            }
            is NetworkResult.Error -> Result.failure(IllegalStateException(result.message))
            NetworkResult.Loading -> Result.failure(IllegalStateException("Still loading"))
        }
    }

    private companion object {
        const val CACHE_MS = 90_000L
    }
}
