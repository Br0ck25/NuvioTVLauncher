package com.nuvio.tv.livetv.sync

import com.nuvio.tv.livetv.model.PlaylistSource
import java.net.URI
import java.util.Locale

/**
 * Finds playlists that exist in a saved setup but not on this TV, and describes them
 * without exposing logins. Recovery only ever adds playlists; it never removes or
 * replaces the ones this TV already has.
 */
internal object PlaylistRecovery {

    /** The same playlist can have a new ID after a re-add, so match on its address too. */
    fun key(source: PlaylistSource): String {
        val url = source.url.trim().trimEnd('/').lowercase(Locale.US)
        if (url.isNotBlank()) return "url:$url"
        val server = source.xtreamServer.trim().trimEnd('/').lowercase(Locale.US)
        val user = source.xtreamUsername.trim().lowercase(Locale.US)
        if (server.isNotBlank()) return "xtream:$server|$user"
        return "id:${source.id}"
    }

    /** [candidates] that are not already on this TV, in their original order. */
    fun missing(
        candidates: List<PlaylistSource>,
        local: List<PlaylistSource>
    ): List<PlaylistSource> {
        val localIds = local.mapTo(HashSet()) { it.id }
        val localKeys = local.mapTo(HashSet()) { key(it) }
        return candidates.asSequence()
            .filter { it.id.isNotBlank() }
            .filter { it.id !in localIds && key(it) !in localKeys }
            .distinctBy { key(it) }
            .toList()
    }

    /** A safe one-line description (host only, never the login). */
    fun detail(source: PlaylistSource): String {
        val host = runCatching { URI(source.url.trim()).host }.getOrNull()
        return when {
            !host.isNullOrBlank() -> host
            source.xtreamServer.isNotBlank() ->
                source.xtreamServer.substringAfter("://").trimEnd('/')
            source.url.isNotBlank() ->
                source.url.substringAfter("://").substringBefore('/')
            else -> "Playlist"
        }
    }
}
